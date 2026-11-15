package com.cyk.agent;

import com.cyk.agent.AgentTestSupport.ScriptedLlm;
import com.cyk.bean.ModelMessage;
import com.cyk.config.HermesConfig;
import com.cyk.security.SecurityContext;
import com.cyk.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>历史压缩在 Agent 里的接线验证（JH-SPEC-001 P0-4 R7 / AC5）</h1>
 *
 * <p>{@code HistoryCompressorTest} 验证压缩算法本身；本测试验证 Agent 真的用了它：</p>
 * <ul>
 *   <li>发给模型的请求里，旧工具结果确实被折叠了；</li>
 *   <li>真实历史（也就是会被持久化的那份）完好无损；</li>
 *   <li>配置开关能完全关闭压缩；</li>
 *   <li>{@code /context} 能说明压缩状态。</li>
 * </ul>
 *
 * <p>做法：用桩 LLM 的 {@code capturedHistories}（记录每次请求收到的消息）检查"实际发出的内容"。
 * 窗口配置成很小的 1000 token，几条大工具结果就能触发压缩，不必构造巨型历史。</p>
 */
class HistoryCompressionWiringTest {

    private static final int BIG = 1000;

    @TempDir
    Path dataDir;

    @AfterEach
    void tearDown() {
        ToolRegistry.clearForTest();
        SecurityContext.resetForTest();
    }

    /** 在最小测试配置上加上 context 段。 */
    private static HermesConfig config(boolean compressionEnabled, int windowTokens) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("model", Map.of(
                "model", "test-model",
                "base_url", "http://127.0.0.1:1/v1",
                "api_key", "sk-test-not-used"));
        cfg.put("agent", Map.of("max_turns", 5, "stream", false));
        cfg.put("tools", Map.of("terminal_enabled", false));
        cfg.put("rag", Map.of("enabled", false));
        cfg.put("extract", Map.of("enabled", false));
        cfg.put("context", Map.of("compression_enabled", compressionEnabled, "window_tokens", windowTokens));
        return new HermesConfig(null, cfg);
    }

    /**
     * 构造若干个"带大工具结果的旧回合组"，再由 {@code agent.seedHistoryForTest} 塞进真实历史。
     *
     * <p>不用桩 LLM 真跑工具调用来造历史，是因为真实的 read_file 返回内容的大小不受控；
     * 直接构造消息才能精确控制每条工具结果恰好 {@link #BIG} 个字符。</p>
     */
    private static List<ModelMessage> bigHistoryTurns(int count) {
        List<ModelMessage> list = new ArrayList<>();
        for (int n = 1; n <= count; n++) {
            list.add(ModelMessage.user("问题" + n));
            ModelMessage call = AgentTestSupport.toolCallResponse("call-" + n, "read_file", "{}").getMessage();
            list.add(call);
            list.add(ModelMessage.tool("x".repeat(BIG), "call-" + n));
            list.add(ModelMessage.assistant("回答" + n));
        }
        return list;
    }

    @Test
    @DisplayName("压缩开启：发给模型的请求里旧工具结果被折叠，真实历史保持完整")
    void outgoingRequestShouldBeCompressedWhileRealHistoryStaysIntact() {
        ScriptedLlm llm = new ScriptedLlm().pushResponse(AgentTestSupport.textResponse("好的"));
        Agent agent = AgentTestSupport.newAgent(dataDir, llm, config(true, 1000));
        agent.installSystemPrompt();
        agent.seedHistoryForTest(bigHistoryTurns(6));

        agent.processUserMessage("继续");

        // 实际发出的请求：6 个旧回合组里，较旧的大工具结果应已折叠
        List<ModelMessage> sent = llm.capturedHistories.get(0);
        long foldedInRequest = sent.stream()
                .filter(m -> "tool".equals(m.getRole()))
                .filter(m -> m.getContent() != null && m.getContent().startsWith("[工具结果已折叠"))
                .count();
        assertThat(foldedInRequest).as("请求里应有被折叠的旧工具结果").isPositive();

        // 真实历史：所有 tool 消息仍是原始的 1000 字符
        long intact = agent.conversationHistory().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .filter(m -> m.getContent() != null && m.getContent().length() == BIG)
                .count();
        assertThat(intact).as("真实历史里的工具结果一条都不能被改").isEqualTo(6);
    }

    @Test
    @DisplayName("压缩关闭：请求里不出现任何折叠占位文字，行为与引入压缩前一致")
    void disabledCompressionShouldSendFullHistory() {
        ScriptedLlm llm = new ScriptedLlm().pushResponse(AgentTestSupport.textResponse("好的"));
        Agent agent = AgentTestSupport.newAgent(dataDir, llm, config(false, 1000));
        agent.installSystemPrompt();
        agent.seedHistoryForTest(bigHistoryTurns(6));

        agent.processUserMessage("继续");

        List<ModelMessage> sent = llm.capturedHistories.get(0);
        assertThat(sent.stream().anyMatch(m -> m.getContent() != null
                && m.getContent().startsWith("[工具结果已折叠"))).isFalse();
    }

    @Test
    @DisplayName("窗口足够大时不触发压缩（缺省 65536 对短对话完全无影响）")
    void largeWindowShouldNotTrigger() {
        ScriptedLlm llm = new ScriptedLlm().pushResponse(AgentTestSupport.textResponse("好的"));
        Agent agent = AgentTestSupport.newAgent(dataDir, llm, config(true, 65_536));
        agent.installSystemPrompt();
        agent.seedHistoryForTest(bigHistoryTurns(6));

        agent.processUserMessage("继续");

        List<ModelMessage> sent = llm.capturedHistories.get(0);
        assertThat(sent.stream().anyMatch(m -> m.getContent() != null
                && m.getContent().startsWith("[工具结果已折叠"))).isFalse();
    }

    @Test
    @DisplayName("/context 说明压缩状态：触发后显示折叠条数，未触发显示阈值，关闭时说明已关闭")
    void contextCommandShouldDescribeCompressionState() {
        // 触发后
        ScriptedLlm llm = new ScriptedLlm().pushResponse(AgentTestSupport.textResponse("好的"));
        Agent triggered = AgentTestSupport.newAgent(dataDir, llm, config(true, 1000));
        triggered.installSystemPrompt();
        triggered.seedHistoryForTest(bigHistoryTurns(6));
        triggered.processUserMessage("继续");
        assertThat(contextOf(triggered)).contains("历史压缩：最近一次请求折叠了").contains("旧工具结果");

        // 未触发
        ScriptedLlm llm2 = new ScriptedLlm().pushResponse(AgentTestSupport.textResponse("好的"));
        Agent idle = AgentTestSupport.newAgent(dataDir.resolve("b"), llm2, config(true, 65_536));
        idle.installSystemPrompt();
        idle.processUserMessage("你好");
        assertThat(contextOf(idle)).contains("历史压缩：未触发").contains("65536");

        // 关闭
        Agent off = AgentTestSupport.newAgent(dataDir.resolve("c"), new ScriptedLlm(), config(false, 1000));
        assertThat(contextOf(off)).contains("历史压缩：已关闭");
    }

    private static String contextOf(Agent agent) {
        List<String> out = new ArrayList<>();
        agent.dispatchCommandForTest("/context", out::add);
        return String.join("\n", out);
    }

    @Test
    @DisplayName("配置项解析：缺省值、显式值、非法窗口回退")
    void configKeysShouldParseWithSaneDefaults() {
        HermesConfig empty = new HermesConfig(null, new HashMap<>());
        assertThat(empty.isContextCompressionEnabled()).isTrue();
        assertThat(empty.getContextWindowTokens()).isEqualTo(65_536);

        assertThat(config(false, 8000).isContextCompressionEnabled()).isFalse();
        assertThat(config(true, 8000).getContextWindowTokens()).isEqualTo(8000);

        // 小于 1000 明显是配置错误，回退到缺省值
        assertThat(config(true, 10).getContextWindowTokens()).isEqualTo(65_536);
    }
}
