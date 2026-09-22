package com.cyk.http;

import com.cyk.bean.ChatCompletionResponse;
import com.cyk.bean.ModelMessage;
import com.cyk.bean.ToolCall;
import com.cyk.config.HermesConfig;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * ModelClient 的 SSE 流式解析（assembleStream）单元测试。
 *
 * <p>覆盖本轮新增的流式能力：content 增量拼接与实时回调、tool_calls 跨 chunk
 * 的 arguments 碎片重组、finish_reason/usage 解析、[DONE] 与非 data 行的忽略。
 * assembleStream 只接受 BufferedReader，因此可用内存中的假 SSE 文本测试，无需真实网络。</p>
 */
class ModelClientStreamTest {

    private static BufferedReader readerOf(String sse) {
        return new BufferedReader(new StringReader(sse));
    }

    private static ModelClient newClient() {
        // assembleStream 不使用 config 字段，这里给最小可用配置即可构建 ModelClient
        Map<String, Object> model = new HashMap<>();
        model.put("model", "test-model");
        model.put("base_url", "https://example.com");
        model.put("api_key", "sk-test");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("model", model);
        return new ModelClient(new HermesConfig(null, cfg));
    }

    @Test
    void shouldAssembleContentFromDeltasAndInvokeCallback() throws IOException {
        String sse = """
                data: {"choices":[{"delta":{"role":"assistant","content":"你"}}]}

                data: {"choices":[{"delta":{"content":"好"}}]}

                data: {"choices":[{"delta":{"content":"，世界"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}

                data: [DONE]
                """;

        List<String> callbacks = new ArrayList<>();
        ChatCompletionResponse resp = newClient().assembleStream(readerOf(sse), callbacks::add);

        ModelMessage msg = resp.getMessage();
        assertThat(msg.getRole()).isEqualTo("assistant");
        assertThat(msg.getContent()).isEqualTo("你好，世界");
        // 回调按顺序收到每个文本增量片段
        assertThat(callbacks).containsExactly("你", "好", "，世界");
        assertThat(msg.getToolCalls()).isNull();
        assertThat(resp.getFinishReason()).isEqualTo("stop");

        assertThat(resp.getUsage()).isNotNull();
        assertThat(resp.getUsage().getPromptTokens()).isEqualTo(10);
        assertThat(resp.getUsage().getCompletionTokens()).isEqualTo(5);
        assertThat(resp.getUsage().getTotalTokens()).isEqualTo(15);
        assertThat(resp.hasToolCalls()).isFalse();
    }

    @Test
    void shouldReassembleToolCallArgumentsSplitAcrossChunks() throws IOException {
        // id/name 在首 chunk，arguments 被切成多片下发（真实 OpenAI 流式行为）
        String sse = """
                data: {"choices":[{"delta":{"role":"assistant","content":""}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"path\\""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\\"a.txt\\"}"}}]}}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """;

        ChatCompletionResponse resp = newClient().assembleStream(readerOf(sse), null);

        assertThat(resp.hasToolCalls()).isTrue();
        List<ToolCall> calls = resp.getMessage().getToolCalls();
        assertThat(calls).hasSize(1);
        ToolCall tc = calls.get(0);
        assertThat(tc.getId()).isEqualTo("call_1");
        assertThat(tc.getType()).isEqualTo("function");
        assertThat(tc.getFunction().getName()).isEqualTo("read_file");
        // arguments 碎片应按 index 拼成合法 JSON
        assertThat(tc.getFunction().getArguments()).isEqualTo("{\"path\":\"a.txt\"}");
        assertThat(resp.getFinishReason()).isEqualTo("tool_calls");
    }

    @Test
    void shouldAggregateMultipleParallelToolCallsByIndex() throws IOException {
        String sse = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c0","function":{"name":"f0","arguments":"{}"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"c1","function":{"name":"f1","arguments":"{\\"k\\":"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"1}"}}]}}]}

                data: [DONE]
                """;

        ChatCompletionResponse resp = newClient().assembleStream(readerOf(sse), null);
        List<ToolCall> calls = resp.getMessage().getToolCalls();

        // 两个并行工具调用应按 index 分别聚合，且保持 index 升序
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).getId()).isEqualTo("c0");
        assertThat(calls.get(0).getFunction().getName()).isEqualTo("f0");
        assertThat(calls.get(0).getFunction().getArguments()).isEqualTo("{}");
        assertThat(calls.get(1).getId()).isEqualTo("c1");
        assertThat(calls.get(1).getFunction().getArguments()).isEqualTo("{\"k\":1}");
    }

    @Test
    void shouldIgnoreNonDataLinesAndComments() throws IOException {
        String sse = """
                event: message
                : this-is-a-comment
                data: {"choices":[{"delta":{"content":"ok"}}]}

                data: [DONE]
                """;

        List<String> callbacks = new ArrayList<>();
        ChatCompletionResponse resp = newClient().assembleStream(readerOf(sse), callbacks::add);

        assertThat(resp.getMessage().getContent()).isEqualTo("ok");
        assertThat(callbacks).containsExactly("ok");
    }

    @Test
    void shouldStopAtDoneAndIgnoreTrailingData() throws IOException {
        String sse = """
                data: {"choices":[{"delta":{"content":"before"}}]}

                data: [DONE]

                data: {"choices":[{"delta":{"content":"SHOULD_NOT_APPEAR"}}]}
                """;

        ChatCompletionResponse resp = newClient().assembleStream(readerOf(sse), null);

        assertThat(resp.getMessage().getContent()).isEqualTo("before");
    }

    @Test
    void shouldSkipMalformedDataChunkWithoutCrashing() throws IOException {
        String sse = """
                data: {"choices":[{"delta":{"content":"good"}}]}

                data: {this is not valid json}

                data: {"choices":[{"delta":{"content":"tail"}}]}

                data: [DONE]
                """;

        ChatCompletionResponse resp = newClient().assembleStream(readerOf(sse), null);

        // 坏块被跳过，好块正常拼接
        assertThat(resp.getMessage().getContent()).isEqualTo("goodtail");
    }

    @Test
    void shouldDefaultRoleToAssistantWhenDeltaOmitsIt() throws IOException {
        String sse = """
                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: [DONE]
                """;

        ChatCompletionResponse resp = newClient().assembleStream(readerOf(sse), null);

        assertThat(resp.getMessage().getRole()).isEqualTo("assistant");
        assertThat(resp.getMessage().getContent()).isEqualTo("hi");
    }

    @Test
    void shouldHandleEmptyStream() throws IOException {
        ChatCompletionResponse resp = newClient().assembleStream(readerOf("data: [DONE]\n"), null);

        assertThat(resp.getMessage()).isNotNull();
        assertThat(resp.getMessage().getContent()).isEmpty();
        assertThat(resp.hasToolCalls()).isFalse();
    }
}
