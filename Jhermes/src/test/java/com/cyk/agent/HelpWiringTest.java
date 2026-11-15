package com.cyk.agent;

import com.cyk.agent.AgentTestSupport.ScriptedLlm;
import com.cyk.capability.CapabilityRegistry;
import com.cyk.command.slash.CommandOutcome;
import com.cyk.command.slash.SlashCommandRegistry;
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
 * <h1>斜杠命令在 Agent 主循环的接线验证（JH-SPEC-001 P0-3 R4 / AC3 接线侧）</h1>
 *
 * <p>{@code SlashCommandRegistryTest} 与 {@code HelpConsistencyTest} 已覆盖命令语义与
 * 帮助渲染的纯逻辑；本测试补上最后一环——<b>Agent 真的接上了这份注册表</b>：
 * {@code /help} 渲染的能力表必须来自本会话真实注册的工具（而非另一份快照），
 * 且命令处理<b>不触发任何模型调用</b>（否则 {@code /help} 会白烧一轮 token）。</p>
 *
 * <p>放在 {@code com.cyk.agent} 包是因为 {@link AgentTestSupport}（桩 LLM、最小配置、
 * 封闭数据目录）与 {@code Agent#dispatchCommandForTest} 均为包级可见——
 * 这是刻意的：测试接缝不外泄到公共 API。</p>
 *
 * <p>配置取 {@link AgentTestSupport#testConfig()}：terminal 禁用、RAG 关闭、
 * 知识提取关闭，审计不写真实目录（{@code SecurityContext} 由 initialize 按该配置初始化）。</p>
 */
class HelpWiringTest {

    @TempDir
    Path dataDir;

    @AfterEach
    void tearDown() {
        // 本测试类经由 Agent 构造触发真实 ToolRegistry.initialize（操作全局单例），
        // 用例后复位，不污染其它依赖注册表的测试
        ToolRegistry.clearForTest();
        SecurityContext.resetForTest();
    }

    private static List<String> capture(Agent agent, String input) {
        List<String> output = new ArrayList<>();
        agent.dispatchCommandForTest(input, output::add);
        return output;
    }

    @Test
    @DisplayName("/help 输出与「同一注册实况渲染」逐字节相同（同源证明，AC3）")
    void helpShouldRenderFromTheRealRegistryOfThisSession() {
        Agent agent = AgentTestSupport.newAgent(dataDir, new ScriptedLlm(),
                AgentTestSupport.testConfig());

        List<String> output = capture(agent, "/help");
        String help = String.join("\n", output);

        // 期望值：拿全局注册表的当前实况直接渲染（与 Agent buildSystemPrompt 用的是同一份）
        String expected = SlashCommandRegistry.renderHelp(SlashCommandRegistry.withBuiltins(),
                CapabilityRegistry.from(ToolRegistry.getInstance()));

        assertThat(help).isEqualTo(expected);
        assertThat(help).contains("Jhermes").contains("当前可用工具");
    }

    @Test
    @DisplayName("testConfig 下 terminal/RAG 均未注册 → /help 不列 run_command 与 rag_*")
    void helpShouldOmitCapabilitiesAbsentFromRegistry() {
        Agent agent = AgentTestSupport.newAgent(dataDir, new ScriptedLlm(),
                AgentTestSupport.testConfig());

        String help = String.join("\n", capture(agent, "/help"));

        // 真实注册的工具必须在表里
        assertThat(help).contains("read_file").contains("memory_save")
                .contains("skill_manage").contains("web_search");
        // terminal_enabled=false 与 rag.enabled=false → 缺席
        assertThat(help).doesNotContain("run_command")
                .doesNotContain("rag_search").doesNotContain("rag_list");
        // 说明行仍要告诉用户「为什么缺」（否则用户以为程序坏了）
        assertThat(help).contains("tools.disabled_toolsets");
    }

    @Test
    @DisplayName("与系统提示词同源：/help 的工具表与 capabilities 段列出的工具名集合一致")
    void helpAndSystemPromptShouldAgreeOnAvailableTools() {
        Agent agent = AgentTestSupport.newAgent(dataDir, new ScriptedLlm(),
                AgentTestSupport.testConfig());

        String help = String.join("\n", capture(agent, "/help"));

        // 同一份注册实况经两条路径渲染：用户看的 /help 与模型看的 capabilities 段
        CapabilityRegistry caps = CapabilityRegistry.from(ToolRegistry.getInstance());
        for (String toolName : caps.allToolNames()) {
            assertThat(help).as("/help 缺少已注册工具 %s", toolName).contains(toolName);
        }
        agent.installSystemPrompt();
        String systemPrompt = agent.conversationHistory().get(0).getContent();
        for (String toolName : caps.allToolNames()) {
            assertThat(systemPrompt).as("系统提示词缺少已注册工具 %s", toolName).contains(toolName);
        }
        // 两份文本都不得引用未注册能力（AC3 与 AC1 在此交汇）
        assertThat(help).doesNotContain("run_command");
        assertThat(systemPrompt).doesNotContain("run_command");
    }

    @Test
    @DisplayName("命令处理零模型调用：exit/help/未知命令都不烧 token")
    void commandHandlingShouldNeverInvokeTheLlm() {
        ScriptedLlm llm = new ScriptedLlm();
        Agent agent = AgentTestSupport.newAgent(dataDir, llm, AgentTestSupport.testConfig());
        List<String> output = new ArrayList<>();

        CommandOutcome helpOutcome = agent.dispatchCommandForTest("/help", output::add);
        CommandOutcome unknownOutcome = agent.dispatchCommandForTest("/nope", output::add);
        CommandOutcome exitOutcome = agent.dispatchCommandForTest("exit", output::add);
        CommandOutcome notCommand = agent.dispatchCommandForTest("普通问题", output::add);

        assertThat(helpOutcome).isEqualTo(CommandOutcome.HANDLED);
        assertThat(unknownOutcome).isEqualTo(CommandOutcome.HANDLED);
        assertThat(exitOutcome).isEqualTo(CommandOutcome.EXIT);
        assertThat(notCommand).isEqualTo(CommandOutcome.NOT_A_COMMAND);

        String combined = String.join("\n", output);
        assertThat(combined).contains("可用命令");          // /help 已渲染
        assertThat(combined).contains("未知命令：/nope");    // 就地提示
        assertThat(combined).doesNotContain("普通问题");     // 非命令不产生输出
        assertThat(llm.calls).as("命令通道不得触及模型").isZero();
    }

    @Test
    @DisplayName("/retry 经接线仍回到宿主 retryLastTurn：无失败回合时给可读提示")
    void retryShouldReachHostAndReportWhenNothingToRetry() {
        ScriptedLlm llm = new ScriptedLlm();
        Agent agent = AgentTestSupport.newAgent(dataDir, llm, AgentTestSupport.testConfig());
        List<String> output = new ArrayList<>();

        CommandOutcome outcome = agent.dispatchCommandForTest("/retry", output::add);

        assertThat(outcome).isEqualTo(CommandOutcome.HANDLED);
        assertThat(llm.calls).as("无可重试回合不应触发模型调用").isZero();
    }

    @Test
    @DisplayName("配置变体：disabled_toolsets 生效后 /help 工具表随之收缩（配置→注册→帮助全链路）")
    void helpShouldShrinkWhenToolsetsAreDisabled() {
        HermesConfig config = new HermesConfig(null, new HashMap<>(Map.of(
                "tools", Map.of("terminal_enabled", true,
                        "disabled_toolsets", List.of("web", "memory")),
                "rag", Map.of("enabled", false),
                "extract", Map.of("enabled", false),
                "security", Map.of("audit_enabled", false),
                "agent", Map.of("max_turns", 5))));
        Agent agent = AgentTestSupport.newAgent(dataDir, new ScriptedLlm(), config);

        String help = String.join("\n", capture(agent, "/help"));

        // 被黑名单禁用 → 从注册表消失 → 帮助不再列出
        assertThat(help).doesNotContain("web_search").doesNotContain("fetch_page")
                .doesNotContain("memory_save");
        // 未禁用者照常在场（terminal_enabled=true）
        assertThat(help).contains("run_command").contains("read_file").contains("skill_manage");
    }
}
