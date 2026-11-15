package com.cyk.agent;

import com.cyk.bean.ChatCompletionResponse;
import com.cyk.bean.ModelMessage;
import com.cyk.http.LlmClient;
import com.cyk.http.LlmTransportException;
import com.cyk.manager.SessionManager;
import com.cyk.ui.Ui;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWaitingAnimationTest {
    @TempDir Path directory;
    private PrintStream originalOut;
    private final ByteArrayOutputStream answers = new ByteArrayOutputStream();
    private final ByteArrayOutputStream statuses = new ByteArrayOutputStream();

    @BeforeEach void capture() {
        originalOut = System.out;
        System.setOut(new PrintStream(answers, true, StandardCharsets.UTF_8));
        Ui.setStreams(System.out, new PrintStream(statuses, true, StandardCharsets.UTF_8));
    }
    @AfterEach void restore() {
        System.setOut(originalOut);
        Ui.resetForTest();
    }

    @Test void nonInteractiveStatusIsNotPartOfAnswerOrSavedHistory() {
        var llm = new AgentTestSupport.ScriptedLlm().pushResponse(AgentTestSupport.textResponse("ANSWER"));
        Agent agent = AgentTestSupport.newAgent(directory, llm);
        agent.processUserMessage("question");
        assertThat(statuses.toString(StandardCharsets.UTF_8)).contains("正在生成回答").doesNotContain("\033");
        assertThat(answers.toString(StandardCharsets.UTF_8)).contains("ANSWER").doesNotContain("正在生成回答", "⠋");
        assertThat(agent.conversationHistory()).extracting(ModelMessage::getContent).containsExactly("question", "ANSWER");
        assertThat(new SessionManager(directory).getSession(agent.sessionId()).messages)
                .extracting(message -> message.content()).containsExactly("question", "ANSWER");
    }

    @Test void emptyStreamDeltasDoNotPrintAnAssistantPrefix() {
        var config = AgentTestSupport.testConfig();
        config.setStreamEnabled(true);
        LlmClient llm = new LlmClient() {
            @Override public ChatCompletionResponse chatCompletion(List<ModelMessage> messages,
                    List<Map<String, Object>> tools, boolean stream) { throw new AssertionError("wrong mode"); }
            @Override public ChatCompletionResponse chatCompletionStream(List<ModelMessage> messages,
                    List<Map<String, Object>> tools, Consumer<String> sink) {
                sink.accept("");
                sink.accept(null);
                assertThat(answers.toString(StandardCharsets.UTF_8)).doesNotContain("◆");
                sink.accept("STREAM_ANSWER\n");
                return AgentTestSupport.textResponse("STREAM_ANSWER\n");
            }
        };
        Agent agent = new Agent(config, null, llm, new SessionManager(directory), false);
        agent.processUserMessage("question");
        assertThat(answers.toString(StandardCharsets.UTF_8)).contains("STREAM_ANSWER");
        assertThat(statuses.toString(StandardCharsets.UTF_8)).doesNotContain("\033");
    }

    @Test void failedRequestCanRetryAndLeavesNoAnimationThread() {
        var llm = new AgentTestSupport.ScriptedLlm()
                .pushError(LlmTransportException.http("local", 400, "bad request"))
                .pushResponse(AgentTestSupport.textResponse("RETRY_ANSWER"));
        Agent agent = AgentTestSupport.newAgent(directory, llm);
        agent.processUserMessage("question");
        agent.retryLastTurn();
        assertThat(answers.toString(StandardCharsets.UTF_8)).contains("本轮请求失败", "RETRY_ANSWER");
        assertThat(agent.conversationHistory()).filteredOn(message -> "user".equals(message.getRole())).hasSize(1);
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(thread ->
                thread.isAlive() && thread.getName().equals("jhermes-waiting-animation"));
    }

    @Test void toolStatusUsesDiagnosticsAndDoesNotBecomeAUserMessage() {
        var llm = new AgentTestSupport.ScriptedLlm()
                .pushResponse(AgentTestSupport.toolCallResponse("local-call", "read_file", "{}"))
                .pushResponse(AgentTestSupport.textResponse("FINAL_ANSWER"));
        Agent agent = AgentTestSupport.newAgent(directory, llm);
        agent.processUserMessage("question");
        assertThat(statuses.toString(StandardCharsets.UTF_8)).contains("正在执行工具: read_file");
        assertThat(answers.toString(StandardCharsets.UTF_8)).contains("FINAL_ANSWER").doesNotContain("正在执行工具");
        assertThat(agent.conversationHistory()).extracting(ModelMessage::getContent)
                .noneMatch(content -> content != null && content.contains("正在执行工具"));
    }
}
