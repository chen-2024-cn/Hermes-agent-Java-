package com.cyk.agent;

import com.cyk.bean.Message;
import com.cyk.bean.Session;
import com.cyk.manager.SessionManager;
import com.cyk.security.SecurityContext;
import com.cyk.tool.ToolRegistry;
import com.cyk.ui.Ui;
import com.cyk.ui.UserInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationSwitchTest {
    @TempDir Path directory;
    @AfterEach void reset() { ToolRegistry.clearForTest(); SecurityContext.resetForTest(); Ui.resetForTest(); }

    private static String command(Agent agent, String text) {
        List<String> output = new ArrayList<>(); agent.dispatchCommandForTest(text, output::add);
        return String.join("\n", output);
    }

    @Test void localSessionAndDebugCommandsDoNotCallModelOrReplaceRuntimeDefinitions() throws Exception {
        var llm = new AgentTestSupport.ScriptedLlm();
        Agent agent = AgentTestSupport.newAgent(directory, llm); agent.installSystemPrompt();
        var before = new java.util.HashSet<>(ToolRegistry.getInstance().getAllToolName());
        assertThat(command(agent, "/sessions")).contains("暂无历史会话");
        assertThat(command(agent, "/debug tools-json --json")).startsWith("[");
        assertThat(command(agent, "/debug tools-json --verbose")).contains("SHA-256");
        assertThat(command(agent, "/debug tools-json --json --verbose")).contains("用法");
        assertThat(command(agent, "/resume ../escaped")).contains("会话ID无效");
        assertThat(command(agent, "/instructions")).contains("未知命令");
        assertThat(ToolRegistry.getInstance().getAllToolName()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(llm.calls).isZero(); agent.persistSession();
        assertThat(new SessionManager(directory).listSessions()).isEmpty();
    }

    @Test void preparedSwitchDoesNotEndOldAgentAndBothHistoriesRemainSeparate() throws Exception {
        var llm = new AgentTestSupport.ScriptedLlm().pushResponse(AgentTestSupport.textResponse("A_REPLY"))
                .pushResponse(AgentTestSupport.textResponse("B_REPLY"));
        Agent a = AgentTestSupport.newAgent(directory, llm); a.installSystemPrompt(); a.processUserMessage("A_USER");
        var sessions = new SessionManager(directory);
        Session bStored = sessions.getSession("target-b"); bStored.addMessage("user", "OLD_B");
        bStored.addMessage("assistant", "OLD_B_REPLY"); sessions.saveSession(bStored);
        Agent b = a.prepareNextConversation("target-b");
        assertThat(a.saveForSwitch()).isTrue();
        assertThat(b.sessionId()).isEqualTo("target-b");
        assertThat(a.finishForSwitch()).isTrue(); b.activateNextConversation(a); b.installSystemPrompt(); b.processUserMessage("NEW_B");
        assertThat(new SessionManager(directory).getSession(a.sessionId()).messages).extracting(Message::content)
                .containsExactly("A_USER", "A_REPLY");
        assertThat(new SessionManager(directory).getSession("target-b").messages).extracting(Message::content)
                .containsExactly("OLD_B", "OLD_B_REPLY", "NEW_B", "B_REPLY");
        assertThat(SecurityContext.sessionId()).isEqualTo("target-b");
        assertThatThrownBy(() -> b.prepareNextConversation("../escaped")).isInstanceOf(IOException.class);
    }

    @Test void failedPersistenceKeepsOriginalConversationInMemoryAndRetryDoesNotDuplicate() throws Exception {
        class FailingManager extends SessionManager {
            boolean fail = true;
            FailingManager() { super(directory); }
            @Override public void saveSession(Session session) throws IOException {
                if (fail) throw new IOException("write-failed"); super.saveSession(session);
            }
        }
        var manager = new FailingManager();
        var llm = new AgentTestSupport.ScriptedLlm().pushResponse(AgentTestSupport.textResponse("ANSWER"));
        Agent a = new Agent(AgentTestSupport.testConfig(), null, llm, manager, false);
        a.installSystemPrompt(); a.processUserMessage("KEEP");
        assertThat(a.saveForSwitch()).isFalse();
        assertThat(a.finishForSwitch()).isFalse();
        assertThat(a.conversationHistory()).extracting(m -> m.getContent()).contains("KEEP", "ANSWER");
        manager.fail = false;
        assertThat(a.saveForSwitch()).isTrue(); assertThat(a.saveForSwitch()).isTrue();
        assertThat(new SessionManager(directory).getSession(a.sessionId()).messages).extracting(Message::content)
                .containsExactly("KEEP", "ANSWER");
    }

    @Test void conversationLoopReturnsSwitchWithoutClosingSharedInputOrCreatingEmptySession() {
        var lines = new ArrayDeque<>(List.of("/chat")); int[] closes = {0};
        Ui.setUserInput(new UserInput() {
            public String readLine(String prompt) { return lines.poll(); }
            public boolean isInteractive() { return false; }
            public boolean isEofReached() { return lines.isEmpty(); }
            public void close() { closes[0]++; }
        });
        Agent agent = AgentTestSupport.newAgent(directory, new AgentTestSupport.ScriptedLlm());
        var request = agent.runConversation();
        assertThat(request.switching()).isTrue(); assertThat(request.sessionId()).isNull();
        assertThat(closes[0]).isZero();
        assertThat(new SessionManager(directory).listSessions()).isEmpty();
    }
}
