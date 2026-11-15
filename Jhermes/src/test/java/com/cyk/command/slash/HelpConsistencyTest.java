package com.cyk.command.slash;

import com.cyk.bean.ToolEntry;
import com.cyk.capability.CapabilityRegistry;
import com.cyk.prompt.PromptConsistencyChecker;
import com.cyk.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>{@code /help} 与注册表的一致性（JH-SPEC-001 P0-3 AC3）</h1>
 *
 * <h2>为什么这个测试值得单独一类</h2>
 * <p>帮助文本是最容易过期的文档：写死工具名，日后禁用某能力或重命名工具，
 * 帮助还在教用户用一个不存在的东西——用户按帮助提问，模型找不到工具，
 * 表现为「智能体明明说自己能做却做不到」。本测试从<b>外部</b>把帮助文本解析回
 * 结构化数据，与 {@link CapabilityRegistry} 逐项比对（工具名、工具集、条数、顺序），
 * 于是「渲染读的不是注册实况」这类改动会立刻红灯。</p>
 *
 * <p>与 {@code PromptConsistencyCheckerTest} 形成交叉：那里守「系统提示词不引用未注册工具」，
 * 这里守「用户可见帮助不引用未注册工具」——两份面向不同受众（模型 / 用户）的文本，
 * 同一个真相源。</p>
 *
 * <h2>解析目标格式（{@code SlashCommandRegistry.renderHelp} 的输出契约）</h2>
 * <pre>
 * 【当前可用工具】共 16 个，6 个工具集
 *   file_operations (4)  grep_files, read_file, search_files, write_file
 * </pre>
 */
class HelpConsistencyTest {

    /** 工具表行：两空格缩进 + 工具集名 + 对齐填充空格 + (条数) + 工具名列表。 */
    private static final Pattern TOOL_ROW = Pattern.compile("^ {2}(\\S+) +\\((\\d+)\\) +(.+)$");

    /** 「共 N 个，M 个工具集」汇总行。 */
    private static final Pattern TOOL_SUMMARY =
            Pattern.compile("【当前可用工具】共 (\\d+) 个，(\\d+) 个工具集");

    private static final Map<String, List<String>> FULL_TOOLSETS = new LinkedHashMap<>();

    static {
        FULL_TOOLSETS.put("file_operations", List.of("grep_files", "read_file", "search_files", "write_file"));
        FULL_TOOLSETS.put("memory", List.of("memory_delete", "memory_get", "memory_replace", "memory_save"));
        FULL_TOOLSETS.put("skill", List.of("skill_manage"));
        FULL_TOOLSETS.put("web", List.of("fetch_page", "web_search"));
        FULL_TOOLSETS.put("terminal", List.of("run_command"));
        FULL_TOOLSETS.put("rag", List.of("rag_delete", "rag_index", "rag_list", "rag_search"));
    }

    private static final SlashCommandRegistry REGISTRY = SlashCommandRegistry.withBuiltins();

    // =========================================================================
    // 脚手架
    // =========================================================================

    private static CapabilityRegistry capabilitiesOf(Map<String, List<String>> toolsetTools) {
        ToolRegistry registry = new ToolRegistry();
        toolsetTools.forEach((toolset, names) -> names.forEach(name -> registry.register(
                new ToolEntry.Builder()
                        .name(name).toolset(toolset)
                        .schema(Map.of("description", name,
                                "parameters", Map.of("type", "object", "properties", Map.of())))
                        .handler(args -> "ok")
                        .build())));
        return CapabilityRegistry.from(registry);
    }

    /** 解析帮助文本里的工具表 → 工具集名到工具名列表（保序）。 */
    private static Map<String, List<String>> parseToolTable(String help) {
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        for (String line : sectionLines(help, "【当前可用工具】")) {
            Matcher m = TOOL_ROW.matcher(line);
            if (m.matches()) {
                List<String> names = new ArrayList<>();
                for (String part : m.group(3).split(",")) {
                    names.add(part.trim());
                }
                parsed.put(m.group(1), names);
                // 行内声明的条数必须与实际列出的工具数一致（自我一致的强断言）
                assertThat(names).as("工具集 %s 行内条数与实际列出不符", m.group(1))
                        .hasSize(Integer.parseInt(m.group(2)));
            }
        }
        return parsed;
    }

    /**
     * 取某小节内的非空行（到下一个【开头的标题为止）。
     *
     * <p>必须按小节限定：全文多处都用两空格缩进（命令表、工具表、说明段），
     * 不限定会互相污染行数统计——这正是首版断言把工具行误记为命令行的原因。</p>
     */
    private static List<String> sectionLines(String help, String sectionTitle) {
        List<String> lines = help.lines().toList();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(sectionTitle)) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("【")) {
                break;
            }
            if (!line.isBlank()) {
                result.add(line);
            }
        }
        return result;
    }

    // =========================================================================
    // AC3：工具表与注册实况逐项相等
    // =========================================================================

    @Test
    @DisplayName("满配：/help 工具表与 CapabilityRegistry.asSummary 逐项相等（集名/工具名/顺序/条数）")
    void helpToolTableMustMatchRegistryExactly() {
        CapabilityRegistry caps = capabilitiesOf(FULL_TOOLSETS);

        String help = SlashCommandRegistry.renderHelp(REGISTRY, caps);

        // ① 工具表结构与注册实况完全相同（含顺序：字典序渲染）
        assertThat(parseToolTable(help)).isEqualTo(caps.asSummary());
        // ② 汇总行的数字也来自实况（不是手工写的）
        Matcher summary = TOOL_SUMMARY.matcher(help);
        assertThat(summary.find()).as("汇总行格式应保持契约").isTrue();
        assertThat(Integer.parseInt(summary.group(1))).isEqualTo(caps.toolCount());
        assertThat(Integer.parseInt(summary.group(2))).isEqualTo(caps.toolsets().size());
        assertThat(Integer.parseInt(summary.group(1))).isEqualTo(16);
    }

    @Test
    @DisplayName("减配：rag/terminal 未注册时，帮助里既无工具集行也无其工具名")
    void disabledCapabilitiesMustDisappearFromHelp() {
        Map<String, List<String>> reduced = new LinkedHashMap<>(FULL_TOOLSETS);
        reduced.remove("rag");
        reduced.remove("terminal");
        CapabilityRegistry caps = capabilitiesOf(reduced);

        String help = SlashCommandRegistry.renderHelp(REGISTRY, caps);

        assertThat(parseToolTable(help)).isEqualTo(caps.asSummary());
        assertThat(help).doesNotContain("rag_search", "rag_list", "run_command")
                .doesNotContain("  rag (").doesNotContain("  terminal (");
        // 16 - rag(4) - terminal(1) = 11 个工具、4 个工具集（数字取自注册实况而非手写）
        assertThat(help).contains("11 个，4 个工具集");
    }

    @Test
    @DisplayName("空注册表：显式声明无可用工具，而非留白或抛异常")
    void emptyRegistryShouldSayNoToolsAvailable() {
        String help = SlashCommandRegistry.renderHelp(REGISTRY, capabilitiesOf(Map.of()));

        assertThat(help).contains("共 0 个，0 个工具集")
                .contains("无已注册工具");
        assertThat(parseToolTable(help)).isEmpty();
    }

    @Test
    @DisplayName("帮助文本经一致性自检零违规：不提及任何未注册的能力名")
    void helpTextMustPassConsistencyCheck() {
        // 复用期 4 的形态扫描器守用户可见文本：凡 snake_case 词必须是真实工具/工具集，
        // 否则说明帮助在教用户用一个不存在的东西
        for (Map<String, List<String>> variant : List.of(
                FULL_TOOLSETS,
                Map.of("file_operations", List.of("read_file")),
                Map.<String, List<String>>of())) {
            CapabilityRegistry caps = capabilitiesOf(variant);

            List<PromptConsistencyChecker.Violation> violations = PromptConsistencyChecker.checkText(
                    SlashCommandRegistry.renderHelp(REGISTRY, caps),
                    PromptConsistencyChecker.knownNames(caps));

            assertThat(violations).as("能力变体 %s 的帮助文本含未注册能力名", variant.keySet())
                    .isEmpty();
        }
    }

    // =========================================================================
    // AC3：命令表与注册表逐项一致
    // =========================================================================

    @Test
    @DisplayName("命令表：每条注册命令的调用名与描述都出现，且不多不少")
    void helpCommandTableMustMatchRegisteredCommands() {
        CapabilityRegistry caps = capabilitiesOf(FULL_TOOLSETS);
        String help = SlashCommandRegistry.renderHelp(REGISTRY, caps);

        List<SlashCommand> commands = REGISTRY.commands();
        assertThat(commands).hasSize(14);
        for (SlashCommand command : commands) {
            String invocation = String.join(", ", command.invocationNames());
            assertThat(help).as("命令 %s 的调用名缺失", command.name()).contains(invocation);
            assertThat(help).as("命令 %s 的描述缺失（描述与命令同处一条声明才不会漂移）",
                    command.name()).contains(command.description());
        }
        // 命令表行数 = 注册命令数 + 1（「普通提问」提示行），证明没有漏列或重复列
        assertThat(sectionLines(help, "【可用命令】")).hasSize(commands.size() + 1);
        // 工具表也逐行可解析（行数 = 工具集数），不存在解析不了的残留行
        assertThat(sectionLines(help, "【当前可用工具】")).hasSize(caps.toolsets().size());
    }

    @Test
    @DisplayName("追加命令后帮助自动收录（/context 与 /model 的 P0-4/P1-3 前置验证）")
    void helpMustAutomaticallyIncludeLaterCommands() {
        SlashCommandRegistry registry = SlashCommandRegistry.withBuiltins();
        registry.register(new SlashCommand("/context", List.of("/ctx"), "分段预算明细", (c, a) -> CommandOutcome.HANDLED));

        String help = SlashCommandRegistry.renderHelp(registry, capabilitiesOf(FULL_TOOLSETS));

        assertThat(help).contains("/context, /ctx").contains("分段预算明细");
        assertThat(parseToolTable(help)).hasSize(FULL_TOOLSETS.size());
    }

    // =========================================================================
    // 终端渲染纪律（Agent 接线级验证在 com.cyk.agent.HelpWiringTest）
    // =========================================================================

    @Test
    @DisplayName("帮助为纯文本：不含任何 Markdown 语法（终端不渲染，与 terminal-output 段同一纪律）")
    void helpMustBePlainTextWithoutMarkdown() {
        String help = SlashCommandRegistry.renderHelp(REGISTRY, capabilitiesOf(FULL_TOOLSETS));

        assertThat(help).doesNotContain("```").doesNotContain("**").doesNotContain("## ")
                .doesNotContain("- [").doesNotContain("](")
                .doesNotContain("\r"); // 行尾统一 LF
    }

    @Test
    @DisplayName("工具集名与工具名不重名：帮助表格解析不会歧义（结构化前提）")
    void toolsetNamesShouldNotCollideWithToolNames() {
        CapabilityRegistry caps = capabilitiesOf(FULL_TOOLSETS);

        Set<String> toolsets = caps.toolsets();
        for (String toolName : caps.allToolNames()) {
            assertThat(toolsets).as("工具名 %s 与工具集重名会让帮助表格无法解析", toolName)
                    .doesNotContain(toolName);
        }
    }
}
