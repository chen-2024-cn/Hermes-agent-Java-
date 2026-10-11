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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptComposer 装配单元测试（JH-SPEC-001 P0-3 R1/R5/R7，AC1/AC2 的 composer 级证据）。
 *
 * <p>全部依赖注入：注册实况用桩 {@link ToolRegistry} 构造、目录快照直接 new record、
 * 技能用 bean setter 构造——不触碰全局单例、不读真实 {@code ~/.jhermes}。</p>
 */
class PromptComposerTest {

    /** 满配注册实况：六工具集全注册。 */
    private static final Map<String, List<String>> FULL_TOOLSETS = Map.of(
            "file_operations", List.of("read_file", "write_file"),
            "memory", List.of("memory_save"),
            "skill", List.of("skill_manage"),
            "web", List.of("web_search", "fetch_page"),
            "terminal", List.of("run_command"),
            "rag", List.of("rag_search", "rag_list"));

    /** manifest 全段 + 记忆尾段的注入序（= 前缀序，P0-4 R8）。 */
    private static final List<String> FULL_ORDER = List.of(
            "identity", "working-dir", "execution", "terminal-output", "memory",
            "terminal-tool", "web-search", "rag", "skills", "capabilities",
            "skills-index", PromptComposer.MEMORY_SNAPSHOT_ID);

    private final PromptComposer composer = new PromptComposer(new PromptLoader(null));

    // =========================================================================
    // 测试脚手架
    // =========================================================================

    private static CapabilityRegistry caps(Map<String, List<String>> toolsetTools) {
        ToolRegistry registry = new ToolRegistry();
        toolsetTools.forEach((toolset, names) -> names.forEach(name -> registry.register(
                new ToolEntry.Builder()
                        .name(name)
                        .toolset(toolset)
                        .schema(Map.of("description", name,
                                "parameters", Map.of("type", "object", "properties", Map.of())))
                        .handler(args -> "ok")
                        .build())));
        return CapabilityRegistry.from(registry);
    }

    private static CapabilityRegistry fullCaps() {
        return caps(FULL_TOOLSETS);
    }

    private static WorkingDirSnapshot workDir() {
        return new WorkingDirSnapshot(Path.of("C:\\proj"), "Windows 11",
                "C:\\Users\\tester", List.of("pom.xml", "src/"));
    }

    private static Skill skill(String name, String description, String content) {
        Skill s = new Skill();
        s.setName(name);
        s.setDescription(description);
        s.setContent(content);
        return s;
    }

    private static PromptContext context(CapabilityRegistry capabilities, String memory, List<Skill> skills) {
        return new PromptContext(capabilities, workDir(), memory, skills);
    }

    // =========================================================================
    // 满配装配
    // =========================================================================

    @Test
    @DisplayName("满配：全段按 manifest 顺序注入 + 记忆尾段，无模板残留")
    void fullCapabilitiesComposeAllSegmentsInOrder() {
        ComposedPrompt composed = composer.compose(context(fullCaps(), "长期记忆内容ABC",
                List.of(skill("my-skill", "一句用途描述", "技能正文严禁进入前缀"))));

        assertThat(composed.segments()).extracting(ComposedPrompt.SegmentRender::id)
                .containsExactlyElementsOf(FULL_ORDER);
        assertThat(composed.segments())
                .allSatisfy(d -> assertThat(d.injected()).as("段 %s 应注入", d.id()).isTrue());

        String text = composed.text();
        assertThat(text)
                .startsWith("你是Jhermes智能体")
                .contains("C:\\proj").contains("Windows 11").contains("pom.xml")
                .contains("当前可用能力（唯一权威清单）")
                .contains("my-skill: 一句用途描述")
                .endsWith("长期记忆内容ABC");
        // R1：装配结果不得残留任何未替换模板变量
        assertThat(text).doesNotContain("{{");
        // R9：技能正文不进前缀（索引只含名称+一句描述）
        assertThat(text).doesNotContain("技能正文严禁进入前缀");
    }

    @Test
    @DisplayName("明细自洽：注入段字符数之和 + 分隔符 = 全文长度；跳过段计 0")
    void segmentDetailsShouldReconcileWithTextLength() {
        ComposedPrompt composed = composer.compose(context(fullCaps(), "记忆", List.of()));

        List<ComposedPrompt.SegmentRender> injected = composed.injectedSegments();
        int sum = injected.stream().mapToInt(ComposedPrompt.SegmentRender::charCount).sum();
        // 段间以空行（\n\n）连接：总长 = 各段之和 + 2×(段数-1)
        assertThat(composed.totalChars()).isEqualTo(sum + 2 * (injected.size() - 1));
        assertThat(composed.skippedSegments())
                .allSatisfy(d -> assertThat(d.charCount()).isZero())
                .allSatisfy(d -> assertThat(d.skipReason()).isNotBlank());
        assertThat(composed.totalChars()).isEqualTo(composed.text().length());
    }

    @Test
    @DisplayName("capabilities 渲染：工具集字典序 × 工具名字典序，无描述重复")
    void capabilitiesRenderingShouldBeCompactAndSorted() {
        String rendered = PromptComposer.renderCapabilities(fullCaps());

        assertThat(rendered).contains("- file_operations: read_file, write_file");
        assertThat(rendered).contains("- web: fetch_page, web_search"); // 集内字典序
        assertThat(rendered).contains("- rag: rag_list, rag_search");
        // 与 tools 数组纯重复的描述文本不再出现（旧 Available Tools 清单的教训）
        assertThat(rendered).doesNotContain("description");
        // 行序 = 工具集字典序
        assertThat(rendered.lines().map(l -> l.split("[ :]")[1]).toList())
                .containsExactly("file_operations", "memory", "rag", "skill", "terminal", "web");
    }

    @Test
    @DisplayName("空注册表：capabilities 渲染显式声明无工具，装配不抛异常")
    void emptyRegistryComposesGracefully() {
        String rendered = PromptComposer.renderCapabilities(caps(Map.of()));
        assertThat(rendered).contains("当前无已注册工具");

        ComposedPrompt composed = composer.compose(context(caps(Map.of()), "", List.of()));
        // 条件段全部跳过，只剩恒注入段
        assertThat(composed.segments()).extracting(ComposedPrompt.SegmentRender::id)
                .contains("identity", "working-dir", "execution", "terminal-output", "capabilities");
        assertThat(composed.text()).contains("当前无已注册工具");
    }

    // =========================================================================
    // requires 判定（R7：注册实况为唯一判据；AC1/AC2）
    // =========================================================================

    @Test
    @DisplayName("AC2：rag 未注册 → rag 段不注入，全文不含任何 rag_ 字样")
    void ragOffShouldRemoveAllRagReferences() {
        Map<String, List<String>> noRag = new HashMap<>(FULL_TOOLSETS);
        noRag.remove("rag");

        ComposedPrompt composed = composer.compose(context(caps(noRag), "", List.of()));

        assertThat(composed.text())
                .doesNotContain("知识库检索与管理")
                .doesNotContain("rag_");
        assertThat(composed.segment("rag")).isPresent().get()
                .satisfies(d -> {
                    assertThat(d.injected()).isFalse();
                    assertThat(d.skipReason()).contains("rag").contains("未注册");
                });
    }

    @Test
    @DisplayName("AC1：run_command 未注册 → 终端指引段不注入，全文不含 run_command")
    void terminalOffShouldRemoveTerminalGuidanceAndToolName() {
        Map<String, List<String>> noTerminal = new HashMap<>(FULL_TOOLSETS);
        noTerminal.remove("terminal");

        ComposedPrompt composed = composer.compose(context(caps(noTerminal), "", List.of()));

        assertThat(composed.text())
                .doesNotContain("终端命令执行（run_command）")
                .doesNotContain("run_command");
        assertThat(composed.segment("terminal-tool")).get()
                .satisfies(d -> assertThat(d.skipReason()).contains("run_command"));
    }

    @Test
    @DisplayName("skill_manage 未注册 → skills 指引与技能索引段同时消失；有技能也不注入")
    void skillToolUnregisteredShouldRemoveBothSkillSegments() {
        Map<String, List<String>> noSkill = new HashMap<>(FULL_TOOLSETS);
        noSkill.remove("skill");

        ComposedPrompt composed = composer.compose(context(caps(noSkill), "",
                List.of(skill("orphan", "有技能但工具没注册", "x"))));

        assertThat(composed.text()).doesNotContain("skill_manage").doesNotContain("orphan");
        assertThat(composed.segment("skills")).get().satisfies(d -> assertThat(d.injected()).isFalse());
        assertThat(composed.segment("skills-index")).get().satisfies(d -> assertThat(d.injected()).isFalse());
    }

    @Test
    @DisplayName("无技能 → 索引段跳过（原因=本机无技能），但 skills 指引段仍注入")
    void noSkillsShouldSkipIndexOnly() {
        ComposedPrompt composed = composer.compose(context(fullCaps(), "", List.of()));

        assertThat(composed.segment("skills-index")).get().satisfies(d -> {
            assertThat(d.injected()).isFalse();
            assertThat(d.skipReason()).contains("无技能");
        });
        assertThat(composed.segment("skills")).get()
                .satisfies(d -> assertThat(d.injected()).isTrue());
    }

    @Test
    @DisplayName("记忆快照为空 → 尾段跳过且不留尾部空白；非空 → 位于全文最后")
    void memorySnapshotTailFollowsPresence() {
        ComposedPrompt without = composer.compose(context(fullCaps(), "   ", List.of()));
        assertThat(without.segment(PromptComposer.MEMORY_SNAPSHOT_ID)).get()
                .satisfies(d -> assertThat(d.injected()).isFalse());
        assertThat(without.text()).doesNotEndWith("\n");

        ComposedPrompt with = composer.compose(context(fullCaps(), "  记忆尾段内容  ", List.of()));
        assertThat(with.text()).endsWith("记忆尾段内容");
        assertThat(with.segment(PromptComposer.MEMORY_SNAPSHOT_ID)).get()
                .satisfies(d -> assertThat(d.charCount()).isEqualTo("记忆尾段内容".length()));
    }

    // =========================================================================
    // 模板渲染容错与确定性（R8 前缀稳定的基础）
    // =========================================================================

    @Test
    @DisplayName("同一上下文两次装配逐字节相同（R8 前缀稳定前提）")
    void composeShouldBeDeterministicForSameContext() {
        PromptContext context = context(fullCaps(), "记忆", List.of(skill("s", "d", "c")));

        String first = composer.compose(context).text();
        String second = composer.compose(context).text();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("renderVariables：已定义变量全部替换；未知变量原样保留（宁可见残留不静默丢内容）")
    void renderVariablesShouldSubstituteKnownAndPreserveUnknown() {
        Map<String, String> vars = Map.of("a", "值A", "b", "值B");

        assertThat(PromptComposer.renderVariables("{{a}}-{{ b }}-{{nope}}", vars))
                .isEqualTo("值A-值B-{{nope}}");
    }

    @Test
    @DisplayName("用户覆盖段含未知变量：装配不抛异常，残留进明细可见")
    void userOverrideWithUnknownVariableShouldNotBreakCompose(@TempDir Path overrideDir) throws IOException {
        Files.writeString(overrideDir.resolve("manifest.yaml"), """
                version: 1
                segments:
                  - id: custom
                    file: custom.md
                """, StandardCharsets.UTF_8);
        Files.writeString(overrideDir.resolve("custom.md"),
                "自定义段 {{cwd}} 与未知变量 {{oops}} 并存", StandardCharsets.UTF_8);

        PromptComposer custom = new PromptComposer(new PromptLoader(overrideDir));
        PromptContext context = context(fullCaps(), "", List.of());

        // compose 契约：任何输入都不抛异常；若违反本行将直接炸掉用例，无需额外包裹
        ComposedPrompt composed = custom.compose(context);

        // 已知变量正常替换，未知变量原样保留（残留可见，不静默丢内容）
        assertThat(composed.text()).contains("C:\\proj").contains("{{oops}}");
    }
}
