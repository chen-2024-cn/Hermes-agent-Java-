package com.cyk.agent;

import com.cyk.agent.AgentTestSupport.ScriptedLlm;
import com.cyk.bean.Message;
import com.cyk.bean.ModelMessage;
import com.cyk.bean.Session;
import com.cyk.bean.ToolEntry;
import com.cyk.http.LlmTransportException;
import com.cyk.manager.SessionManager;
import com.cyk.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <h1>回合级错误边界与重试（JH-SPEC-001 P0-5 AC1/AC2/AC3）</h1>
 *
 * <p>改造前 {@code Agent.run()} 只捕获 {@code IOException}，而 LLM 的 HTTP/网络错误、
 * 参数 JSON 解析失败、工具内部异常全部以 {@code RuntimeException} 上抛——
 * 结果是<b>一次 429、一次坏参数、一次工具 ClassCastException 就让进程退出，
 * 且 endSession 不被调用，整场对话与知识提取全部丢失</b>（附录 A1/A2/A3）。</p>
 *
 * <p>本测试用手写桩 {@link LlmClient}（无 Mockito，见 CC-5）逐条验证：</p>
 * <ul>
 *   <li>可重试错误经退避重试后成功；</li>
 *   <li>不可重试错误（401）不重试、REPL 存活、半截消息被回滚；</li>
 *   <li>工具异常与坏参数都降级为 error JSON 回传模型，会话继续；</li>
 *   <li>每个回合结束即落盘（强杀进程也不丢已完成回合的自动化近似）。</li>
 * </ul>
 */
class AgentResilienceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 探针工具名：注册到全局单例，用例结束后必须反注册，避免污染其它测试。 */
    private static final String PROBE_TOOL = "probe_boom_tool";

    @TempDir
    Path dataDir;

    @AfterEach
    void cleanup() {
        ToolRegistry.getInstance().unregister(PROBE_TOOL);
    }

    // =========================================================================
    // AC1：可重试与不可重试错误的分野
    // =========================================================================

    @Test
    @DisplayName("429 后重试成功：回合正常完成，会话已逐轮落盘")
    void rateLimitThenRetryCompletesTurn() {
        ScriptedLlm llm = new ScriptedLlm()
                .pushError(LlmTransportException.http("请求失败", 429, "{\"error\":\"rate_limit\"}"))
                .pushResponse(AgentTestSupport.textResponse("这是重试成功后的回答"));

        Agent agent = AgentTestSupport.newAgent(dataDir, llm);

        assertThatCode(() -> agent.processUserMessage("你好")).doesNotThrowAnyException();

        assertThat(llm.calls).as("429 应触发一次重试").isEqualTo(2);
        assertThat(lastAssistantContent(agent)).isEqualTo("这是重试成功后的回答");
    }

    @Test
    @DisplayName("401 不重试且 REPL 存活：异常不外抛，半截消息被回滚")
    void unauthorizedDoesNotRetryAndReplSurvives() {
        ScriptedLlm llm = new ScriptedLlm()
                .pushError(LlmTransportException.http("请求失败", 401, "Invalid Authentication"));

        Agent agent = AgentTestSupport.newAgent(dataDir, llm);

        // AC1 关键：进程不得退出、异常不得穿透到 REPL 主循环
        assertThatCode(() -> agent.processUserMessage("查一下")).doesNotThrowAnyException();

        assertThat(llm.calls).as("凭据错误不应重试").isEqualTo(1);
        // R2 回滚：只保留用户的提问，不留没有任何产出的空助手消息
        assertThat(roles(agent)).containsExactly("user");
    }

    @Test
    @DisplayName("工具回合中途失败：回滚后不留孤立 tool 消息")
    void failureAfterToolRoundRollsBackOrphanMessages() {
        // 用 401（不可重试）：本用例验证的是「半截消息回滚」而非重试；
        // 若用 502 会走满 3 次退避重试（3.5s），重试耗尽路径已由
        // LlmRetryPolicyTest 用假 Sleeper 精确覆盖，此处无需重复付出等待成本。
        ScriptedLlm llm = new ScriptedLlm()
                .pushResponse(AgentTestSupport.toolCallResponse("call_1", "read_file", "{}"))
                .pushError(LlmTransportException.http("请求失败", 401, "Invalid Authentication"));

        Agent agent = AgentTestSupport.newAgent(dataDir, llm);
        assertThatCode(() -> agent.processUserMessage("读个文件")).doesNotThrowAnyException();

        // 失败发生在第 2 次 LLM 调用，此时历史里已有 assistant(tool_calls) + tool 结果；
        // 若只按单条回滚就会留下「有 tool_calls 但没有对应响应」的非法历史
        assertThat(roles(agent)).containsExactly("user");
        assertThat(agent.conversationHistory())
                .as("回滚后不得残留半截 assistant/tool 消息")
                .noneMatch(m -> "tool".equals(m.getRole()))
                .noneMatch(m -> m.getToolCalls() != null && !m.getToolCalls().isEmpty());
    }

    @Test
    @DisplayName("/retry 重发上一轮：不重复入队 user 消息")
    void retryResendsTurnWithoutDuplicatingUserMessage() {
        ScriptedLlm llm = new ScriptedLlm()
                .pushError(LlmTransportException.http("请求失败", 401, "denied"))
                .pushResponse(AgentTestSupport.textResponse("重试后的回答"));

        Agent agent = AgentTestSupport.newAgent(dataDir, llm);
        agent.processUserMessage("原始问题");
        assertThat(roles(agent)).containsExactly("user");

        agent.retryLastTurn();

        assertThat(roles(agent)).as("用户消息不得被重复入队").containsExactly("user", "assistant");
        assertThat(lastAssistantContent(agent)).isEqualTo("重试后的回答");
        assertThat(llm.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("无失败回合时 /retry 给出提示而非报错")
    void retryWithoutFailureIsNoop() {
        ScriptedLlm llm = new ScriptedLlm()
                .pushResponse(AgentTestSupport.textResponse("ok"));
        Agent agent = AgentTestSupport.newAgent(dataDir, llm);
        agent.processUserMessage("你好");

        assertThatCode(agent::retryLastTurn).doesNotThrowAnyException();
        assertThat(llm.calls).as("成功回合后不应有多余的 LLM 调用").isEqualTo(1);
    }

    // =========================================================================
    // AC2/AC3：工具异常与坏参数不得终结会话
    // =========================================================================

    @Test
    @DisplayName("工具抛 ClassCastException：模型收到 error JSON，会话继续")
    void toolHandlerExceptionBecomesErrorJson() {
        ScriptedLlm llm = new ScriptedLlm()
                .pushResponse(AgentTestSupport.toolCallResponse("call_1", PROBE_TOOL, "{}"))
                .pushResponse(AgentTestSupport.textResponse("工具出错了，我换个办法"));

        Agent agent = AgentTestSupport.newAgent(dataDir, llm);
        // 探针必须在 Agent 构造之后注册：构造会触发 ToolRegistry.initialize()，
        // 而 initialize 自 P0-3 起是幂等重建（先清空再注册，矩阵测试依赖），
        // 先注册探针会被清空（这正是「运行期重载不残留旧工具」的生产语义）。
        registerThrowingProbeTool();
        assertThatCode(() -> agent.processUserMessage("试试这个工具")).doesNotThrowAnyException();

        String toolResult = contentOfFirstRole(agent, "tool");
        assertThat(toolResult).contains("error").contains("工具执行异常");
        assertThat(toolResult).as("错误 JSON 需带上异常类型供模型判断").contains("ClassCastException");
        assertThat(lastAssistantContent(agent)).isEqualTo("工具出错了，我换个办法");
    }

    @Test
    @DisplayName("模型给出非法 arguments JSON：回传 toolError，会话继续")
    void malformedArgumentsBecomeErrorJson() {
        ScriptedLlm llm = new ScriptedLlm()
                .pushResponse(AgentTestSupport.toolCallResponse("call_1", "read_file", "{这不是合法JSON"))
                .pushResponse(AgentTestSupport.textResponse("我重新调整参数"));

        Agent agent = AgentTestSupport.newAgent(dataDir, llm);
        assertThatCode(() -> agent.processUserMessage("读文件")).doesNotThrowAnyException();

        String toolResult = contentOfFirstRole(agent, "tool");
        assertThat(toolResult).contains("error").contains("不是合法 JSON");
        assertThat(llm.calls).as("错误回传后模型应能继续对话").isEqualTo(2);
    }

    // =========================================================================
    // R4/R5：逐轮持久化与工具字段保全
    // =========================================================================

    @Test
    @DisplayName("每回合结束即落盘：无需等到 endSession")
    void turnIsPersistedBeforeSessionEnds() throws Exception {
        ScriptedLlm llm = new ScriptedLlm()
                .pushResponse(AgentTestSupport.textResponse("答案"));
        Agent agent = AgentTestSupport.newAgent(dataDir, llm);

        agent.processUserMessage("会被持久化的问题");

        // 刻意不调用 endSession：模拟「进程随后被强杀」，磁盘上应已有本回合内容（AC4 的自动化近似）
        Path sessionFile = dataDir.resolve("memory").resolve("sessions")
                .resolve(agent.sessionId() + ".json");
        assertThat(sessionFile).as("回合结束应已写出会话文件").exists();

        String json = Files.readString(sessionFile, StandardCharsets.UTF_8);
        assertThat(json).contains("会被持久化的问题").contains("答案");
    }

    @Test
    @DisplayName("持久化保留 tool_calls/tool_call_id：resume 后配对完整")
    void persistedSessionKeepsToolCallPairing() throws Exception {
        ScriptedLlm llm = new ScriptedLlm()
                .pushResponse(AgentTestSupport.toolCallResponse("call_abc", "read_file", "{\"path\":\"a.txt\"}"))
                .pushResponse(AgentTestSupport.textResponse("读完了"));
        Agent agent = AgentTestSupport.newAgent(dataDir, llm);

        agent.processUserMessage("读 a.txt");

        // 重新从磁盘载入（等价于 resume 的读取路径）
        Session reloaded = new SessionManager(dataDir).getSession(agent.sessionId());
        assertThat(reloaded.messages).isNotEmpty();
        assertThat(reloaded.messages)
                .as("assistant 消息必须保留 tool_calls，否则 resume 后请求体非法（AC5）")
                .anyMatch(Message::hasToolCalls);
        assertThat(reloaded.messages)
                .as("tool 消息必须保留 tool_call_id")
                .anyMatch(m -> m.isToolMessage() && "call_abc".equals(m.toolCallId()));
        assertThat(reloaded.findUnpairedToolMessages()).as("配对必须完整").isEmpty();

        // system 提示词不应被持久化（体积大且每次重建）
        assertThat(reloaded.messages).noneMatch(m -> "system".equals(m.role()));
    }

    @Test
    @DisplayName("逐轮持久化不产生重复消息：增量水位生效")
    void repeatedPersistDoesNotDuplicateMessages() throws Exception {
        ScriptedLlm llm = new ScriptedLlm()
                .pushResponse(AgentTestSupport.textResponse("a1"))
                .pushResponse(AgentTestSupport.textResponse("a2"));
        Agent agent = AgentTestSupport.newAgent(dataDir, llm);

        agent.processUserMessage("q1");
        agent.processUserMessage("q2");
        agent.persistSession();// 幂等复调，模拟关闭钩子与 endSession 的重复触发

        Session reloaded = new SessionManager(dataDir).getSession(agent.sessionId());
        assertThat(reloaded.messages).hasSize(4);
        assertThat(reloaded.messages.stream().map(Message::content).toList())
                .containsExactly("q1", "a1", "q2", "a2");
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    /** 注册一个必定抛出 ClassCastException 的探针工具（模拟工具内部未预期的运行时错误）。 */
    private static void registerThrowingProbeTool() {
        ToolRegistry.getInstance().register(new ToolEntry.Builder()
                .name(PROBE_TOOL)
                .toolset("test")
                .schema(Map.of("description", "测试探针：总是抛出 ClassCastException",
                        "parameters", Map.of("type", "object", "properties", Map.of())))
                .handler(args -> {
                    throw new ClassCastException("probe boom");
                })
                .build());
    }

    private static List<String> roles(Agent agent) {
        return agent.conversationHistory().stream().map(ModelMessage::getRole).toList();
    }

    private static String lastAssistantContent(Agent agent) {
        List<ModelMessage> history = agent.conversationHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("assistant".equals(history.get(i).getRole())) {
                return history.get(i).getContent();
            }
        }
        return null;
    }

    private static String contentOfFirstRole(Agent agent, String role) {
        for (ModelMessage m : agent.conversationHistory()) {
            if (role.equals(m.getRole())) {
                return m.getContent();
            }
        }
        throw new AssertionError("历史中没有 role=" + role + " 的消息，实际为 " + roles(agent));
    }
}
