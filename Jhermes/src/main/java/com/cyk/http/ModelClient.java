package com.cyk.http;

import com.cyk.bean.*;
import com.cyk.config.HermesConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OpenAI 兼容 /chat/completions 的生产实现（JH-SPEC-001 P0-5 R0）。
 *
 * <p>失败一律抛 {@link LlmTransportException}（带状态码与可重试标记），
 * 而非裸 {@code RuntimeException}——上层 {@code Agent} 据此做指数退避重试
 * 与回合级错误边界，REPL 不再因一次 429 而整进程退出（附录 A1）。</p>
 */
public class ModelClient implements LlmClient {
    private static final Logger logger = LoggerFactory.getLogger(ModelClient.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    /** 限制输出长度的传统参数名（OpenAI 兼容接口规范）。 */
    static final String PARAM_MAX_TOKENS = "max_tokens";
    /** 较新模型（如 Azure 上的 gpt 系列）只认的新参数名。 */
    static final String PARAM_MAX_COMPLETION_TOKENS = "max_completion_tokens";

    private final HermesConfig config;
    private final OkHttpClient httpClient;

    // 解析/生成 json 数据。必须用 newStableObjectMapper() 创建，原因见该方法的注释。
    private final ObjectMapper objectMapper = newStableObjectMapper();

    /**
     * 当前请求体里"限制输出长度"用的参数名（缺陷 N-2）。
     *
     * <p>缺省是 {@code max_tokens}，所以普通模型的请求体和以前逐字节相同。
     * 只有服务端明确回复"这个模型不支持 max_tokens，请改用 max_completion_tokens"时，
     * 才会被 {@link #executeWithParamFallback} 改成新名字，并一直沿用到进程结束
     * （同一个模型之后的请求就不用再多发一次注定失败的请求了）。</p>
     *
     * <p>用 volatile 是因为主对话和"退出时的知识提取"可能出现在不同线程；
     * 只有"读"和"赋值一个不可变字符串"两种操作，volatile 已足够，不需要加锁。</p>
     */
    private volatile String maxTokensParam = PARAM_MAX_TOKENS;

    public ModelClient(HermesConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 创建"输出顺序稳定"的 JSON 序列化器（JH-SPEC-001 P0-4 R8，修复 C-08）。
     *
     * <h3>要解决的问题</h3>
     * <p>每次请求，我们都会把 16 个工具的定义（tools 数组）序列化成 JSON 发给模型。
     * 这些定义在代码里是用 {@code Map.of(...)} 写的，而 Java 的 {@code Map.of} 有个特点：
     * <b>遍历顺序在每次启动 JVM 时都是随机的</b>（官方文档明确说"迭代顺序不保证，且可能变化"）。
     * 结果就是：同样的工具、同样的内容，今天启动序列化出来是 {@code {"name":..,"type":..}}，
     * 明天启动可能变成 {@code {"type":..,"name":..}}。</p>
     *
     * <h3>为什么这是个问题（前缀缓存）</h3>
     * <p>模型服务商的"前缀缓存"是按<b>字节</b>比对请求开头的：只要开头有一个字节不一样，
     * 后面整段缓存都作废。tools 数组在请求里排在很靠前的位置，它每次字节不同，
     * 缓存就永远命不中，白白多花钱、多等时间。</p>
     *
     * <h3>怎么解决</h3>
     * <p>打开 {@code ORDER_MAP_ENTRIES_BY_KEYS}：序列化任何 Map 时，都先把 key 按字母顺序排好再输出。
     * 这样不管 Map 内部遍历顺序如何，输出的 JSON 字节始终一致。
     * 注意：这只影响 Map，不影响 List（List 本来就有确定顺序）和 JSON 对象节点。</p>
     *
     * <p>声明为 public static，是为了让 {@code Jhermes debug tools-json} 诊断命令
     * 复用同一份配置——这样它打印出来的 JSON 和真正发给模型的逐字节一致。</p>
     */
    public static ObjectMapper newStableObjectMapper() {
        return new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 把工具定义列表转成请求体里的 {@code tools} JSON 数组。
     *
     * <p>注意 {@code mapper} 必须是 {@link #newStableObjectMapper()} 创建的那种：
     * {@code valueToTree} 会在转换时按 key 字母序排列 Map 的内容，
     * 得到的 ObjectNode 之后会按这个顺序原样输出，所以最终 JSON 字节是稳定的。</p>
     *
     * @param mapper 输出顺序稳定的序列化器
     * @param tools  工具定义列表（每个元素是 {"type":"function","function":{...}} 这样的 Map）
     * @return JSON 数组节点；tools 为空时返回空数组
     */
    public static ArrayNode buildToolsArray(ObjectMapper mapper, List<Map<String, Object>> tools) {
        ArrayNode toolsArray = mapper.createArrayNode();
        for (Map<String, Object> tool : tools) {
            toolsArray.add(mapper.valueToTree(tool));
        }
        return toolsArray;
    }

    /** 错误响应体最大读取长度，防止超大 body 冲刷控制台/日志。 */
    private static final int MAX_ERROR_BODY = 500;

    /**
     * 判断一个失败的响应，是不是"服务端嫌 max_tokens 这个参数名不对"（缺陷 N-2）。
     *
     * <p>判据故意很严格，宁可漏判也不误判：</p>
     * <ul>
     *   <li>状态码必须是 400：401/403 是凭据问题，5xx 是服务端故障，都与参数名无关；</li>
     *   <li>响应体必须<b>同时</b>出现 {@code max_tokens} 和 {@code max_completion_tokens}：
     *       服务端是在"指路"——"别用前者，请用后者"。只出现 max_tokens
     *       （例如"max_tokens 不能超过 4096"）说明是数值问题，换名字没用，不能触发。</li>
     * </ul>
     *
     * <p>写成包级静态方法，是为了不依赖网络就能直接单元测试这条判断。</p>
     *
     * @param statusCode HTTP 状态码
     * @param body       服务端响应体（可为 null）
     */
    static boolean isMaxTokensParamRejection(int statusCode, String body) {
        return statusCode == 400
                && body != null
                && body.contains(PARAM_MAX_TOKENS)
                && body.contains(PARAM_MAX_COMPLETION_TOKENS);
    }

    /**
     * 发送请求；若服务端明确拒绝 {@code max_tokens} 参数名，则换成 {@code max_completion_tokens}
     * 重发<b>一次</b>（缺陷 N-2）。主对话（流式/非流式）与知识提取三处共用，避免只修一处。
     *
     * <h3>流程</h3>
     * <ol>
     *   <li>用 {@code buildRequest} 构造请求并发送；</li>
     *   <li>响应成功 → 原样返回；</li>
     *   <li>响应失败，且 {@link #isMaxTokensParamRejection} 判定是"参数名不对"，
     *       并且当前用的还是 {@code max_tokens} → 把 {@link #maxTokensParam} 改成新名字，
     *       关闭旧响应，重新构造请求再发一次；</li>
     *   <li>其他任何失败 → 原样返回，由调用方走原来的 {@link LlmTransportException} 路径。</li>
     * </ol>
     *
     * <h3>为什么只重发一次</h3>
     * <p>换名之后如果还被拒（比如两个名字它都不认），就是别的问题了。此时再换回去会形成死循环，
     * 所以判断条件里要求"当前还是旧名字"，第二次失败时直接把错误交给调用方。</p>
     *
     * <h3>调用方的责任</h3>
     * <p>返回的 {@link Response} 要由调用方用 try-with-resources 关闭（和原来一样）。
     * 本方法在决定重发时，会先关闭第一次的响应，避免连接泄漏。</p>
     *
     * @param client       用哪个 OkHttp 客户端发（知识提取用短超时的那个）
     * @param buildRequest 根据当前 {@link #maxTokensParam} 构造请求；会被调用一或两次
     * @param buildFailure 构造请求体失败（序列化出错）时的错误说明，区分是哪条调用路径
     */
    private Response executeWithParamFallback(OkHttpClient client,
                                              RequestFactory buildRequest,
                                              String buildFailure) throws IOException {
        Response response = client.newCall(buildOrFail(buildRequest, buildFailure)).execute();
        if (response.isSuccessful() || !PARAM_MAX_TOKENS.equals(maxTokensParam)) {
            return response;
        }

        // 失败了：先读出错误体判断原因。读完后流就被消耗掉了，
        // 所以若判定"不是参数名问题"，要把读到的内容重新装回响应里再交给调用方，
        // 这样调用方照常能读到错误详情。
        String errorBody = readErrorBody(response);
        if (!isMaxTokensParamRejection(response.code(), errorBody)) {
            return response.newBuilder()
                    .body(ResponseBody.create(errorBody, response.body() == null ? null : response.body().contentType()))
                    .build();
        }

        response.close();
        maxTokensParam = PARAM_MAX_COMPLETION_TOKENS;
        logger.info("服务端不接受 max_tokens，已自动改用 max_completion_tokens 重发（本次运行内后续请求沿用）");
        return client.newCall(buildOrFail(buildRequest, buildFailure)).execute();
    }

    /**
     * 构造请求；序列化失败时转成不可重试的 {@link LlmTransportException#serialization}。
     *
     * <p>为什么单独包这一层，而不是让调用方在外面 {@code catch (JsonProcessingException)}：
     * {@link JsonProcessingException} 是 {@link IOException} 的子类，
     * 而发送点里解析<b>响应体</b>的 {@code readTree} 也会抛它。若在外层统一捕获，
     * 就会把"服务端返回了坏 JSON"（原本归为可重试的网络错误）误判成"请求体构造失败"
     * （不可重试），悄悄改变错误分类。把捕获范围收窄到"构造请求"这一步，
     * 发送点原来的 {@code catch (IOException)} 语义就一字不改。</p>
     */
    private static Request buildOrFail(RequestFactory factory, String failureMessage) {
        try {
            return factory.create();
        } catch (JsonProcessingException e) {
            throw LlmTransportException.serialization(failureMessage, e);
        }
    }

    /**
     * 构造一个请求（因为换参数名后要重新构造请求体，所以不能只传一个现成的 Request）。
     * 序列化失败时抛 {@link JsonProcessingException}，由 {@link #buildOrFail} 统一转换。
     */
    @FunctionalInterface
    private interface RequestFactory {
        Request create() throws JsonProcessingException;
    }

    /**
     * 安全读取 HTTP 错误响应体。
     *
     * <p>不能直接把 {@link ResponseBody} 拼进字符串——它的 {@code toString()} 是
     * {@code okhttp3.internal.http.RealResponseBody@160c3ec1} 这种对象引用，
     * 对用户毫无信息量（旧实现的 401 报错就是这样刷屏的）。必须调用 {@code .string()}
     * 拿到服务端真正返回的 JSON 错误详情（如 "Invalid Authentication"）。</p>
     *
     * <p>注意：{@code .string()} 会消费掉流，只能调用一次；读取失败时降级为空串。</p>
     */
    private static String readErrorBody(Response response) {
        try {
            if (response.body() == null) {
                return "";
            }
            String raw = response.body().string();
            if (raw.length() > MAX_ERROR_BODY) {
                return raw.substring(0, MAX_ERROR_BODY) + "...(已截断)";
            }
            return raw;
        } catch (IOException e) {
            return "(错误响应体读取失败: " + e.getMessage() + ")";
        }
    }

    /**
     * 向LLm发送请求获取响应（非流式，整段返回）
     *
     * <p>排查指引与重试分类已由 {@link LlmTransportException} 接管，
     * 本方法只负责传输与解析。</p>
     */
    @Override
    public ChatCompletionResponse chatCompletion(List<ModelMessage> messages, List<Map<String, Object>> tools, boolean stream) {
        // 请求在 lambda 里按需构造：若需要换参数名重发，会重新走一遍 buildRequestBody
        RequestFactory factory = () -> new Request.Builder()
                .url(config.getBaseUrl() + "/chat/completions")
                .method("POST", RequestBody.create(
                        buildRequestBody(messages, tools, false, false), JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .build();

        //发送请求（max_tokens 被服务端拒绝时会自动换名重发一次）
        try (Response response = executeWithParamFallback(httpClient, factory, "构造对话请求体失败")) {
            if (!response.isSuccessful()) {
                // body 必须用 .string() 读取：直接拼 ResponseBody 只会得到 RealResponseBody@160c3ec1 对象引用
                throw LlmTransportException.http("请求失败", response.code(), readErrorBody(response));
            }

            String responseBody = response.body().string();
            JsonNode root = objectMapper.readTree(responseBody);
            return parseCompletionResponse(root);

        } catch (IOException e) {
            throw LlmTransportException.network("模型请求失败", e);
        }

    }

    /**
     * 以流式（SSE）方式向 LLM 发送请求。
     *
     * <p>OpenAI 兼容接口在 {@code stream:true} 时返回 text/event-stream，逐块下发
     * {@code data: {...}} 事件，内容以增量 delta 形式给出。本方法边读边把文本增量
     * 交给 {@code onContent} 回调实时打印，同时在内部把分散的 delta（含 tool_calls
     * 的 arguments 片段）拼装成一条完整的 {@link ChatCompletionResponse} 返回，
     * 使上层循环无需感知流式与非流式的差异。</p>
     *
     * @param messages  对话历史
     * @param tools     工具定义（可为 null）
     * @param onContent 文本增量回调（实时打印用）；可为 null 表示不回调
     * @return 组装完成的完整响应（等价于非流式的一次性返回）
     */
    @Override
    public ChatCompletionResponse chatCompletionStream(List<ModelMessage> messages,
                                                       List<Map<String, Object>> tools,
                                                       Consumer<String> onContent) {
        // stream_options.include_usage=true 让服务端在最后一个 chunk 回传 token 用量
        RequestFactory factory = () -> new Request.Builder()
                .url(config.getBaseUrl() + "/chat/completions")
                .method("POST", RequestBody.create(
                        buildRequestBody(messages, tools, true, true), JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .addHeader("Authorization", "Bearer " + config.getApiKey())
                .build();

        try (Response response = executeWithParamFallback(httpClient, factory, "构造流式请求体失败")) {
            if (!response.isSuccessful()) {
                // 失败时 body 可能是普通 JSON 错误而非 SSE，直接读出报错
                throw LlmTransportException.http("流式请求失败", response.code(), readErrorBody(response));
            }
            if (response.body() == null) {
                // 2xx 却无响应体属服务端异常行为，与传输抖动同类，归为可重试
                throw LlmTransportException.network("流式响应体为空", null);
            }
            // SSE 是 UTF-8 文本流；用 BufferedReader 按行读取事件
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                return assembleStream(reader, onContent);
            }
        } catch (IOException e) {
            throw LlmTransportException.network("流式请求失败", e);
        }
    }

    /**
     * 构建 /chat/completions 请求体 JSON。
     *
     * @param messages     对话历史
     * @param tools        工具定义（null 或空则不带 tools 字段）
     * @param stream       是否流式
     * @param includeUsage 流式时是否请求在末尾返回 token 用量
     */
    // 去掉了 private（变成"包级可见"）：只为让同包的单元测试能直接调用它，检查请求体字节是否稳定
    String buildRequestBody(List<ModelMessage> messages, List<Map<String, Object>> tools,
                                    boolean stream, boolean includeUsage) throws JsonProcessingException {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", config.getCurrentModel());

        ArrayNode messagesArray = objectMapper.createArrayNode();
        for (ModelMessage message : messages) {
            messagesArray.add(message.toJsonNode());
        }
        requestBody.set("messages", messagesArray);

        if (tools != null && !tools.isEmpty()) {
            // 工具数组的序列化抽成了静态方法 buildToolsArray：
            // 真实请求和 `Jhermes debug tools-json` 诊断命令共用它，保证两边字节完全一致
            requestBody.set("tools", buildToolsArray(objectMapper, tools));
        }

        requestBody.put("stream", stream);
        if (stream && includeUsage) {
            requestBody.set("stream_options",
                    objectMapper.createObjectNode().put("include_usage", true));
        }
        requestBody.put("temperature", config.getTemperature());
        requestBody.put(maxTokensParam, config.getMaxTokens());

        return objectMapper.writeValueAsString(requestBody);
    }

    /**
     * 解析一条 SSE 流（已剥离 HTTP 层的纯文本事件流），组装成完整响应。
     *
     * <p>抽成独立方法的好处：输入只是 {@link BufferedReader}，不依赖网络，
     * 可以用内存中的假 SSE 文本对其做单元测试。</p>
     *
     * <p>处理规则：</p>
     * <ul>
     *   <li>只关心以 {@code data:} 开头的行；空行（事件分隔符）跳过</li>
     *   <li>{@code data: [DONE]} 表示流结束</li>
     *   <li>每个 chunk 的 {@code choices[0].delta.content} 为文本增量：实时回调并累加</li>
     *   <li>{@code delta.tool_calls} 按 {@code index} 聚合：id/type/name 取首个非空片段，
     *       arguments 是跨多个 chunk 的字符串碎片，需按 index 拼接</li>
     *   <li>{@code finish_reason} 与 {@code usage} 通常出现在最后的 chunk（usage 可能挂在选择项外的根节点）</li>
     * </ul>
     *
     * @param reader    SSE 文本流（逐行）
     * @param onContent 文本增量回调；可为 null
     * @return 组装完成的 ChatCompletionResponse
     */
    ChatCompletionResponse assembleStream(BufferedReader reader, java.util.function.Consumer<String> onContent) throws IOException {
        StringBuilder contentBuf = new StringBuilder();
        // tool_calls 按 index 聚合（同一工具调用的 arguments 会分散在多个 chunk）
        Map<Integer, StreamToolCall> toolCallsByIndex = new TreeMap<>();
        String finishReason = null;
        Usage usage = null;
        String role = "assistant"; // delta 通常只在首 chunk 给 role，缺省按 assistant 处理

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue; // 事件分隔空行
            }
            if (!line.startsWith("data:")) {
                continue; // 忽略 event:/id:/retry: 等其他 SSE 字段
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                break;
            }

            JsonNode chunk;
            try {
                chunk = objectMapper.readTree(payload);
            } catch (JsonProcessingException e) {
                logger.warn("跳过无法解析的 SSE 数据块: {}", payload, e);
                continue;
            }

            // usage 常挂在根节点（最后一个 chunk）
            if (chunk.has("usage") && !chunk.get("usage").isNull()) {
                usage = parseUsage(chunk.get("usage"));
            }

            JsonNode choices = chunk.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                continue;
            }
            JsonNode choice = choices.get(0);

            if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                finishReason = choice.get("finish_reason").asText();
            }

            JsonNode delta = choice.get("delta");
            if (delta == null || delta.isNull()) {
                continue;
            }
            if (delta.has("role") && !delta.get("role").isNull()) {
                role = delta.get("role").asText();
            }

            // 文本增量
            if (delta.has("content") && !delta.get("content").isNull()) {
                String piece = delta.get("content").asText();
                if (!piece.isEmpty()) {
                    contentBuf.append(piece);
                    if (onContent != null) {
                        onContent.accept(piece);
                    }
                }
            }

            // 工具调用增量
            JsonNode deltaToolCalls = delta.get("tool_calls");
            if (deltaToolCalls != null && deltaToolCalls.isArray()) {
                for (JsonNode tcDelta : deltaToolCalls) {
                    int idx = tcDelta.has("index") ? tcDelta.get("index").asInt() : 0;
                    StreamToolCall acc = toolCallsByIndex.computeIfAbsent(idx, k -> new StreamToolCall());
                    if (tcDelta.has("id") && !tcDelta.get("id").isNull() && acc.id == null) {
                        acc.id = tcDelta.get("id").asText();
                    }
                    if (tcDelta.has("type") && !tcDelta.get("type").isNull() && acc.type == null) {
                        acc.type = tcDelta.get("type").asText();
                    }
                    JsonNode fn = tcDelta.get("function");
                    if (fn != null && !fn.isNull()) {
                        if (fn.has("name") && !fn.get("name").isNull()) {
                            // name 可能在首 chunk 给全，也可能分片，这里累加
                            acc.name.append(fn.get("name").asText());
                        }
                        if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                            // arguments 一定是跨 chunk 的碎片，持续拼接
                            acc.arguments.append(fn.get("arguments").asText());
                        }
                    }
                }
            }
        }

        // 组装成与非流式一致的结构
        ModelMessage message = new ModelMessage();
        message.setRole(role);
        message.setContent(contentBuf.toString());

        if (!toolCallsByIndex.isEmpty()) {
            List<ToolCall> toolCallList = new ArrayList<>();
            for (StreamToolCall acc : toolCallsByIndex.values()) {
                ToolCall tc = new ToolCall();
                tc.setId(acc.id);
                tc.setType(acc.type != null ? acc.type : "function");
                Function fc = new Function();
                fc.setName(acc.name.toString());
                fc.setArguments(acc.arguments.toString());
                tc.setFunction(fc);
                toolCallList.add(tc);
            }
            message.setToolCalls(toolCallList);
        }

        ChatCompletionResponse result = new ChatCompletionResponse();
        result.setMessage(message);
        result.setFinishReason(finishReason);
        result.setUsage(usage);
        return result;
    }

    /**
     * 流式 tool_call 的增量累加器。arguments / name 用 StringBuilder 承接跨 chunk 碎片。
     */
    private static final class StreamToolCall {
        String id;
        String type;
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }

    /**
     * 解析 usage 节点为 {@link Usage} 对象（流式与非流式共用）。
     */
    // 同样去掉 private，供单元测试直接喂各种 usage JSON 来验证缓存字段的兼容解析
    Usage parseUsage(JsonNode usageNode) {
        Usage usage = new Usage();
        if (usageNode.has("completion_tokens")) {
            usage.setCompletionTokens(usageNode.get("completion_tokens").asInt());
        }
        if (usageNode.has("prompt_tokens")) {
            usage.setPromptTokens(usageNode.get("prompt_tokens").asInt());
        }
        if (usageNode.has("total_tokens")) {
            usage.setTotalTokens(usageNode.get("total_tokens").asInt());
        }
        // 缓存命中 token 数（P0-4 R3）。不同供应商返回的字段名不一样，按下面的优先级依次尝试：
        //   1) OpenAI 风格：usage.prompt_tokens_details.cached_tokens（嵌套在 details 里）
        //   2) DeepSeek 等兼容层：usage.prompt_cache_hit_tokens（直接在 usage 下）
        // 两个都没有（供应商不支持缓存）就保持默认值 0，绝不能因为字段缺失而抛异常——
        // 用量统计只是辅助信息，不能影响正常对话。
        JsonNode details = usageNode.path("prompt_tokens_details"); // path() 不存在时返回"空节点"而非 null，不会空指针
        if (details.has("cached_tokens") && !details.get("cached_tokens").isNull()) {
            usage.setCachedTokens(details.get("cached_tokens").asInt());
        } else if (usageNode.has("prompt_cache_hit_tokens") && !usageNode.get("prompt_cache_hit_tokens").isNull()) {
            usage.setCachedTokens(usageNode.get("prompt_cache_hit_tokens").asInt());
        }
        return usage;
    }


    /**
     * 解析llm返回的数据
     */
    private ChatCompletionResponse parseCompletionResponse(JsonNode root) {
        ChatCompletionResponse result = new ChatCompletionResponse();

        //解析choices
        JsonNode choicesNode = root.get("choices");
        if (choicesNode != null && choicesNode.isArray() && !choicesNode.isEmpty()) {
            JsonNode firstChoices = choicesNode.get(0);
            JsonNode messageNode = firstChoices.get("message");
            if (messageNode != null) {
                ModelMessage modelMessage = new ModelMessage();
                //解析role
                if (messageNode.has("role")) {
                    String role = messageNode.get("role").asText();
                    modelMessage.setRole(role);
                }

                //解析content
                if (messageNode.has("content")) {
                    String content = messageNode.get("content").asText();
                    modelMessage.setContent(content);
                }

                //解析tool call
                if (messageNode.path("tool_calls").isArray()) {
                    ArrayNode toolCalls = (ArrayNode) messageNode.get("tool_calls");

                    if (!toolCalls.isEmpty()) {
                        List<ToolCall> toolCallList = new ArrayList<>();

                        //解析toolCalls 转成ToolCall对象
                        for (JsonNode toolCall : toolCalls) {
                            ToolCall tc = new ToolCall();
                            if (toolCall.has("id")) {
                                tc.setId(toolCall.get("id").asText());
                            }
                            if (toolCall.has("type")) {
                                tc.setType(toolCall.get("type").asText());
                            }

                            if (toolCall.has("function")) {
                                Function fc = new Function();
                                JsonNode function = toolCall.get("function");
                                if (function.has("name")) {
                                    fc.setName(function.get("name").asText());
                                }

                                if (function.has("arguments")) {
                                    fc.setArguments(function.get("arguments").asText());
                                }

                                tc.setFunction(fc);//拿到function对象
                            }
                            //ToolCall里面属性全部拿到，存入list里面
                            toolCallList.add(tc);
                        }
                        //将拿到的tool_calls放入modelMessage
                        modelMessage.setToolCalls(toolCallList);
                    }
                }

                result.setMessage(modelMessage);
            }

            if (firstChoices.has("finish_reason")) {
                result.setFinishReason(firstChoices.get("finish_reason").asText());
            }

        }

        //解析usage（复用 parseUsage，与流式路径共用同一套解析逻辑）
        if (root.has("usage") && !root.get("usage").isNull()) {
            result.setUsage(parseUsage(root.get("usage")));
        }


        return result;
    }

    /**
     * 把信息发送给模型，让模型进行信息提取
     *
     * @param prompt
     * @param maxToken
     * @param temperature
     * @return
     */
    public String callExtractionModel(String prompt, int maxToken, double temperature) {
        // 提取专用的短超时 client：newBuilder() 与主 client 共享连接池/线程池，仅改超时。
        // 主对话 readTimeout=300s 是为了长回复，但知识提取是退出前的收尾动作，
        // 绝不该让用户在 exit 后最多干等 5 分钟；60s 内拿不到结果就放弃（提取失败不影响主流程）。
        OkHttpClient extractionClient = httpClient.newBuilder()
                .callTimeout(60, TimeUnit.SECONDS)
                .build();
        ArrayNode messageArray = objectMapper.createArrayNode();
        ObjectNode messageObject = objectMapper.createObjectNode();
        messageObject.put("role", "user");
        messageObject.put("content", prompt);
        messageArray.add(messageObject);

        // 整个请求体在 lambda 里组装：换参数名重发时会重新执行一遍，才能带上新名字。
        // 参数名默认是 max_tokens（OpenAI 兼容接口规范），由 maxTokensParam 字段决定，
        // 遇到只认 max_completion_tokens 的模型会被自动切换（见 executeWithParamFallback）。
        // 旧实现曾误写为 max_token，服务端会静默忽略未知字段 → 限制完全不生效，
        // 模型可能长篇大论，白白拖慢会话退出时的提取耗时。
        RequestFactory factory = () -> {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", config.getCurrentModel());
            body.set("messages", messageArray);
            body.put(maxTokensParam, maxToken);
            body.put("temperature", temperature);
            return new Request.Builder()
                    .url(config.getBaseUrl() + "/chat/completions")
                    .method("POST", RequestBody.create(objectMapper.writeValueAsString(body), JSON_MEDIA_TYPE))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                    .build();
        };

        //发送请求（用短超时 client；max_tokens 被拒时同样自动换名重发一次）
        try (Response response = executeWithParamFallback(extractionClient, factory, "构造知识提取请求体失败")) {//获得模型返回的数据
            if (!response.isSuccessful()) {
                throw LlmTransportException.http("知识提取请求失败", response.code(), readErrorBody(response));
            }

            String responseBody = response.body().string();
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choiceNode = root.get("choices");

            if (choiceNode != null && choiceNode.isArray()) {
                JsonNode firstChoice = choiceNode.get(0);
                JsonNode message = firstChoice.get("message");
                return message.get("content").asText();
            }

        } catch (IOException e) {
            logger.error("Extraction model call failed: {}", e.getMessage(), e);
        }

        return "";
    }
}
