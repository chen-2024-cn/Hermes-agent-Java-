package com.cyk.prompt;

import com.cyk.capability.CapabilityRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>系统提示词预算守卫（JH-SPEC-001 P0-3 期 6 / D-07 冻结线）</h1>
 *
 * <h2>为什么预算必须写进测试</h2>
 * <p>系统提示词是<b>每一轮请求都重发</b>的前缀：多写 1,000 字符，用户每一次提问都为它付费，
 * 且长上下文会稀释模型注意力（轻量模型上尤其明显）。但它没有任何编译期约束——
 * 谁都能顺手往 md 里加两句「顺便记得……」，半年后前缀悄悄膨胀 60% 而没人发现。
 * 把上限写成断言，膨胀就会以 CI 红灯的形式立刻暴露。</p>
 *
 * <h2>口径（与 Phase 0 基线 B2 严格可比）</h2>
 * <p>基线是在<b>满配 16 工具</b>下实测的：系统提示词共 8,402 字符，其中记忆快照占 2,524、
 * 已删除的「Available Tools」文本清单占 1,548。D-07 冻结线：
 * <b>T1 ≤ 基线的 80%</b>，即 ≤ 6,722 字符。RAG 关闭时的基线是 7,923 字符（≤ 6,338）。</p>
 *
 * <p>由于记忆快照因用户而异，本测试构造一份<b>与基线等长</b>（2,524 字符）的记忆样本注入，
 * 使「总字符」与基线口径一致、可直接比较；同时单独盯住
 * {@link ComposedPrompt#authoredChars()}（静态创作段）——那才是我们能控制的部分。</p>
 *
 * <h2>换算说明</h2>
 * <p>字符 → token 的经验系数取 B2 实测的 2.5 字符/token（中文提示词）。本测试用字符作硬判据
 * （精确、可复现）；token 侧的最终复核在 P0-4 用真实 {@code prompt_tokens} 完成（AC2），
 * 因为分词器行为随模型与供应商而变，不适合做 CI 硬断言。</p>
 */
class PromptBudgetTest {

    // =========================================================================
    // 基线与冻结线（数据来自 Phase 0 B2 实测，改动需同步 baseline-report.md）
    // =========================================================================

    /** B2 满配基线：旧版系统提示词总字符（含记忆快照与 Available Tools 清单）。 */
    private static final int BASELINE_FULLSCREEN_CHARS = 8_402;
    /** B2 满配基线中的记忆快照字符数（用于构造等长样本，保持口径可比）。 */
    private static final int BASELINE_MEMORY_CHARS = 2_524;
    /** B2 RAG 关闭时的基线总字符。 */
    private static final int BASELINE_RAG_OFF_CHARS = 7_923;
    /** D-07 冻结线：T1 ≤ 基线 80%。 */
    private static final double T1_RATIO_LIMIT = 0.80;
    /** B2 实测的中文字符/token 经验系数。 */
    private static final double CHARS_PER_TOKEN = 2.5;

    /** 满配（16 工具、6 工具集）的冻结上限：8402 × 0.80 = 6721.6 → 6721。 */
    private static final int FULL_CAP = (int) Math.floor(BASELINE_FULLSCREEN_CHARS * T1_RATIO_LIMIT);
    /** RAG 关闭时的冻结上限：7923 × 0.80 = 6338.4 → 6338。 */
    private static final int RAG_OFF_CAP = (int) Math.floor(BASELINE_RAG_OFF_CHARS * T1_RATIO_LIMIT);

    /**
     * 静态创作段（不含记忆尾段）的独立上限。
     *
     * <p>取值 = 满配上限减去基线记忆占比，即「记忆之外的部分」不得超过的规模；
     * 这是日常最容易悄悄膨胀的部分（往 md 里加句子），单独钉一条线更容易定位。</p>
     */
    private static final int AUTHORED_CAP = FULL_CAP - BASELINE_MEMORY_CHARS;

    /** 与生产一致的真实工具集构成（16 工具满配）。 */
    private static final Map<String, List<String>> FULL_TOOLSETS = new LinkedHashMap<>();

    static {
        FULL_TOOLSETS.put("file_operations", List.of("grep_files", "read_file", "search_files", "write_file"));
        FULL_TOOLSETS.put("memory", List.of("memory_delete", "memory_get", "memory_replace", "memory_save"));
        FULL_TOOLSETS.put("skill", List.of("skill_manage"));
        FULL_TOOLSETS.put("web", List.of("fetch_page", "web_search"));
        FULL_TOOLSETS.put("terminal", List.of("run_command"));
        FULL_TOOLSETS.put("rag", List.of("rag_delete", "rag_index", "rag_list", "rag_search"));
    }

    private final PromptComposer composer = new PromptComposer(new PromptLoader(null));

    // =========================================================================
    // 脚手架
    // =========================================================================

    private static CapabilityRegistry caps(Map<String, List<String>> toolsetTools) {
        return PromptComposerTestSupport.capabilitiesOf(toolsetTools);
    }

    /** 与基线记忆快照等长的样本（口径可比的关键：不能拿空记忆去比 8402）。 */
    private static String memorySample(int chars) {
        return "记忆".repeat(chars / 2);
    }

    private static com.cyk.bean.Skill skill(String name) {
        return PromptComposerTestSupport.skill(name, "一句用途描述", "正文不进前缀".repeat(50));
    }

    private ComposedPrompt compose(CapabilityRegistry capabilities, String memory, int skillCount) {
        List<com.cyk.bean.Skill> skills = new ArrayList<>();
        for (int i = 1; i <= skillCount; i++) {
            skills.add(skill(String.format("skill-%02d", i)));
        }
        return composer.compose(PromptComposerTestSupport.contextWith(capabilities, memory, skills));
    }

    private static int estimatedTokens(ComposedPrompt composed) {
        return (int) Math.ceil(composed.totalChars() / CHARS_PER_TOKEN);
    }

    // =========================================================================
    // 冻结线断言
    // =========================================================================

    @Test
    @DisplayName("满配（16 工具 + 基线等长记忆 + 1 技能）：总字符 ≤ 基线 80%")
    void fullConfigurationMustStayWithinFrozenBudget() {
        ComposedPrompt composed = compose(caps(FULL_TOOLSETS), memorySample(BASELINE_MEMORY_CHARS), 1);

        int actual = composed.totalChars();
        assertThat(actual)
                .as("满配提示词 %d 字符（占基线 %d 的 %.1f%%，≈%d tokens），超出 D-07 冻结线 %d 字符。"
                                + "回退手段：压缩 execution.md（合并去重后仍最大的静态段），不改语义只删重复表述",
                        actual, BASELINE_FULLSCREEN_CHARS, 100.0 * actual / BASELINE_FULLSCREEN_CHARS,
                        estimatedTokens(composed), FULL_CAP)
                .isLessThanOrEqualTo(FULL_CAP);
    }

    @Test
    @DisplayName("RAG 关闭：rag 段不注入，总字符 ≤ rag-off 基线 80%（AC2 的预算侧证据）")
    void ragOffMustStayWithinItsOwnFrozenBudget() {
        Map<String, List<String>> noRag = new LinkedHashMap<>(FULL_TOOLSETS);
        noRag.remove("rag");

        ComposedPrompt composed = compose(caps(noRag), memorySample(BASELINE_MEMORY_CHARS), 1);

        int actual = composed.totalChars();
        assertThat(actual)
                .as("RAG 关闭时 %d 字符（基线 %d 的 %.1f%%），上限 %d", actual,
                        BASELINE_RAG_OFF_CHARS, 100.0 * actual / BASELINE_RAG_OFF_CHARS, RAG_OFF_CAP)
                .isLessThanOrEqualTo(RAG_OFF_CAP);
        // rag 段确实缺席（预算收益来自真实的能力裁剪，而非别处省字）
        assertThat(composed.segment("rag")).get()
                .satisfies(d -> assertThat(d.injected()).isFalse());
        // 裁剪量应与旧版 rag 段规模相当（>800 字符），否则说明条件注入没真生效
        ComposedPrompt full = compose(caps(FULL_TOOLSETS), memorySample(BASELINE_MEMORY_CHARS), 1);
        assertThat(full.totalChars() - actual).isGreaterThan(800);
    }

    @Test
    @DisplayName("静态创作段单独设线：记忆之外的部分不得超上限")
    void authoredSegmentsMustStayWithinTheirOwnCap() {
        ComposedPrompt composed = compose(caps(FULL_TOOLSETS), memorySample(BASELINE_MEMORY_CHARS), 1);

        assertThat(composed.authoredChars())
                .as("静态段 %d 字符，上限 %d（= 满配上限 − 基线记忆占比）", composed.authoredChars(), AUTHORED_CAP)
                .isLessThanOrEqualTo(AUTHORED_CAP);
        // 口径精确自洽：全文 = 静态前缀 + 段间分隔(\n\n) + 记忆尾段。
        // 这是 authoredText/totalChars 两个度量不漂移的硬约束：日后有人把记忆
        // 插到中间、或改了分隔符，本断言会立即失配（前缀稳定性也会被破坏）
        int memoryChars = composed.segment(PromptComposer.MEMORY_SNAPSHOT_ID).orElseThrow().charCount();
        assertThat(composed.totalChars())
                .as("全文应等于静态前缀 + 2 字符分隔 + 记忆尾段")
                .isEqualTo(composed.authoredChars() + 2 + memoryChars);
    }

    @Test
    @DisplayName("技能库膨胀防护：30 个技能（索引上限）时仍在冻结线内")
    void thirtySkillsMustNotBreakBudget() {
        // 索引上限 30 条正是为此设的：技能重度用户不能让索引吃光 T1 预算
        ComposedPrompt composed = compose(caps(FULL_TOOLSETS), memorySample(BASELINE_MEMORY_CHARS), 30);

        assertThat(composed.totalChars())
                .as("30 技能时 %d 字符，仍须 ≤ %d", composed.totalChars(), FULL_CAP)
                .isLessThanOrEqualTo(FULL_CAP);
        // 相对单技能，索引膨胀量应有限（30 条 × (名称+一句描述) ≈ 数百字符级）
        ComposedPrompt single = compose(caps(FULL_TOOLSETS), memorySample(BASELINE_MEMORY_CHARS), 1);
        assertThat(composed.authoredChars() - single.authoredChars())
                .as("索引从 1 条增至 30 条的膨胀量")
                .isLessThan(1_500);
        assertThat(composed.text()).contains("已截断").doesNotContain("正文不进前缀");
    }

    @Test
    @DisplayName("最小能力配置：只剩文件工具时提示词显著收缩（条件注入的收益可量化）")
    void minimalCapabilitiesShouldShrinkPromptSubstantially() {
        ComposedPrompt minimal = compose(caps(Map.of("file_operations", List.of("read_file"))), "", 0);
        ComposedPrompt full = compose(caps(FULL_TOOLSETS), "", 0);

        assertThat(minimal.totalChars())
                .as("最小配置 %d vs 满配 %d", minimal.totalChars(), full.totalChars())
                .isLessThan(full.totalChars() * 2 / 3);
        // 收缩来自 5 个条件段全部缺席
        assertThat(minimal.skippedSegments())
                .extracting(ComposedPrompt.SegmentRender::id)
                .contains("memory", "terminal-tool", "web-search", "rag", "skills", "skills-index");
    }

    @Test
    @DisplayName("token 估算记录（P0-4 用真实 prompt_tokens 复核的对照锚点）")
    void tokenEstimateShouldBeRecordedForLaterVerification() {
        ComposedPrompt composed = compose(caps(FULL_TOOLSETS), memorySample(BASELINE_MEMORY_CHARS), 1);

        int tokens = estimatedTokens(composed);
        int baselineTokens = (int) Math.ceil(BASELINE_FULLSCREEN_CHARS / CHARS_PER_TOKEN);

        assertThat(tokens)
                .as("估算 %d tokens（基线估算 %d），P0-4 将以真实 prompt_tokens 复核", tokens, baselineTokens)
                .isLessThanOrEqualTo((int) Math.floor(baselineTokens * T1_RATIO_LIMIT) + 1);
        assertThat(tokens).isPositive();
    }

    @Test
    @DisplayName("预算明细可审计：每个注入段的字符数都被记录（/context 的数据源）")
    void perSegmentBudgetShouldBeAuditable() {
        ComposedPrompt composed = compose(caps(FULL_TOOLSETS), memorySample(BASELINE_MEMORY_CHARS), 1);

        assertThat(composed.segments()).isNotEmpty()
                .allSatisfy(d -> {
                    assertThat(d.id()).isNotBlank();
                    assertThat(d.charCount()).isNotNegative();
                    if (d.injected()) {
                        assertThat(d.charCount()).as("注入段 %s 字符数必须 > 0", d.id()).isPositive();
                    } else {
                        assertThat(d.charCount()).as("跳过段 %s 字符数必须为 0", d.id()).isZero();
                        assertThat(d.skipReason()).as("跳过段 %s 必须给可解释原因", d.id()).isNotBlank();
                    }
                });
        // 单段上限：任何静态段不得独自吃掉预算的四分之一以上（防某一段悄悄膨胀）；
        // 不钉「最大段必须是哪个」——文案重心会随需求变化，那是脆弱断言
        assertThat(composed.injectedSegments())
                .filteredOn(d -> !d.id().equals(PromptComposer.MEMORY_SNAPSHOT_ID))
                .allSatisfy(d -> assertThat(d.charCount())
                        .as("静态段 %s 过大", d.id()).isLessThan(1_000));
        // 期 6 压缩成果上锁：execution 已由 1010 降到 ~736，不得回流
        assertThat(composed.segment("execution").orElseThrow().charCount()).isLessThanOrEqualTo(800);
        // 最大静态段应为已知段之一（新增巨段时本断言会提醒重新评估预算）
        ComposedPrompt.SegmentRender largest = composed.injectedSegments().stream()
                .filter(d -> !d.id().equals(PromptComposer.MEMORY_SNAPSHOT_ID))
                .max(java.util.Comparator.comparingInt(ComposedPrompt.SegmentRender::charCount))
                .orElseThrow();
        assertThat(largest.id()).isIn("rag", "execution", "terminal-tool");
    }
}
