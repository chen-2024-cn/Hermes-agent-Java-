package com.cyk.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <h1>LLM 重试策略与错误分类单测（JH-SPEC-001 P0-5 AC1）</h1>
 *
 * <p>验收要点：429 等可重试错误经指数退避后成功；401/403/404 等凭据与配置错误
 * <b>一次都不重试</b>（重试无意义且可能触发服务商风控）；重试耗尽后异常原样上抛，
 * 交由 {@code Agent} 的回合级边界展示可读错误并保持 REPL 存活。</p>
 *
 * <p>无 Mockito：按 CC-5 用接口注入 + 手写桩（假 {@link LlmClient}、假 Sleeper、
 * 记录型 RetryNotifier）。</p>
 */
class LlmRetryPolicyTest {

    /** 记录每次注入的 Sleeper 收到的退避时长，避免单测真实等待 3.5 秒。 */
    private static final class RecordingSleeper implements LlmRetryPolicy.Sleeper {
        final List<Long> backoffs = new ArrayList<>();

        @Override
        public void sleep(long millis) throws InterruptedException {
            backoffs.add(millis);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("interrupted while backing off");
            }
        }
    }

    /** 记录 UI 通知次数与参数。 */
    private static final class RecordingNotifier implements LlmRetryPolicy.RetryNotifier {
        final List<String> events = new ArrayList<>();

        @Override
        public void onRetry(int attempt, int maxRetries, long backoffMillis, LlmTransportException error) {
            events.add(attempt + "/" + maxRetries + "@" + backoffMillis + "ms:http" + error.statusCode());
        }
    }

    /**
     * 桩 LlmClient：前 {@code failures} 次抛指定异常，之后返回固定响应。
     * 用于验证「重试后成功」而不发起任何网络请求。
     */
    private static final class StubLlmClient implements LlmClient {
        private final LlmTransportException toThrow;
        private final int failures;
        final AtomicInteger calls = new AtomicInteger();

        StubLlmClient(LlmTransportException toThrow, int failures) {
            this.toThrow = toThrow;
            this.failures = failures;
        }

        @Override
        public com.cyk.bean.ChatCompletionResponse chatCompletion(
                List<com.cyk.bean.ModelMessage> messages,
                List<java.util.Map<String, Object>> tools, boolean stream) {
            int n = calls.incrementAndGet();
            if (n <= failures) {
                throw toThrow;
            }
            var resp = new com.cyk.bean.ChatCompletionResponse();
            resp.setMessage(com.cyk.bean.ModelMessage.assistant("ok#" + n));
            return resp;
        }

        @Override
        public com.cyk.bean.ChatCompletionResponse chatCompletionStream(
                List<com.cyk.bean.ModelMessage> messages,
                List<java.util.Map<String, Object>> tools,
                java.util.function.Consumer<String> onContent) {
            return chatCompletion(messages, tools, false);
        }
    }

    private static LlmRetryPolicy policy(RecordingNotifier notifier, RecordingSleeper sleeper) {
        return new LlmRetryPolicy(notifier, sleeper);
    }

    @Test
    @DisplayName("首次成功：不重试、不通知、不休眠")
    void firstCallSuccessDoesNotRetry() {
        RecordingNotifier notifier = new RecordingNotifier();
        RecordingSleeper sleeper = new RecordingSleeper();
        StubLlmClient client = new StubLlmClient(null, 0);

        String result = policy(notifier, sleeper).call(() ->
                client.chatCompletion(List.of(), null, false).getMessage().getContent());

        assertThat(result).isEqualTo("ok#1");
        assertThat(client.calls).hasValue(1);
        assertThat(notifier.events).isEmpty();
        assertThat(sleeper.backoffs).isEmpty();
    }

    @Test
    @DisplayName("429 限流后第 2 次成功：重试 1 次，退避 500ms，通知 1 次（AC1）")
    void retryAfterRateLimitThenSucceeds() {
        RecordingNotifier notifier = new RecordingNotifier();
        RecordingSleeper sleeper = new RecordingSleeper();
        StubLlmClient client = new StubLlmClient(
                LlmTransportException.http("请求失败", 429, "{\"error\":\"rate_limit\"}"), 1);

        String result = policy(notifier, sleeper).call(() ->
                client.chatCompletion(List.of(), null, false).getMessage().getContent());

        assertThat(result).isEqualTo("ok#2");
        assertThat(client.calls).hasValue(2);
        assertThat(sleeper.backoffs).containsExactly(500L);
        assertThat(notifier.events).containsExactly("1/3@500ms:http429");
    }

    @Test
    @DisplayName("持续 503：重试 3 次后原异常上抛，退避曲线 500/1000/2000")
    void retryExhaustedPropagatesLastException() {
        RecordingNotifier notifier = new RecordingNotifier();
        RecordingSleeper sleeper = new RecordingSleeper();
        StubLlmClient client = new StubLlmClient(
                LlmTransportException.http("流式请求失败", 503, "unavailable"), 99);

        assertThatThrownBy(() -> policy(notifier, sleeper).call(() ->
                client.chatCompletion(List.of(), null, false)))
                .isInstanceOf(LlmTransportException.class)
                .hasMessageContaining("HTTP 503");

        assertThat(client.calls).hasValue(LlmRetryPolicy.MAX_RETRIES + 1);
        assertThat(sleeper.backoffs).containsExactly(500L, 1000L, 2000L);
        assertThat(notifier.events).hasSize(3);
    }

    @ParameterizedTest(name = "HTTP {0} 不可重试：一次都不重试")
    @ValueSource(ints = {400, 401, 403, 404, 405})
    @DisplayName("凭据与配置错误不重试（AC1：401 不重试）")
    void nonRetryableStatusCodesFailFast(int code) {
        RecordingNotifier notifier = new RecordingNotifier();
        RecordingSleeper sleeper = new RecordingSleeper();
        StubLlmClient client = new StubLlmClient(
                LlmTransportException.http("请求失败", code, "denied"), 99);

        assertThatThrownBy(() -> policy(notifier, sleeper).call(() ->
                client.chatCompletion(List.of(), null, false)))
                .isInstanceOf(LlmTransportException.class);

        assertThat(client.calls).as("HTTP %d 应只尝试 1 次", code).hasValue(1);
        assertThat(sleeper.backoffs).isEmpty();
        assertThat(notifier.events).isEmpty();
    }

    @ParameterizedTest(name = "HTTP {0} 可重试")
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void retryableStatusCodesAreRetried(int code) {
        RecordingSleeper sleeper = new RecordingSleeper();
        StubLlmClient client = new StubLlmClient(
                LlmTransportException.http("请求失败", code, "transient"), 1);

        policy(new RecordingNotifier(), sleeper).call(() ->
                client.chatCompletion(List.of(), null, false));

        assertThat(client.calls).hasValue(2);
        assertThat(sleeper.backoffs).containsExactly(500L);
    }

    @Test
    @DisplayName("网络异常（连接重置/超时）可重试")
    void networkErrorsAreRetryable() {
        LlmTransportException netErr = LlmTransportException.network("模型请求失败",
                new java.net.SocketTimeoutException("Read timed out"));
        assertThat(netErr.isRetryable()).isTrue();
        assertThat(netErr.statusCode()).isEqualTo(LlmTransportException.NO_STATUS_CODE);

        RecordingSleeper sleeper = new RecordingSleeper();
        StubLlmClient client = new StubLlmClient(netErr, 1);

        policy(new RecordingNotifier(), sleeper).call(() ->
                client.chatCompletion(List.of(), null, false));

        assertThat(client.calls).hasValue(2);
    }

    @Test
    @DisplayName("请求体序列化失败不可重试（本地数据问题，重发必然再失败）")
    void serializationErrorsAreNotRetryable() {
        LlmTransportException err = LlmTransportException.serialization("构造对话请求体失败",
                new com.fasterxml.jackson.core.JsonProcessingException("bad char") {});

        assertThat(err.isRetryable()).isFalse();
        assertThat(err.statusCode()).isEqualTo(LlmTransportException.NO_STATUS_CODE);

        RecordingSleeper sleeper = new RecordingSleeper();
        StubLlmClient client = new StubLlmClient(err, 99);

        assertThatThrownBy(() -> policy(new RecordingNotifier(), sleeper).call(() ->
                client.chatCompletion(List.of(), null, false)))
                .isSameAs(err);
        assertThat(client.calls).hasValue(1);
        assertThat(sleeper.backoffs).isEmpty();
    }

    @Test
    @DisplayName("退避期间被中断：恢复中断标记并放弃重试")
    void interruptionDuringBackoffAbortsRetry() {
        LlmTransportException err = LlmTransportException.http("请求失败", 429, "limit");
        RecordingSleeper sleeper = new RecordingSleeper();
        StubLlmClient client = new StubLlmClient(err, 99);

        // 先置中断标记：RecordingSleeper 会在记录后抛 InterruptedException 模拟被 Ctrl+C 打断
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> policy(new RecordingNotifier(), sleeper).call(() ->
                    client.chatCompletion(List.of(), null, false)))
                    .isSameAs(err);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("中断标记必须恢复，否则后续 IO 行为异常")
                    .isTrue();
            assertThat(client.calls).hasValue(1);
        } finally {
            // 清理中断状态，避免污染后续测试
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("退避曲线指数增长且封顶")
    void backoffGrowsExponentiallyAndIsCapped() {
        assertThat(LlmRetryPolicy.backoffMillis(1)).isEqualTo(500L);
        assertThat(LlmRetryPolicy.backoffMillis(2)).isEqualTo(1000L);
        assertThat(LlmRetryPolicy.backoffMillis(3)).isEqualTo(2000L);
        assertThat(LlmRetryPolicy.backoffMillis(4)).isEqualTo(4000L);
        assertThat(LlmRetryPolicy.backoffMillis(10))
                .as("大重试次数下退避必须封顶，不得无限增长")
                .isLessThanOrEqualTo(LlmRetryPolicy.MAX_BACKOFF_MILLIS);
    }

    @Test
    @DisplayName("401 消息含配置文件路径排查指引（沿用 buildHttpErrorMessage 语义）")
    void unauthorizedMessageCarriesConfigGuidance() {
        LlmTransportException err = LlmTransportException.http("请求失败", 401,
                "{\"error\":\"Invalid Authentication\"}");

        assertThat(err.getMessage())
                .contains("HTTP 401")
                .contains("Invalid Authentication")
                .contains("model.api_key")
                .contains(com.cyk.config.HermesConfig.getConfigPath().toString());
    }

    @Test
    @DisplayName("405 消息指出 base_url 误填完整端点这一高频原因")
    void methodNotAllowedMessageHintsBaseUrlMistake() {
        assertThat(LlmTransportException.http("请求失败", 405, "").getMessage())
                .contains("/chat/completions");
    }
}
