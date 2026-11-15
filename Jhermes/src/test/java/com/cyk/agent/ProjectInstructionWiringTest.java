package com.cyk.agent;

import com.cyk.manager.SessionManager;
import com.cyk.prompt.ProjectInstructionLoader;
import com.cyk.prompt.ProjectInstructionManager;
import com.cyk.security.SecurityContext;
import com.cyk.tool.ToolRegistry;
import com.cyk.ui.Ui;
import com.cyk.ui.UserInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectInstructionWiringTest {
    @TempDir Path directory;

    @AfterEach void reset() {
        ToolRegistry.clearForTest();
        SecurityContext.resetForTest();
        Ui.resetForTest();
    }

    private static UserInput input(String answer) {
        return new UserInput() {
            public String readLine(String prompt) { return answer; }
            public boolean isInteractive() { return true; }
            public boolean isEofReached() { return false; }
            public void close() { }
        };
    }

    private Agent agent(AgentTestSupport.ScriptedLlm llm, SessionManager sessions, String sessionId) throws Exception {
        var manager = new ProjectInstructionManager(new ProjectInstructionLoader(directory,
                Files.createDirectories(directory.resolve("personal"))));
        return new Agent(AgentTestSupport.testConfig(), sessionId, llm, sessions, false, manager);
    }

    private static String command(Agent agent, String text) {
        List<String> lines = new ArrayList<>();
        agent.dispatchCommandForTest(text, lines::add);
        return String.join("\n", lines);
    }

    @Test void reloadChangesOnlyFirstSystemMessageAndNeverCallsModelItself() throws Exception {
        Path file = Files.writeString(directory.resolve("JHERMES.md"), "MARKER_FIRST");
        var sessions = new SessionManager(directory.resolve("data"), 100);
        var llm = new AgentTestSupport.ScriptedLlm().pushResponse(AgentTestSupport.textResponse("OK"))
                .pushResponse(AgentTestSupport.textResponse("OK"));
        Agent agent = agent(llm, sessions, null);
        agent.installSystemPrompt();
        assertThat(agent.conversationHistory().getFirst().getContent()).contains("MARKER_FIRST");
        assertThat(llm.calls).isZero();
        agent.processUserMessage("USER_ONE");
        assertThat(llm.capturedHistories.getFirst().getFirst().getContent()).contains("MARKER_FIRST");
        Files.writeString(file, "MARKER_SECOND");
        agent.processUserMessage("USER_TWO");
        var messages = llm.capturedHistories.getLast();
        assertThat(messages.stream().filter(m -> "system".equals(m.getRole())).count()).isEqualTo(1);
        assertThat(messages.getFirst().getContent()).contains("MARKER_SECOND").doesNotContain("MARKER_FIRST");
        var persisted = sessions.getSession(agent.sessionId());
        assertThat(persisted.messages.stream().filter(m -> "user".equals(m.role())).count()).isEqualTo(2);
        assertThat(persisted.messages).noneMatch(m -> "system".equals(m.role()));
        assertThat(command(agent, "/context")).contains("project-instructions", "当前占用");
    }

    @Test void localCommandsAloneDoNotCreateSessionAndInitNeverOverwrites() throws Exception {
        var sessions = new SessionManager(directory.resolve("data"), 100);
        var llm = new AgentTestSupport.ScriptedLlm();
        Agent agent = agent(llm, sessions, null);
        agent.installSystemPrompt();
        assertThat(command(agent, "/init")).contains("已创建");
        String original = Files.readString(directory.resolve("JHERMES.md"));
        assertThat(command(agent, "/init")).contains("未覆盖");
        assertThat(Files.readString(directory.resolve("JHERMES.md"))).isEqualTo(original);
        assertThat(command(agent, "/instructions")).contains("未知命令");
        assertThat(command(agent, "/help")).contains("/init", "/sessions").doesNotContain("/instructions");
        agent.persistSession();
        assertThat(sessions.sessionExists(agent.sessionId())).isFalse();
        assertThat(llm.calls).isZero();
    }

    @Test void resumedAgentRequiresCurrentRulesAgainAndDoesNotDuplicateSavedHistory() throws Exception {
        Files.writeString(directory.resolve("JHERMES.md"), "CURRENT_PROJECT");
        var sessions = new SessionManager(directory.resolve("data"), 100);
        var firstLlm = new AgentTestSupport.ScriptedLlm().pushResponse(AgentTestSupport.textResponse("OLD_REPLY"));
        Agent original = agent(firstLlm, sessions, null);
        original.installSystemPrompt();
        Ui.setUserInput(input("yes"));
        assertThat(command(original, "/instructions")).contains("未知命令");
        original.processUserMessage("OLD_USER");
        int before = sessions.getSession(original.sessionId()).messages.size();
        ToolRegistry.clearForTest(); SecurityContext.resetForTest();
        Agent resumed = agent(new AgentTestSupport.ScriptedLlm(), sessions, original.sessionId());
        resumed.installSystemPrompt();
        assertThat(resumed.conversationHistory().getFirst().getContent()).contains("CURRENT_PROJECT");
        resumed.persistSession();
        assertThat(sessions.getSession(original.sessionId()).messages).hasSize(before);
    }
}
