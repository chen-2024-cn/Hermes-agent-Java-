package com.cyk.agent;

import com.cyk.bean.ChatCompletionResponse;
import com.cyk.bean.Function;
import com.cyk.bean.ModelMessage;
import com.cyk.bean.ToolCall;
import com.cyk.config.HermesConfig;
import com.cyk.http.LlmClient;
import com.cyk.http.LlmTransportException;
import com.cyk.manager.SessionManager;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>Agent 层测试支撑（手写桩，无 Mockito）</h1>
 *
 * <p>P0-5 R0 引入 {@link LlmClient} 接口的目的正是为了这里：不发起任何网络请求，
 * 就能驱动完整的对话回合并验证错误边界与重试行为（AC1/AC2/AC3/AC5/AC7）。</p>
 *
 * <p>同时提供「封闭数据目录」的 Agent 构造：{@link Agent} 常规路径会在真实
 * {@code ~/.jhermes} 下建会话文件与轨迹目录并拉起守护线程，测试必须避免这些副作用。</p>
 */
final class AgentTestSupport {

    private AgentTestSupport() {
    }

    /**
     * 可编程的桩 LLM：按脚本顺序返回响应或抛出异常。
     *
     * <p>脚本耗尽后仍被调用会立即报 AssertionError——这能暴露「回合循环未按预期收敛」
     * 这类死循环缺陷，而不是让测试静默挂住。</p>
     */
    static final class ScriptedLlm implements LlmClient {
        private final Deque<Object> script = new ArrayDeque<>();

        /** 每次调用时的历史快照（副本），用于断言请求体结构。 */
        final List<List<ModelMessage>> capturedHistories = new ArrayList<>();

        /** 实际被调用次数。 */
        int calls;

        ScriptedLlm pushResponse(ChatCompletionResponse response) {
            script.add(response);
            return this;
        }

        ScriptedLlm pushError(LlmTransportException error) {
            script.add(error);
            return this;
        }

        private ChatCompletionResponse next(List<ModelMessage> messages) {
            calls++;
            capturedHistories.add(new ArrayList<>(messages));
            if (script.isEmpty()) {
                throw new AssertionError("桩 LLM 被调用次数超过脚本长度（第 " + calls + " 次），"
                        + "回合循环可能未收敛");
            }
            Object item = script.poll();
            if (item instanceof LlmTransportException e) {
                throw e;
            }
            return (ChatCompletionResponse) item;
        }

        @Override
        public ChatCompletionResponse chatCompletion(List<ModelMessage> messages,
                                                    List<Map<String, Object>> tools, boolean stream) {
            return next(messages);
        }

        @Override
        public ChatCompletionResponse chatCompletionStream(List<ModelMessage> messages,
                                                          List<Map<String, Object>> tools,
                                                          java.util.function.Consumer<String> onContent) {
            return next(messages);
        }
    }

    /** 构造一个「无 RAG、无终端工具」的最小配置，避免测试触发数据库连接或命令注册。 */
    static HermesConfig testConfig() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("model", Map.of(
                "model", "test-model",
                "base_url", "http://127.0.0.1:1/v1",
                "api_key", "sk-test-not-used"));
        cfg.put("agent", Map.of("max_turns", 5, "stream", false));
        cfg.put("tools", Map.of("terminal_enabled", false));
        cfg.put("rag", Map.of("enabled", false));
        cfg.put("extract", Map.of("enabled", false));
        return new HermesConfig(null, cfg);
    }

    /**
     * 构造封闭在指定目录下的 Agent：会话落盘到 dataDir，且不启用轨迹收集与知识提取。
     *
     * @param dataDir 测试专用数据目录（JUnit @TempDir 即可，Agent 不经过 FileTool 的路径黑名单）
     */
    static Agent newAgent(Path dataDir, ScriptedLlm llm) {
        return newAgent(dataDir, llm, testConfig());
    }

    static Agent newAgent(Path dataDir, ScriptedLlm llm, HermesConfig config) {
        SessionManager sessionManager = new SessionManager(dataDir, config.getSessionMaxMessages());
        return new Agent(config, null, llm, sessionManager, false);
    }

    /** 构造一个恢复历史场景的 Agent（sessionId 由调用方指定）。 */
    static Agent newResumedAgent(Path dataDir, ScriptedLlm llm, String sessionId, HermesConfig config) {
        SessionManager sessionManager = new SessionManager(dataDir, config.getSessionMaxMessages());
        return new Agent(config, sessionId, llm, sessionManager, false);
    }

    /** 纯文本回复（不含工具调用）。 */
    static ChatCompletionResponse textResponse(String content) {
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setMessage(ModelMessage.assistant(content));
        response.setFinishReason("stop");
        return response;
    }

    /**
     * 只发起工具调用的回复。
     *
     * @param arguments 原始参数字符串；可故意传非法 JSON 以验证 AC3
     */
    static ChatCompletionResponse toolCallResponse(String callId, String toolName, String arguments) {
        Function function = new Function();
        function.setName(toolName);
        function.setArguments(arguments);

        ToolCall call = new ToolCall();
        call.setId(callId);
        call.setType("function");
        call.setFunction(function);

        ModelMessage message = new ModelMessage();
        message.setRole("assistant");
        message.setContent("");
        message.setToolCalls(List.of(call));

        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setMessage(message);
        response.setFinishReason("tool_calls");
        return response;
    }

    /** 带 N 个工具调用的回复（用于验证 tool_calls 与 tool 消息的配对裁剪）。 */
    static ChatCompletionResponse multiToolCallResponse(String... callIds) {
        List<ToolCall> calls = new ArrayList<>();
        for (String id : callIds) {
            Function function = new Function();
            function.setName("probe_tool");
            function.setArguments("{}");
            ToolCall call = new ToolCall();
            call.setId(id);
            call.setType("function");
            call.setFunction(function);
            calls.add(call);
        }
        ModelMessage message = new ModelMessage();
        message.setRole("assistant");
        message.setContent("");
        message.setToolCalls(calls);

        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setMessage(message);
        return response;
    }
}
