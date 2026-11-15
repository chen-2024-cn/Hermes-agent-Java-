package com.cyk.http;

import com.cyk.bean.ChatCompletionResponse;
import com.cyk.bean.ModelMessage;
import com.cyk.config.HermesConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "max_tokens / max_completion_tokens 参数名自动适配"测试（缺陷 N-2）。
 *
 * <h2>背景</h2>
 * <p>OpenAI 兼容接口里，限制模型最多输出多少 token 的参数，传统叫 {@code max_tokens}。
 * 但有些较新的模型（例如 Azure 上的 gpt 系列）拒绝这个名字，返回 HTTP 400：
 * {@code 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.}
 * 原来的代码把参数名写死成 {@code max_tokens}，于是这类模型完全用不了。</p>
 *
 * <h2>修复思路（做法 B）</h2>
 * <ol>
 *   <li>照旧先发 {@code max_tokens}（默认行为一个字节都不变）；</li>
 *   <li>只有收到 400，且响应体里<b>同时</b>出现 {@code max_tokens} 和 {@code max_completion_tokens}
 *       两个词时，才认定是"参数名不对"；</li>
 *   <li>此时换成 {@code max_completion_tokens} 重发这一个请求，并在本进程内记住这个结论，
 *       后续请求直接用对的名字，不再多浪费一次失败请求；</li>
 *   <li>换了之后还是被拒，就如实报错，绝不循环。</li>
 * </ol>
 *
 * <h2>测试方法</h2>
 * <p>项目没有 Mockito 和 MockWebServer，这里用 JDK 自带的
 * {@link HttpServer} 在本机随机端口起一个假的模型服务：它把每次收到的请求体记下来，
 * 并按事先设定的"剧本"返回 400 或 200。全程不联网、不消耗 token。</p>
 */
class ModelClientMaxTokensParamTest {

    /** 真实环境里取证到的 400 报错原文（节选自 AC4-REPORT）。 */
    private static final String REAL_AZURE_400 =
            "litellm.BadRequestError: AzureException BadRequestError - Unsupported parameter: "
                    + "'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.";

    private static final String OK_BODY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    /** 假服务器收到的所有请求体（按到达顺序）。 */
    private final List<String> received = new CopyOnWriteArrayList<>();
    /** 假服务器的"剧本"：第 N 次请求返回 scripts[N]；超出则重复最后一条。每项 = {状态码, 响应体}。 */
    private volatile List<Object[]> script = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int index = received.size();
            received.add(requestBody);
            Object[] step = script.get(Math.min(index, script.size() - 1));
            byte[] out = ((String) step[1]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders((Integer) step[0], out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ModelClient newClient() {
        Map<String, Object> model = new HashMap<>();
        model.put("model", "test-model");
        model.put("base_url", "http://127.0.0.1:" + server.getAddress().getPort());
        model.put("api_key", "sk-test");
        Map<String, Object> agent = new HashMap<>();
        agent.put("max_tokens", 1234);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("model", model);
        cfg.put("agent", agent);
        return new ModelClient(new HermesConfig(null, cfg));
    }

    private static List<ModelMessage> oneMessage() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(ModelMessage.user("你好"));
        return messages;
    }

    private void respondWith(Object[]... steps) {
        script = List.of(steps);
    }

    private static Object[] status(int code, String body) {
        return new Object[]{code, body};
    }

    private JsonNode requestAt(int index) throws IOException {
        return MAPPER.readTree(received.get(index));
    }

    // =========================================================================
    // 判断方法：什么样的 400 才算"参数名不对"
    // =========================================================================

    @Test
    void shouldRecognizeRealAzureRejection() {
        assertThat(ModelClient.isMaxTokensParamRejection(400, REAL_AZURE_400)).isTrue();
    }

    @Test
    void shouldNotTreatOtherBadRequestsAsParamRejection() {
        // 普通 400：请求体 JSON 有问题，换参数名没用
        assertThat(ModelClient.isMaxTokensParamRejection(400, "{\"error\":\"invalid json\"}")).isFalse();
        // 只提到 max_tokens（比如"max_tokens 超过上限"），没有指向 max_completion_tokens：不是改名问题
        assertThat(ModelClient.isMaxTokensParamRejection(400, "max_tokens must be <= 4096")).isFalse();
        // 空响应体、null
        assertThat(ModelClient.isMaxTokensParamRejection(400, "")).isFalse();
        assertThat(ModelClient.isMaxTokensParamRejection(400, null)).isFalse();
    }

    @Test
    void shouldOnlyTriggerOnHttp400() {
        // 同样的文字出现在别的状态码里，不触发：401/403 是凭据问题，5xx 是服务端故障
        assertThat(ModelClient.isMaxTokensParamRejection(401, REAL_AZURE_400)).isFalse();
        assertThat(ModelClient.isMaxTokensParamRejection(500, REAL_AZURE_400)).isFalse();
        assertThat(ModelClient.isMaxTokensParamRejection(429, REAL_AZURE_400)).isFalse();
    }

    // =========================================================================
    // 请求体：缺省行为不变
    // =========================================================================

    @Test
    void defaultRequestBodyShouldUseMaxTokens() throws Exception {
        String body = newClient().buildRequestBody(oneMessage(), null, false, false);
        JsonNode json = MAPPER.readTree(body);

        assertThat(json.has("max_tokens")).isTrue();
        assertThat(json.get("max_tokens").asInt()).isEqualTo(1234);
        assertThat(json.has("max_completion_tokens")).isFalse();
    }

    // =========================================================================
    // 自动切换：非流式主对话
    // =========================================================================

    @Test
    void shouldSwitchParamNameAndRetryOnceWhenServerRejectsMaxTokens() throws Exception {
        respondWith(status(400, REAL_AZURE_400), status(200, OK_BODY));
        ModelClient client = newClient();

        ChatCompletionResponse resp = client.chatCompletion(oneMessage(), null, false);

        assertThat(resp.getMessage().getContent()).isEqualTo("ok");
        assertThat(received).hasSize(2);
        // 第一次：照旧发 max_tokens
        assertThat(requestAt(0).has("max_tokens")).isTrue();
        // 第二次：换成 max_completion_tokens，数值不变，且不再带 max_tokens
        JsonNode second = requestAt(1);
        assertThat(second.has("max_completion_tokens")).isTrue();
        assertThat(second.get("max_completion_tokens").asInt()).isEqualTo(1234);
        assertThat(second.has("max_tokens")).isFalse();
    }

    @Test
    void shouldRememberSwitchSoLaterRequestsDoNotWasteAFailedAttempt() throws Exception {
        respondWith(status(400, REAL_AZURE_400), status(200, OK_BODY));
        ModelClient client = newClient();

        client.chatCompletion(oneMessage(), null, false);   // 第 1、2 次请求：失败 + 重发成功
        client.chatCompletion(oneMessage(), null, false);   // 第 3 次请求：应直接用对的名字

        assertThat(received).hasSize(3);
        JsonNode third = requestAt(2);
        assertThat(third.has("max_completion_tokens")).isTrue();
        assertThat(third.has("max_tokens")).isFalse();
    }

    @Test
    void shouldNotLoopWhenServerRejectsBothNames() {
        // 两个名字都被拒：只允许"换一次"，第二次的 400 照常作为错误抛出
        respondWith(status(400, REAL_AZURE_400));
        ModelClient client = newClient();

        assertThatThrownBy(() -> client.chatCompletion(oneMessage(), null, false))
                .isInstanceOf(LlmTransportException.class)
                .satisfies(e -> assertThat(((LlmTransportException) e).statusCode()).isEqualTo(400));
        // 恰好两次请求：原名 + 换名各一次，没有无限循环
        assertThat(received).hasSize(2);
    }

    @Test
    void shouldNotSwitchOnUnrelatedBadRequest() {
        respondWith(status(400, "{\"error\":\"messages is required\"}"));
        ModelClient client = newClient();

        assertThatThrownBy(() -> client.chatCompletion(oneMessage(), null, false))
                .isInstanceOf(LlmTransportException.class);
        // 只发了一次：普通 400 不触发换名重发，错误原样上抛
        assertThat(received).hasSize(1);
    }

    @Test
    void shouldKeepErrorClassificationForNon400() {
        // 500 仍是"可重试"错误，由 LlmRetryPolicy 处理；本机制不插手
        respondWith(status(500, "server busy"));
        ModelClient client = newClient();

        assertThatThrownBy(() -> client.chatCompletion(oneMessage(), null, false))
                .isInstanceOf(LlmTransportException.class)
                .satisfies(e -> assertThat(((LlmTransportException) e).isRetryable()).isTrue());
        assertThat(received).hasSize(1);
    }

    // =========================================================================
    // 自动切换：流式主对话
    // =========================================================================

    @Test
    void streamCallShouldAlsoSwitchParamName() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n";
        respondWith(status(400, REAL_AZURE_400), status(200, sse));
        ModelClient client = newClient();

        List<String> pieces = new ArrayList<>();
        ChatCompletionResponse resp = client.chatCompletionStream(oneMessage(), null, pieces::add);

        assertThat(resp.getMessage().getContent()).isEqualTo("hi");
        assertThat(received).hasSize(2);
        assertThat(requestAt(0).has("max_tokens")).isTrue();
        assertThat(requestAt(1).has("max_completion_tokens")).isTrue();
        assertThat(requestAt(1).has("max_tokens")).isFalse();
        // 流式请求的其他字段不受影响
        assertThat(requestAt(1).get("stream").asBoolean()).isTrue();
    }

    // =========================================================================
    // 自动切换：知识提取（最容易漏的一处）
    // =========================================================================

    @Test
    void extractionCallShouldAlsoSwitchParamName() throws Exception {
        respondWith(status(400, REAL_AZURE_400), status(200, OK_BODY));
        ModelClient client = newClient();

        String text = client.callExtractionModel("请总结", 2000, 0.3);

        assertThat(text).isEqualTo("ok");
        assertThat(received).hasSize(2);
        assertThat(requestAt(0).get("max_tokens").asInt()).isEqualTo(2000);
        // 提取调用有自己的数值（2000），换名后必须沿用它，而不是配置里的 1234
        assertThat(requestAt(1).get("max_completion_tokens").asInt()).isEqualTo(2000);
        assertThat(requestAt(1).has("max_tokens")).isFalse();
    }

    @Test
    void switchLearnedByChatShouldApplyToExtractionToo() throws Exception {
        // 同一个 ModelClient：主对话先学到"要用 max_completion_tokens"，之后提取调用直接用对的名字
        respondWith(status(400, REAL_AZURE_400), status(200, OK_BODY));
        ModelClient client = newClient();

        client.chatCompletion(oneMessage(), null, false);   // 第 1、2 次
        client.callExtractionModel("请总结", 2000, 0.3);     // 第 3 次

        assertThat(received).hasSize(3);
        assertThat(requestAt(2).has("max_completion_tokens")).isTrue();
        assertThat(requestAt(2).has("max_tokens")).isFalse();
    }
}
