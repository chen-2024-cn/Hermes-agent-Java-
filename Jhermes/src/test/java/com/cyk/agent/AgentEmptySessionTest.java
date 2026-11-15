package com.cyk.agent;

import com.cyk.bean.Message;
import com.cyk.bean.ModelMessage;
import com.cyk.bean.Session;
import com.cyk.http.LlmTransportException;
import com.cyk.manager.SessionManager;
import com.cyk.ui.Ui;
import com.cyk.ui.UserInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEmptySessionTest {
    @TempDir Path dataDir;
    @AfterEach void reset() { Ui.resetForTest(); }

    private static void input(String... lines) {
        var remaining = new ArrayDeque<>(List.of(lines));
        Ui.setUserInput(new UserInput() {
            public String readLine(String prompt) { return remaining.poll(); }
            public boolean isInteractive() { return false; }
            public boolean isEofReached() { return remaining.isEmpty(); }
            public void close() { }
        });
    }

    @Test void openingAndExitingWithoutSubmittingDoesNotCreateSession() {
        input("exit");
        var llm = new AgentTestSupport.ScriptedLlm();
        var agent = AgentTestSupport.newAgent(dataDir, llm);
        agent.run();
        agent.persistSession(); // Also exercises the shutdown-hook path.
        assertThat(llm.calls).isZero();
        assertThat(new SessionManager(dataDir).sessionExists(agent.sessionId())).isFalse();
        assertThat(new SessionManager(dataDir).listSessions()).isEmpty();
    }

    @Test void eofAndSystemOnlyHistoryDoNotCreateSession() {
        input();
        var agent = AgentTestSupport.newAgent(dataDir, new AgentTestSupport.ScriptedLlm());
        agent.run();
        agent.endSession(true);
        assertThat(new SessionManager(dataDir).sessionExists(agent.sessionId())).isFalse();
    }

    @Test void whitespaceAndLocalCommandsDoNotCreateSession() {
        input(" ", "/help", "/context", "/retry", "/clear", "exit");
        var llm = new AgentTestSupport.ScriptedLlm();
        var agent = AgentTestSupport.newAgent(dataDir, llm);
        agent.run();
        assertThat(llm.calls).isZero();
        assertThat(new SessionManager(dataDir).listSessions()).isEmpty();
    }

    @Test void canceledMultilineDraftDoesNotCreateSession() {
        input("/multiline", "draft line", "/cancel", "exit");
        var llm = new AgentTestSupport.ScriptedLlm();
        var agent = AgentTestSupport.newAgent(dataDir, llm);
        agent.run();
        assertThat(llm.calls).isZero();
        assertThat(new SessionManager(dataDir).sessionExists(agent.sessionId())).isFalse();
    }

    @Test void submittedUserIsRetainedWhenModelRequestFails() {
        var llm = new AgentTestSupport.ScriptedLlm()
                .pushError(LlmTransportException.http("local failure", 400, "local rejection"));
        var agent = AgentTestSupport.newAgent(dataDir, llm);
        agent.installSystemPrompt();
        agent.processUserMessage("保留失败的问题");
        agent.endSession(true);
        var loaded = new SessionManager(dataDir).getSession(agent.sessionId());
        assertThat(loaded.messages).extracting(Message::role).containsExactly("user");
        assertThat(new SessionManager(dataDir).listSessions()).extracting(s -> s.id).containsExactly(agent.sessionId());
    }

    @Test void submittedImageWithoutTextIsARealSession() {
        var llm = new AgentTestSupport.ScriptedLlm().pushResponse(AgentTestSupport.textResponse("图像回答"));
        var agent = AgentTestSupport.newAgent(dataDir, llm);
        agent.installSystemPrompt();
        agent.processUserMessage("", List.of("data:image/png;base64,test-image"));
        agent.endSession(true);
        var session = new SessionManager(dataDir).getSession(agent.sessionId());
        assertThat(session.hasUserInput()).isTrue();
        assertThat(session.messages.get(0).contentParts()).hasSize(1);
        assertThat(new SessionManager(dataDir).listSessions()).hasSize(1);
    }

    @Test void resumeThenExitDoesNotRewriteHistoryOrAdvanceItsActivity() throws Exception {
        var manager = new SessionManager(dataDir);
        Session old = manager.getSession("existing");
        old.addMessage("user", "旧问题");
        old.addMessage("assistant", "旧回答");
        old.lastActivity = 123456789L;
        manager.saveSession(old);
        Path file = dataDir.resolve("memory/sessions/existing.json");
        byte[] before = Files.readAllBytes(file);
        var modified = Files.getLastModifiedTime(file);
        input("exit");
        var llm = new AgentTestSupport.ScriptedLlm();
        var agent = AgentTestSupport.newResumedAgent(dataDir, llm, "existing", AgentTestSupport.testConfig());
        agent.run();
        agent.persistSession();
        assertThat(llm.calls).isZero();
        assertThat(Files.readAllBytes(file)).containsExactly(before);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
        assertThat(new SessionManager(dataDir).getSession("existing").lastActivity).isEqualTo(123456789L);
    }

    @Test void pendingWriteIsRetriedWithoutDuplicatingMessages() throws Exception {
        class FailingManager extends SessionManager {
            int calls;
            FailingManager() { super(dataDir); }
            @Override public void saveSession(Session session) throws IOException {
                if (++calls == 1) throw new IOException("simulated write failure");
                super.saveSession(session);
            }
        }
        var manager = new FailingManager();
        var llm = new AgentTestSupport.ScriptedLlm().pushResponse(AgentTestSupport.textResponse("答案"));
        var agent = new Agent(AgentTestSupport.testConfig(), null, llm, manager, false);
        agent.installSystemPrompt();
        agent.processUserMessage("问题");
        assertThat(manager.sessionExists(agent.sessionId())).isFalse();
        agent.persistSession();
        agent.persistSession();
        agent.endSession(true);
        assertThat(manager.calls).isEqualTo(2);
        assertThat(new SessionManager(dataDir).getSession(agent.sessionId()).messages)
                .extracting(Message::content).containsExactly("问题", "答案");
    }

    @Test void failedSaveNeverAnnouncesSuccessfulPersistence() {
        var manager = new SessionManager(dataDir) {
            @Override public void saveSession(Session session) throws IOException { throw new IOException("local failure"); }
        };
        var llm = new AgentTestSupport.ScriptedLlm().pushResponse(AgentTestSupport.textResponse("答案"));
        var agent = new Agent(AgentTestSupport.testConfig(), null, llm, manager, false);
        agent.processUserMessage("问题");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            agent.endSession(true);
        } finally { System.setOut(original); }
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("会话已保存");
    }

    @Test void resumedHistoricalEmptyFileRemainsUntouchedUntilRealInput() throws Exception {
        Session empty = new Session("legacy-empty");
        Path file = dataDir.resolve("memory/sessions/legacy-empty.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, empty.toJson().toString());
        byte[] before = Files.readAllBytes(file);
        input("exit");
        var agent = AgentTestSupport.newResumedAgent(dataDir, new AgentTestSupport.ScriptedLlm(),
                "legacy-empty", AgentTestSupport.testConfig());
        agent.run();
        assertThat(Files.readAllBytes(file)).containsExactly(before);
        assertThat(new SessionManager(dataDir).listSessions()).isEmpty();
    }
}
