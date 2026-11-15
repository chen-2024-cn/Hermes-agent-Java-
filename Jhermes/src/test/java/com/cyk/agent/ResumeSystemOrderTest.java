package com.cyk.agent;

import com.cyk.agent.AgentTestSupport.ScriptedLlm;
import com.cyk.bean.Function;
import com.cyk.bean.Message;
import com.cyk.bean.ModelMessage;
import com.cyk.bean.Session;
import com.cyk.bean.ToolCall;
import com.cyk.config.HermesConfig;
import com.cyk.manager.SessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>resume 后 system 提示词位置与历史合法性（JH-SPEC-001 P0-5 R7 / AC5 / AC7）</h1>
 *
 * <p>改造前 {@code Agent.run()} 用 {@code add(...)} 把 system 提示词追加到历史<b>末尾</b>；
 * resume 场景下历史已从磁盘载入，于是 system 变成「对话的最后一句」（附录 A6）。
 * 后果有二：</p>
 * <ol>
 *   <li>部分供应商把末尾的 system 当成新一轮指令而非全局约束，行为不可预期；</li>
 *   <li>请求体前缀在不同会话间不再稳定，前缀缓存永久无法命中（P0-4 R8 的前提被破坏）。</li>
 * </ol>
 */
class ResumeSystemOrderTest {

    @TempDir
    Path dataDir;

    /** 写一份含工具调用的历史会话到磁盘，返回其 id。 */
    private String writeResumableSession(String sessionId) throws Exception {
        SessionManager manager = new SessionManager(dataDir);
        Session session = manager.getSession(sessionId);

        Function function = new Function();
        function.setName("read_file");
        function.setArguments("{\"path\":\"old.txt\"}");
        ToolCall call = new ToolCall();
        call.setId("call_old_1");
        call.setType("function");
        call.setFunction(function);

        ModelMessage assistant = new ModelMessage();
        assistant.setRole("assistant");
        assistant.setContent("");
        assistant.setToolCalls(List.of(call));

        session.addMessage(ModelMessage.user("旧会话的第一个问题"));
        session.addMessage(assistant);
        session.addMessage(ModelMessage.tool("{\"content\":\"旧文件内容\"}", "call_old_1"));
        session.addMessage(ModelMessage.assistant("旧会话的回答"));
        manager.saveSession(session);
        return sessionId;
    }

    @Test
    @DisplayName("resume：system 位于下标 0，其后是磁盘上的历史（AC7）")
    void resumedHistoryPutsSystemAtIndexZero() throws Exception {
        String sessionId = writeResumableSession("cli0resume01");
        ScriptedLlm llm = new ScriptedLlm();
        HermesConfig config = AgentTestSupport.testConfig();

        Agent agent = AgentTestSupport.newResumedAgent(dataDir, llm, sessionId, config);
        agent.installSystemPrompt();

        List<ModelMessage> history = agent.conversationHistory();
        assertThat(history.get(0).getRole()).as("首条必须是 system").isEqualTo("system");
        assertThat(history).hasSize(5);
        assertThat(history.get(1).getRole()).isEqualTo("user");
        assertThat(history.get(1).getContent()).isEqualTo("旧会话的第一个问题");
        // 历史顺序不得被打乱
        assertThat(history.subList(1, 5).stream().map(ModelMessage::getRole).toList())
                .containsExactly("user", "assistant", "tool", "assistant");
    }

    @Test
    @DisplayName("resume：还原的历史保持 tool_calls 与 tool_call_id 配对（AC5）")
    void resumedHistoryKeepsToolCallPairing() throws Exception {
        String sessionId = writeResumableSession("cli0resume02");
        ScriptedLlm llm = new ScriptedLlm();

        Agent agent = AgentTestSupport.newResumedAgent(dataDir, llm, sessionId,
                AgentTestSupport.testConfig());

        List<ModelMessage> history = agent.conversationHistory();
        ModelMessage assistant = history.get(1);
        assertThat(assistant.getToolCalls()).as("assistant 的 tool_calls 必须被还原").hasSize(1);
        assertThat(assistant.getToolCalls().get(0).getId()).isEqualTo("call_old_1");

        ModelMessage tool = history.get(2);
        assertThat(tool.getRole()).isEqualTo("tool");
        assertThat(tool.getToolCallId()).as("tool 消息必须带 tool_call_id").isEqualTo("call_old_1");

        // 配对完整性：每个带 tool_calls 的 assistant 都有等量 tool 响应
        long calls = assistant.getToolCalls().size();
        long responses = history.stream()
                .filter(m -> "tool".equals(m.getRole()))
                .filter(m -> "call_old_1".equals(m.getToolCallId()))
                .count();
        assertThat(responses).isEqualTo(calls);
    }

    @Test
    @DisplayName("resume：发给模型的请求体首条为 system（AC7 的请求体级证据）")
    void requestBodyStartsWithSystemAfterResume() throws Exception {
        String sessionId = writeResumableSession("cli0resume03");
        ScriptedLlm llm = new ScriptedLlm()
                .pushResponse(AgentTestSupport.textResponse("续上一次的回答"));

        Agent agent = AgentTestSupport.newResumedAgent(dataDir, llm, sessionId,
                AgentTestSupport.testConfig());
        agent.installSystemPrompt();
        agent.processUserMessage("接着上次继续");

        List<ModelMessage> sent = llm.capturedHistories.get(0);
        assertThat(sent.get(0).getRole()).as("请求体首条必须是 system").isEqualTo("system");
        assertThat(sent).as("请求体不得出现第二条 system")
                .filteredOn(m -> "system".equals(m.getRole()))
                .hasSize(1);
        assertThat(sent.get(sent.size() - 1).getContent()).isEqualTo("接着上次继续");
    }

    @Test
    @DisplayName("新会话：installSystemPrompt 后历史仅含一条 system 且位于下标 0")
    void newSessionHasSingleSystemFirst() {
        ScriptedLlm llm = new ScriptedLlm();
        Agent agent = AgentTestSupport.newAgent(dataDir, llm);

        agent.installSystemPrompt();

        assertThat(agent.conversationHistory()).hasSize(1);
        assertThat(agent.conversationHistory().get(0).getRole()).isEqualTo("system");
    }

    @Test
    @DisplayName("持久化不写入 system 提示词（体积大且每轮重建）")
    void systemPromptIsNotPersisted() throws Exception {
        ScriptedLlm llm = new ScriptedLlm()
                .pushResponse(AgentTestSupport.textResponse("答案"));
        Agent agent = AgentTestSupport.newAgent(dataDir, llm);

        agent.installSystemPrompt();
        agent.processUserMessage("问题");

        Session persisted = new SessionManager(dataDir).getSession(agent.sessionId());
        assertThat(persisted.messages).extracting(Message::role)
                .as("system 不得落盘").doesNotContain("system");
        assertThat(persisted.messages).hasSize(2);
    }
}
