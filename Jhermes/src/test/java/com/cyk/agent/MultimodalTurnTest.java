package com.cyk.agent;

import com.cyk.bean.ModelMessage;
import com.cyk.http.LlmTransportException;
import com.cyk.manager.SessionManager;
import com.cyk.ui.InputDraft;
import com.cyk.ui.Ui;
import com.cyk.ui.UserInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultimodalTurnTest {
    @TempDir Path directory;
    private static final String IMAGE = "data:image/png;base64,retained-image";
    @AfterEach void reset() { Ui.resetForTest(); }

    @Test void retryPreservesImageAndResumeDoesNotLoseIt() {
        var llm = new AgentTestSupport.ScriptedLlm()
                .pushError(LlmTransportException.http("bad", 400, "vision unsupported"))
                .pushResponse(AgentTestSupport.textResponse("retry ok"));
        Agent agent = AgentTestSupport.newAgent(directory, llm);
        agent.processUserMessage("图文\n说明", List.of(IMAGE));
        agent.retryLastTurn();
        assertThat(agent.conversationHistory()).filteredOn(m -> "user".equals(m.getRole())).hasSize(1);
        assertThat(llm.capturedHistories.get(1).get(0).toJsonNode().toString()).contains(IMAGE);
        var next = new AgentTestSupport.ScriptedLlm().pushResponse(AgentTestSupport.textResponse("resume ok"));
        var resumed = AgentTestSupport.newResumedAgent(directory, next, agent.sessionId(), AgentTestSupport.testConfig());
        resumed.processUserMessage("再看图片");
        assertThat(next.capturedHistories.get(0).get(0).imageCount()).isEqualTo(1);
        assertThat(new SessionManager(directory).getSession(agent.sessionId()).messages.get(0).contentParts()).hasSize(2);
    }

    @Test void streamPathReceivesPureImageContentArray() {
        var config = AgentTestSupport.testConfig();
        config.setStreamEnabled(true);
        var llm = new AgentTestSupport.ScriptedLlm().pushResponse(AgentTestSupport.textResponse("看到了"));
        Agent agent = AgentTestSupport.newAgent(directory, llm, config);
        agent.processUserMessage("", List.of(IMAGE));
        assertThat(llm.capturedHistories.get(0).get(0).toJsonNode().path("content").size()).isEqualTo(1);
    }

    @Test void replSubmitsMultilineExactlyOnceAndDoesNotDispatchItsFirstLine() {
        var lines = new ArrayDeque<>(List.of("/exit\n这不是退出命令\nyes", "下一轮", "exit"));
        Ui.setUserInput(new UserInput() {
            public String readLine(String prompt) { return lines.poll(); }
            public boolean isInteractive() { return false; }
            public boolean isEofReached() { return lines.isEmpty(); }
            public void close() { }
        });
        var llm = new AgentTestSupport.ScriptedLlm()
                .pushResponse(AgentTestSupport.textResponse("第一轮"))
                .pushResponse(AgentTestSupport.textResponse("第二轮"));
        Agent agent = AgentTestSupport.newAgent(directory, llm);
        agent.run();
        assertThat(llm.calls).isEqualTo(2);
        assertThat(agent.conversationHistory()).filteredOn(m -> "user".equals(m.getRole()))
                .extracting(ModelMessage::getContent).containsExactly("/exit\n这不是退出命令\nyes", "下一轮");
    }
}
