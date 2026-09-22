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

public class ModelClient {
    private static final Logger logger = LoggerFactory.getLogger(ModelClient.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final HermesConfig config;
    private final OkHttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();//解析json数据

    public ModelClient(HermesConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /** 错误响应体最大读取长度，防止超大 body 冲刷控制台/日志。 */
    private static final int MAX_ERROR_BODY = 500;

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
     * 构造对人友好的 HTTP 错误信息：状态码 + 服务端响应体 + 针对常见状态码的自查指引。
     *
     * <p>401/403 几乎都是 api_key 没配或配错——直接在消息里告诉用户去哪修，
     * 而不是让用户对着一个状态码猜。</p>
     */
    private static String buildHttpErrorMessage(String prefix, int code, String body) {
        StringBuilder sb = new StringBuilder(prefix).append(": HTTP ").append(code);
        if (body != null && !body.isBlank()) {
            sb.append("\n服务端返回: ").append(body);
        }
        switch (code) {
            case 401, 403 -> sb.append("\n排查: API Key 无效或未配置。请编辑 ")
                    .append(HermesConfig.getConfigPath())
                    .append("\n在 model.api_key 填入有效的密钥后重试（无需改代码，改完即生效）。");
            case 404 -> sb.append("\n排查: base_url 或模型名不对。请检查配置中 model.base_url 与 model.model 是否匹配所用服务商。");
            case 429 -> sb.append("\n排查: 触发限流或余额不足，稍后重试或检查服务商账户。");
            default -> { /* 其他状态码不追加指引 */ }
        }
        return sb.toString();
    }

    /**
     * 向LLm发送请求获取响应（非流式，整段返回）
     */
    public ChatCompletionResponse chatCompletion(List<ModelMessage> messages, List<Map<String, Object>> tools, boolean stream) {
        try {
            String json = buildRequestBody(messages, tools, false, false);
            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);

            //构建请求
            Request requestBuilder = new Request.Builder()
                    .url(config.getBaseUrl() + "/chat/completions")
                    .method("POST", body)//将请求体放入请求
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                    .build();

            //发送请求
            try (Response response = httpClient.newCall(requestBuilder).execute()) {
                if (!response.isSuccessful()) {
                    // body 必须用 .string() 读取：直接拼 ResponseBody 只会得到 RealResponseBody@160c3ec1 对象引用
                    throw new RuntimeException(buildHttpErrorMessage("请求失败", response.code(), readErrorBody(response)));
                }

                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);
                return parseCompletionResponse(root);

            } catch (IOException e) {
                throw new RuntimeException("Chat completion request failed", e);
            }

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize chat completion request", e);
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
    public ChatCompletionResponse chatCompletionStream(List<ModelMessage> messages,
                                                       List<Map<String, Object>> tools,
                                                       java.util.function.Consumer<String> onContent) {
        try {
            // stream_options.include_usage=true 让服务端在最后一个 chunk 回传 token 用量
            String json = buildRequestBody(messages, tools, true, true);
            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);

            Request requestBuilder = new Request.Builder()
                    .url(config.getBaseUrl() + "/chat/completions")
                    .method("POST", body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                    .build();

            try (Response response = httpClient.newCall(requestBuilder).execute()) {
                if (!response.isSuccessful()) {
                    // 失败时 body 可能是普通 JSON 错误而非 SSE，直接读出报错
                    throw new RuntimeException(buildHttpErrorMessage("流式请求失败", response.code(), readErrorBody(response)));
                }
                if (response.body() == null) {
                    throw new RuntimeException("流式响应体为空");
                }
                // SSE 是 UTF-8 文本流；用 BufferedReader 按行读取事件
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                    return assembleStream(reader, onContent);
                }
            } catch (IOException e) {
                throw new RuntimeException("Stream chat completion request failed", e);
            }

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize stream request", e);
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
    private String buildRequestBody(List<ModelMessage> messages, List<Map<String, Object>> tools,
                                    boolean stream, boolean includeUsage) throws JsonProcessingException {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", config.getCurrentModel());

        ArrayNode messagesArray = objectMapper.createArrayNode();
        for (ModelMessage message : messages) {
            messagesArray.add(message.toJsonNode());
        }
        requestBody.set("messages", messagesArray);

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = objectMapper.createArrayNode();
            for (Map<String, Object> tool : tools) {
                toolsArray.add(objectMapper.valueToTree(tool));
            }
            requestBody.set("tools", toolsArray);
        }

        requestBody.put("stream", stream);
        if (stream && includeUsage) {
            requestBody.set("stream_options",
                    objectMapper.createObjectNode().put("include_usage", true));
        }
        requestBody.put("temperature", config.getTemperature());
        requestBody.put("max_tokens", config.getMaxTokens());

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
    private Usage parseUsage(JsonNode usageNode) {
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
                if (messageNode.has("tool_calls")) {
                    ArrayNode toolCalls = (ArrayNode) messageNode.get("tool_calls");

                    if (toolCalls != null && !toolCalls.isEmpty()) {
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
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", config.getCurrentModel());
            ArrayNode messageArray = objectMapper.createArrayNode();
            ObjectNode messageObject = objectMapper.createObjectNode();

            messageObject.put("role", "user");
            messageObject.put("content", prompt);
            messageArray.add(messageObject);

            requestBody.set("messages", messageArray);
            requestBody.put("max_token", maxToken);
            requestBody.put("temperature", temperature);

            //将json数据放入请求体里面
            String json = objectMapper.writeValueAsString(requestBody);
            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);

            //构建请求
            Request requestBuilder = new Request.Builder()
                    .url(config.getBaseUrl() + "/chat/completions")
                    .method("POST", body)//将请求体放入请求
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + config.getApiKey())
                    .build();

            //发送请求
            try (Response response = httpClient.newCall(requestBuilder).execute()) {//获得模型返回的数据
                if (!response.isSuccessful()) {
                    throw new RuntimeException(buildHttpErrorMessage("请求失败", response.code(), readErrorBody(response)));
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

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize extraction request", e);
        }

        return "";
    }
}
