package com.cyk.agent;

import com.cyk.agent.AgentTestSupport.ScriptedLlm;
import com.cyk.bean.ChatCompletionResponse;
import com.cyk.bean.Usage;
import com.cyk.command.slash.CommandOutcome;
import com.cyk.security.SecurityContext;
import com.cyk.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>{@code /context} 在 Agent 里的接线验证（JH-SPEC-001 P0-4 R2）</h1>
 *
 * <p>{@code ContextReportTest} 已经验证了报告的算法；本测试验证最后一环：
 * <b>Agent 真的把自己的数据（系统提示词、工具定义、历史、API 样本）接给了报告</b>。</p>
 *
 * <p>和 {@code HelpWiringTest} 一样放在 {@code com.cyk.agent} 包，
 * 因为 {@code dispatchCommandForTest}、{@code installSystemPrompt} 都是包级可见的测试接缝。</p>
 */
class ContextWiringTest {

    @TempDir
    Path dataDir;

    @AfterEach
    void tearDown() {
        // Agent 构造会初始化全局单例（工具注册表、安全上下文），用完必须复位，免得影响其他测试
        ToolRegistry.clearForTest();
        SecurityContext.resetForTest();
    }

    private static String run(Agent agent, String command) {
        List<String> output = new ArrayList<>();
        agent.dispatchCommandForTest(command, output::add);
        return String.join("\n", output);
    }

    @Test
    @DisplayName("新会话：/context 列出系统提示词各段和 tools，并说明尚无 API 数据")
    void freshSessionShouldShowBreakdownWithoutApiData() {
        ScriptedLlm llm = new ScriptedLlm();
        Agent agent = AgentTestSupport.newAgent(dataDir, llm);
        agent.installSystemPrompt();

        String report = run(agent, "/context");

        assertThat(report).contains("上下文预算明细")
                .contains("identity")                       // 系统提示词的分段明细
                .containsPattern("tools \\(\\d+\\)")        // tools 行带上真实的工具个数
                .contains("history:user (0)")
                .contains("尚无 API 实测数据");
        assertThat(llm.calls).as("/context 不得触发模型调用").isZero();
    }

    @Test
    @DisplayName("对话一轮后：历史计数更新，并出现 API 实测对账行")
    void afterOneTurnShouldReconcileWithApiUsage() {
        ChatCompletionResponse response = AgentTestSupport.textResponse("你好，我是 Jhermes");
        Usage usage = new Usage();
        usage.setPromptTokens(1234);
        usage.setCompletionTokens(8);
        usage.setTotalTokens(1242);
        usage.setCachedTokens(1000);
        response.setUsage(usage);

        ScriptedLlm llm = new ScriptedLlm().pushResponse(response);
        Agent agent = AgentTestSupport.newAgent(dataDir, llm);
        agent.installSystemPrompt();
        agent.processUserMessage("你好");

        String report = run(agent, "/context");

        assertThat(report).contains("history:user (1)")
                .contains("history:assistant (1)")
                .contains("prompt_tokens=1234")
                .contains("缓存命中 1000")
                .contains("偏差");
        assertThat(llm.calls).as("只有用户那一句触发了模型调用，/context 没有").isEqualTo(1);
    }

    @Test
    @DisplayName("响应不带 usage 时不留对账样本，/context 仍如实说明没有实测数据")
    void responseWithoutUsageShouldNotCreateSample() {
        ScriptedLlm llm = new ScriptedLlm().pushResponse(AgentTestSupport.textResponse("好的"));
        Agent agent = AgentTestSupport.newAgent(dataDir, llm);
        agent.installSystemPrompt();
        agent.processUserMessage("你好");

        String report = run(agent, "/context");

        assertThat(report).contains("history:user (1)").contains("尚无 API 实测数据");
    }

    @Test
    @DisplayName("/context 是已注册命令：返回 HANDLED，且出现在 /help 的命令表里")
    void contextShouldBeRegisteredAndListedInHelp() {
        Agent agent = AgentTestSupport.newAgent(dataDir, new ScriptedLlm());
        List<String> output = new ArrayList<>();

        CommandOutcome outcome = agent.dispatchCommandForTest("/context", output::add);
        String help = run(agent, "/help");

        assertThat(outcome).isEqualTo(CommandOutcome.HANDLED);
        assertThat(help).contains("/context");
    }

    @Test
    @DisplayName("提示词尚未装配时 /context 不抛异常，明确标注未装配")
    void beforeSystemPromptInstalledShouldDegradeGracefully() {
        Agent agent = AgentTestSupport.newAgent(dataDir, new ScriptedLlm());

        String report = run(agent, "/context");

        assertThat(report).contains("system (未装配)");
    }
}
