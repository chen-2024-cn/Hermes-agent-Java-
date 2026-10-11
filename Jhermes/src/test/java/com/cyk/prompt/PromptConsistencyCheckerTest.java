package com.cyk.prompt;

import com.cyk.bean.Skill;
import com.cyk.bean.ToolEntry;
import com.cyk.capability.CapabilityRegistry;
import com.cyk.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>PromptConsistencyChecker 测试（JH-SPEC-001 P0-3 R6）</h1>
 *
 * <p>最关键的一条是 {@link #checkerMustCatchFakeToolName}：故意在段文件里写一个
 * 假工具名，自检<b>必须</b>报违规。没有这条「自证有效性」的用例，
 * 其余全绿只能说明「当前提示词恰好没问题」，无法证明扫描器真的在工作
 *（正则写错、白名单吞掉一切、扫描范围漏段等都表现为全绿）。</p>
 */
class PromptConsistencyCheckerTest {

    /** 真实全量工具名（覆盖六个工具集，与各 Tool 类的注册一致）。 */
    private static final Set<String> ALL_REAL_TOOLS = Set.of(
            "read_file", "write_file", "search_files", "grep_files",
            "memory_save", "memory_get", "memory_delete", "memory_replace",
            "skill_manage", "web_search", "fetch_page", "run_command",
            "rag_index", "rag_search", "rag_list", "rag_delete");

    /** 白名单条数上限：防「红灯就塞一行」式无节制膨胀（膨胀说明扫描形态选错了）。 */
    private static final int WHITELIST_CAP = 10;

    // =========================================================================
    // 有效性自证（R6 的核心 AC）
    // =========================================================================

    @Test
    @DisplayName("R6 有效性：段文件里写假工具名 → 自检必须报违规（否则扫描器等于没工作）")
    void checkerMustCatchFakeToolName(@TempDir Path overrideDir) throws IOException {
        Files.writeString(overrideDir.resolve("manifest.yaml"), """
                version: 1
                segments:
                  - id: poisoned
                    file: poisoned.md
                """, StandardCharsets.UTF_8);
        // 模拟真实漂移场景：拼写错误（rag_serach）、改名后未同步（old_memory_tool）、
        // 彻底编造（fake_tool_name）
        Files.writeString(overrideDir.resolve("poisoned.md"), """
                你可以用 fake_tool_name 完成任务。
                检索请调用 rag_serach，删除旧的 old_memory_tool。
                """, StandardCharsets.UTF_8);

        PromptComposer composer = new PromptComposer(new PromptLoader(overrideDir));
        CapabilityRegistry caps = CapabilityRegistry.from(toolRegistryWith(ALL_REAL_TOOLS));
        ComposedPrompt composed = composer.compose(
                PromptComposerTestSupport.contextWith(caps, "", List.of()));

        List<PromptConsistencyChecker.Violation> violations =
                PromptConsistencyChecker.check(composed, caps);

        assertThat(violations).extracting(PromptConsistencyChecker.Violation::token)
                .containsExactlyInAnyOrder("fake_tool_name", "rag_serach", "old_memory_tool");
        // 证据行必须能定位到具体文本（WARN 与 CI 失败信息都靠它）
        assertThat(violations).allSatisfy(v -> assertThat(v.evidenceLine()).isNotBlank());
        assertThat(evidenceOfToken(violations, "fake_tool_name")).contains("fake_tool_name");
    }

    private static String evidenceOfToken(List<PromptConsistencyChecker.Violation> violations, String token) {
        return violations.stream()
                .filter(v -> v.token().equals(token))
                .map(PromptConsistencyChecker.Violation::evidenceLine)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未拿到 " + token + " 的违规记录"));
    }

    @Test
    @DisplayName("warnIfInconsistent 不抛异常，返回违规列表（自检自身绝不影响会话）")
    void warnIfInconsistentShouldNeverThrow(@TempDir Path overrideDir) throws IOException {
        Files.writeString(overrideDir.resolve("manifest.yaml"), """
                version: 1
                segments:
                  - id: bad
                    file: bad.md
                """, StandardCharsets.UTF_8);
        Files.writeString(overrideDir.resolve("bad.md"), "调用 nonexistent_tool 试试",
                StandardCharsets.UTF_8);

        PromptComposer composer = new PromptComposer(new PromptLoader(overrideDir));
        CapabilityRegistry caps = CapabilityRegistry.from(toolRegistryWith(ALL_REAL_TOOLS));
        ComposedPrompt composed = composer.compose(
                PromptComposerTestSupport.contextWith(caps, "", List.of()));

        List<PromptConsistencyChecker.Violation> returned =
                PromptConsistencyChecker.warnIfInconsistent(composed, caps);

        assertThat(returned).extracting(PromptConsistencyChecker.Violation::token)
                .containsExactly("nonexistent_tool");
    }

    // =========================================================================
    // 内置提示词现状：CI 红灯防线（段文件级全覆盖，无视 requires）
    // =========================================================================

    @Test
    @DisplayName("内置 11 个段文件全覆盖扫描：无未登记的下划线候选词（段文件级 CI 防线）")
    void allBuiltinSegmentsShouldBeConsistentWithRealToolNames() {
        // 段文件级扫描（而非装配结果级）：无视 requires，连当前配置下不注入的段也一起查——
        // 否则「rag 段的拼写错误」在 rag 关闭的开发机上永远测不到
        PromptLoader loader = new PromptLoader(null);
        for (PromptSegment segment : loader.load()) {
            List<PromptConsistencyChecker.Violation> violations =
                    PromptConsistencyChecker.checkText(segment.content(), ALL_REAL_TOOLS);
            assertThat(violations)
                    .as("段 %s 出现未登记的下划线词（拼写错误请修正，合法非工具名请加白名单并注明理由）",
                            segment.id())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("满配装配结果自检零违规（运行期 WARN 不应出现的正常态）")
    void fullAssemblyShouldHaveNoViolation() {
        CapabilityRegistry caps = CapabilityRegistry.from(fullRegistry());
        ComposedPrompt composed = new PromptComposer(new PromptLoader(null)).compose(
                PromptComposerTestSupport.contextWith(caps, "", List.of(skill("demo", "示例", "正文"))));

        assertThat(PromptConsistencyChecker.check(composed, caps)).isEmpty();
        assertThat(PromptConsistencyChecker.warnIfInconsistent(composed, caps)).isEmpty();
    }

    @Test
    @DisplayName("能力缺失时自检仍零违规：条件段随工具消失，不留悬空引用（AC1 的文本侧证据）")
    void reducedCapabilitiesShouldStillBeConsistent() {
        // 只留 file/memory：rag/web/terminal/skill 段都不注入，全文自然无这些工具名
        ToolRegistry registry = toolRegistryWith(Set.of(
                "read_file", "write_file", "search_files", "grep_files",
                "memory_save", "memory_get", "memory_delete", "memory_replace"));
        CapabilityRegistry caps = CapabilityRegistry.from(registry);

        ComposedPrompt composed = new PromptComposer(new PromptLoader(null)).compose(
                PromptComposerTestSupport.contextWith(caps, "", List.of()));

        assertThat(PromptConsistencyChecker.check(composed, caps)).isEmpty();
        assertThat(composed.authoredText()).doesNotContain("rag_", "run_command", "skill_manage");
    }

    // =========================================================================
    // 白名单纪律
    // =========================================================================

    @Test
    @DisplayName("白名单每条都有理由、条数封顶，且无一与真实工具名冲突")
    void whitelistShouldBeSmallDocumentedAndNonConflicting() {
        Map<String, String> whitelist = PromptConsistencyChecker.NON_TOOL_NAMES;

        assertThat(whitelist).isNotEmpty();
        assertThat(whitelist.size())
                .as("白名单条数应 <= %d（膨胀说明扫描形态需要收窄）", WHITELIST_CAP)
                .isLessThanOrEqualTo(WHITELIST_CAP);
        whitelist.forEach((token, reason) -> {
            assertThat(reason).as("白名单 %s 必须注明理由", token).isNotBlank();
            // 关键断言：白名单不得遮蔽真实工具——否则该工具漏注册时自检会失效
            assertThat(ALL_REAL_TOOLS).as("白名单 %s 与真实工具重名，会遮蔽漏注册", token)
                    .doesNotContain(token);
            // 白名单条目自身应符合工具名形态（否则登记它无意义，属噪音）
            assertThat(PromptConsistencyChecker.TOOL_NAME_SHAPE.matcher(token).matches())
                    .as("白名单 %s 不符合工具名形态，无需登记", token).isTrue();
        });
    }

    @Test
    @DisplayName("白名单生效：timeout_seconds/source_path/skills_index 不被判为未注册工具")
    void whitelistedNamesShouldPass() {
        String text = "长耗时命令请设置更大的 timeout_seconds；命中时引用 source_path。"
                + "裸写的 skills_index 也算。";

        assertThat(PromptConsistencyChecker.checkText(text, ALL_REAL_TOOLS)).isEmpty();
    }

    // =========================================================================
    // 扫描语义边界
    // =========================================================================

    @Test
    @DisplayName("形态判定：只认 snake_case，不误伤连字符/大写/数字/中文")
    void shapeShouldOnlyMatchSnakeCase() {
        // 连字符短语、驼峰、带数字、纯大写、中文都不算候选
        String safe = "deepseek-chat、DeepSeek Chat、v1_2 不算、SKILL_MANAGE 不算、"
                + "P0-3 分期、代码规范标准.md、sha256sum、base64；";

        assertThat(PromptConsistencyChecker.checkText(safe, ALL_REAL_TOOLS))
                .as("非 snake_case 形态不应被扫进来（收窄形态以压低误报）")
                .isEmpty();
    }

    @Test
    @DisplayName("模板占位符先剔除：变量名不被当成工具名")
    void templatePlaceholdersShouldBeStrippedBeforeScan() {
        // 未替换的占位符（用户覆盖文件写了未知变量）不应产生工具名违规，
        // 那是 renderVariables 的职责域，重复报会掩盖真实问题
        String text = "目录 {{cwd}}，索引 {{unknown_variable}}，能力 {{capabilities}}";

        assertThat(PromptConsistencyChecker.checkText(text, ALL_REAL_TOOLS)).isEmpty();
    }

    @Test
    @DisplayName("同词重复出现只报一次；null/空白输入安全返回空列表")
    void dedupAndNullSafety() {
        String text = "用 fake_tool_a 和 fake_tool_a，再来一次 fake_tool_a";

        assertThat(PromptConsistencyChecker.checkText(text, ALL_REAL_TOOLS)).hasSize(1);
        assertThat(PromptConsistencyChecker.checkText(null, ALL_REAL_TOOLS)).isEmpty();
        assertThat(PromptConsistencyChecker.checkText("   ", ALL_REAL_TOOLS)).isEmpty();
        assertThat(PromptConsistencyChecker.checkText("含 fake_tool_b", null)).hasSize(1);
        assertThat(PromptConsistencyChecker.check(null, null)).isEmpty();
    }

    @Test
    @DisplayName("证据行截断：超长行不会刷爆日志")
    void evidenceShouldBeTruncatedForHugeLines() {
        String text = "x".repeat(900) + " fake_tool_c " + "y".repeat(900);

        PromptConsistencyChecker.Violation v =
                PromptConsistencyChecker.checkText(text, ALL_REAL_TOOLS).get(0);

        assertThat(v.token()).isEqualTo("fake_tool_c");
        assertThat(v.evidenceLine().length()).isLessThanOrEqualTo(161); // 160 + 省略号
        assertThat(v.evidenceLine()).endsWith("…");
    }

    @Test
    @DisplayName("跨行定位：证据取违规词所在行而非全文")
    void evidenceShouldBeTheOffendingLine() {
        String text = "第一行正常说明\n第二行提到 not_a_registered_tool\n第三行也正常";

        String evidence = PromptConsistencyChecker.checkText(text, ALL_REAL_TOOLS).get(0).evidenceLine();

        assertThat(evidence).isEqualTo("第二行提到 not_a_registered_tool");
    }

    @Test
    @DisplayName("authoredText 排除记忆尾段：用户数据里的工具名不触发运行期自检")
    void authoredTextShouldExcludeMemoryTail() {
        CapabilityRegistry caps = CapabilityRegistry.from(fullRegistry());
        // 用户 MEMORY.md 里写了 rag_search（真实开发机就存在这种内容）
        ComposedPrompt composed = new PromptComposer(new PromptLoader(null)).compose(
                PromptComposerTestSupport.contextWith(caps, "用户记忆：常用 rag_search 查规范", List.of()));

        assertThat(composed.text()).contains("用户记忆");
        assertThat(composed.authoredText()).as("记忆尾段不属静态创作段").doesNotContain("用户记忆");
        assertThat(composed.authoredChars()).isLessThan(composed.totalChars());
        // 只扫 authoredText，故 rag 关闭的配置下用户记忆也不会误报
        ToolRegistry noRag = toolRegistryWith(Set.of("read_file", "memory_save"));
        ComposedPrompt minimal = new PromptComposer(new PromptLoader(null)).compose(
                PromptComposerTestSupport.contextWith(CapabilityRegistry.from(noRag),
                        "用户记忆：常用 rag_search 查规范", List.of()));
        assertThat(PromptConsistencyChecker.check(minimal, CapabilityRegistry.from(noRag))).isEmpty();
    }

    // =========================================================================
    // knownNames 防回归：工具集名合法，但拼错的工具集名仍必须被捕获
    // =========================================================================

    @Test
    @DisplayName("knownNames = 工具名 ∪ 工具集名：capabilities 段渲染的集名不误报")
    void knownNamesShouldUnionToolsAndToolsets() {
        CapabilityRegistry caps = CapabilityRegistry.from(fullRegistry());

        Set<String> known = PromptConsistencyChecker.knownNames(caps);

        // 工具名在集合里
        assertThat(known).contains("read_file", "rag_search", "run_command");
        // 工具集名也在集合里（期 4 实测缺陷的根因：file_operations 符合工具名形态）
        assertThat(known).contains("file_operations", "web", "memory");
        // 且 known 恰为「全部工具名 + 全部工具集名」的并集（无多余、无遗漏）
        Set<String> expected = new java.util.HashSet<>(caps.allToolNames());
        expected.addAll(caps.toolsets());
        assertThat(known).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("防失明：纳入工具集名后，拼错的工具集名（file_operation）仍被捕获")
    void misspelledToolsetNameShouldStillBeCaught() {
        // 把工具集名纳入 knownNames 的目的仅是让 capabilities 段的合法集名不误报，
        // 绝不能让「拼错的集名/漏注册的集名」蒙混过关——两者靠注册实况区分：
        // 只有真实注册的工具集名才进 knownNames
        CapabilityRegistry caps = CapabilityRegistry.from(fullRegistry());

        // file_operation（少个 s）不是已注册工具集名也不是工具名 → 必须违规
        List<PromptConsistencyChecker.Violation> violations =
                PromptConsistencyChecker.checkText("请用 file_operation 工具集", 
                        PromptConsistencyChecker.knownNames(caps));

        assertThat(violations).extracting(PromptConsistencyChecker.Violation::token)
                .containsExactly("file_operation");
        // 对照组：正确的 file_operations 不违规
        assertThat(PromptConsistencyChecker.checkText("请用 file_operations 工具集",
                PromptConsistencyChecker.knownNames(caps))).isEmpty();
    }

    // =========================================================================
    // 测试脚手架
    // =========================================================================

    static ToolRegistry toolRegistryWith(Set<String> toolNames) {
        ToolRegistry registry = new ToolRegistry();
        toolNames.forEach(name -> registry.register(new ToolEntry.Builder()
                .name(name)
                .toolset(toolsetOf(name))
                .schema(Map.of("description", name,
                        "parameters", Map.of("type", "object", "properties", Map.of())))
                .handler(args -> "ok")
                .build()));
        return registry;
    }

    /** 按工具名前缀推断工具集（只为造出与真实分组一致的 CapabilityRegistry）。 */
    private static String toolsetOf(String toolName) {
        if (toolName.startsWith("rag")) return "rag";
        if (toolName.startsWith("memory")) return "memory";
        if (toolName.startsWith("skill")) return "skill";
        if (toolName.equals("run_command")) return "terminal";
        if (toolName.equals("web_search") || toolName.equals("fetch_page")) return "web";
        return "file_operations";
    }

    private static ToolRegistry fullRegistry() {
        return toolRegistryWith(ALL_REAL_TOOLS);
    }

    private static Skill skill(String name, String description, String content) {
        return PromptComposerTestSupport.skill(name, description, content);
    }
}
