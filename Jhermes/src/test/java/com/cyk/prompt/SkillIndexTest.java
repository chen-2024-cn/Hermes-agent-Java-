package com.cyk.prompt;

import com.cyk.bean.Skill;
import com.cyk.capability.CapabilityRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>技能索引测试（JH-SPEC-001 P0-3 R9 / AC6）</h1>
 *
 * <p>AC6 的核心是三条不可妥协的性质：</p>
 * <ol>
 *   <li><b>名称与描述在场</b>——模型据此判断「有没有现成方法可用」；</li>
 *   <li><b>正文绝不在场</b>——正文动辄数千字符且每轮重发，全量入前缀就是 token 雪球
 *       （改造前 SkillMatcher 的做法）；</li>
 *   <li><b>条数有上限</b>——技能重度用户不能让索引吃光 T1 预算。</li>
 * </ol>
 *
 * <p>另需覆盖 A19 缺陷的根治：旧实现按「恢复的历史文本」匹配，新会话历史为空
 * → 恒不注入任何技能；新实现<b>无条件</b>给出索引，故空历史场景下技能依然可见
 *（由 {@link PromptComposerMatrixTest} 在装配层交叉验证）。</p>
 */
class SkillIndexTest {

    private static Skill skill(String name, String description, String content) {
        return PromptComposerTestSupport.skill(name, description, content);
    }

    // =========================================================================
    // 基线与空输入
    // =========================================================================

    @Test
    @DisplayName("空/null 技能清单 → 空索引（composer 据此跳过整段）")
    void emptyOrNullSkillsProduceEmptyIndex() {
        assertThat(SkillIndexBuilder.build(null)).isEmpty();
        assertThat(SkillIndexBuilder.build(List.of())).isEmpty();
    }

    @Test
    @DisplayName("单技能：一行「- 名称: 描述」，且绝不含正文")
    void singleSkillRendersNameAndDescriptionOnly() {
        String index = SkillIndexBuilder.build(List.of(
                skill("deploy-spring-boot", "一键部署 Spring Boot 到测试环境",
                        "正文内容：第一步执行 mvn package，第二步……（数千字符）")));

        assertThat(index).isEqualTo("- deploy-spring-boot: 一键部署 Spring Boot 到测试环境");
        assertThat(index).doesNotContain("正文内容").doesNotContain("mvn package");
    }

    @Test
    @DisplayName("多技能按名称字典序（与插入顺序无关，保证前缀稳定）")
    void skillsAreSortedByNameRegardlessOfInsertionOrder() {
        List<Skill> shuffled = List.of(
                skill("zeta-skill", "z", ""),
                skill("alpha-skill", "a", ""),
                skill("mid-skill", "m", ""));

        List<String> lines = SkillIndexBuilder.build(shuffled).lines().toList();

        assertThat(lines).containsExactly(
                "- alpha-skill: a",
                "- mid-skill: m",
                "- zeta-skill: z");
    }

    // =========================================================================
    // 描述归一化（索引条目必须单行，否则破坏结构与预算）
    // =========================================================================

    @Test
    @DisplayName("描述里的换行被压平为空格：单技能仍占一行")
    void multilineDescriptionIsFlattenedToOneLine() {
        String index = SkillIndexBuilder.build(List.of(
                skill("multi", "第一行\n第二行\r\n第三行", "")));

        assertThat(index.lines().count()).isEqualTo(1);
        assertThat(index).isEqualTo("- multi: 第一行 第二行 第三行");
    }

    @Test
    @DisplayName("超长描述截断到 60 字符并加省略号（预算不被单条描述吃掉）")
    void longDescriptionIsTruncated() {
        String index = SkillIndexBuilder.build(List.of(
                skill("long", "x".repeat(SkillIndexBuilder.MAX_DESCRIPTION_CHARS + 100), "")));

        assertThat(index).isEqualTo("- long: "
                + "x".repeat(SkillIndexBuilder.MAX_DESCRIPTION_CHARS) + "…");
        assertThat(index.length()).isLessThan(80);
    }

    @Test
    @DisplayName("描述缺失/空白与名称缺失都安全降级，不抛异常不产生 null 字样")
    void missingDescriptionOrNameDegradeGracefully() {
        assertThat(SkillIndexBuilder.build(List.of(skill("no-desc", null, ""))))
                .isEqualTo("- no-desc: ");
        assertThat(SkillIndexBuilder.build(List.of(skill("blank", "   ", ""))))
                .isEqualTo("- blank: ");
        assertThat(SkillIndexBuilder.build(List.of(skill(null, "有描述但无名称", ""))))
                .isEqualTo("- (unnamed): 有描述但无名称");
    }

    // =========================================================================
    // 条数上限（预算守卫）
    // =========================================================================

    @Test
    @DisplayName("超过 30 条只渲染前 30 + 截断提示（含总数与可操作的补救指引）")
    void entriesAreCappedAtThirtyWithTruncationNotice() {
        List<Skill> many = new ArrayList<>();
        IntStream.rangeClosed(1, 45)
                .forEach(i -> many.add(skill(String.format("s%02d", i), "d" + i, "")));

        String index = SkillIndexBuilder.build(many);
        List<String> lines = index.lines().toList();

        // 30 条索引 + 1 条截断提示
        assertThat(lines).hasSize(SkillIndexBuilder.MAX_ENTRIES + 1);
        assertThat(lines.get(SkillIndexBuilder.MAX_ENTRIES))
                .contains("共 45 项").contains("已截断").contains("skill_manage list");
        // 字典序前 30 条保留，其余丢弃
        assertThat(lines.get(0)).isEqualTo("- s01: d1");
        assertThat(lines.get(SkillIndexBuilder.MAX_ENTRIES - 1)).isEqualTo("- s30: d30");
        assertThat(index).doesNotContain("- s31:");
    }

    @Test
    @DisplayName("恰好 30 条不触发截断提示")
    void exactlyThirtyEntriesProduceNoTruncationNotice() {
        List<Skill> exact = new ArrayList<>();
        IntStream.rangeClosed(1, SkillIndexBuilder.MAX_ENTRIES)
                .forEach(i -> exact.add(skill(String.format("s%02d", i), "d" + i, "")));

        String index = SkillIndexBuilder.build(exact);

        assertThat(index.lines()).hasSize(SkillIndexBuilder.MAX_ENTRIES);
        assertThat(index).doesNotContain("已截断");
    }

    // =========================================================================
    // 字符预算护栏（期 6 实测缺陷：只限条数时限不住长描述）
    // =========================================================================

    @Test
    @DisplayName("描述长时自动少列：索引正文恒不超字符预算，且给出截断提示")
    void longDescriptionsAreLimitedByCharBudgetNotJustCount() {
        // 30 个满长描述（每条被截到 60 字）：条数上限放得过，字符预算必须拦住
        List<Skill> verbose = new ArrayList<>();
        IntStream.rangeClosed(1, SkillIndexBuilder.MAX_ENTRIES).forEach(i ->
                verbose.add(skill(String.format("verbose-skill-%02d", i), "x".repeat(200), "")));

        String index = SkillIndexBuilder.build(verbose);

        assertThat(index.length())
                .as("索引正文 %d 字符，预算 %d", index.length(), SkillIndexBuilder.MAX_BODY_CHARS)
                .isLessThanOrEqualTo(SkillIndexBuilder.MAX_BODY_CHARS);
        assertThat(index.lines().count())
                .as("长描述下应只列出部分条目而非满 30 条")
                .isLessThan(SkillIndexBuilder.MAX_ENTRIES);
        assertThat(index).contains("已截断")
                .contains("共 " + SkillIndexBuilder.MAX_ENTRIES + " 项")
                .contains("skill_manage list");
    }

    @Test
    @DisplayName("各种技能规模下索引都不超预算，且截断提示与实际列出的条数自洽")
    void charBudgetHoldsAcrossSkillCounts() {
        for (int count : List.of(1, 3, 8, 20, 30, 60)) {
            List<Skill> skills = new ArrayList<>();
            IntStream.rangeClosed(1, count).forEach(i ->
                    skills.add(skill("技能-" + i, "描述内容".repeat(20), "")));

            String index = SkillIndexBuilder.build(skills);
            List<String> rows = index.lines()
                    .filter(line -> !line.startsWith("…等共 "))
                    .toList();
            boolean truncated = index.contains("已截断");

            assertThat(index.length())
                    .as("%d 个技能时索引 %d 字符超预算", count, index.length())
                    .isLessThanOrEqualTo(SkillIndexBuilder.MAX_BODY_CHARS);
            // 至少列出首条（预算再紧也不能让索引空掉，否则技能对模型不可见）
            assertThat(index).contains("技能-1:");
            // 自洽：截断提示当且仅当真有未列出的条目，且总数写得对
            if (truncated) {
                assertThat(rows.size()).isLessThan(count);
                assertThat(index).contains("共 " + count + " 项").contains("skill_manage list");
            } else {
                assertThat(rows).as("%d 个技能未截断时应全部列出", count).hasSize(count);
            }
        }
    }

    @Test
    @DisplayName("截断提示自身计入预算：卡在边界时不会因预留不当反而超线")
    void truncationHintIsAccountedInBudget() {
        // 构造刚好卡在预算边界的规模（短名 + 中等描述）：
        // 若未为提示行预留空间，累加后会超 MAX_BODY_CHARS
        for (int count : List.of(18, 20, 22, 24, 26, 28, 30)) {
            List<Skill> skills = new ArrayList<>();
            IntStream.rangeClosed(1, count).forEach(i ->
                    skills.add(skill(String.format("s%02d", i), "一句用途描述信息", "")));

            String index = SkillIndexBuilder.build(skills);

            assertThat(index.length())
                    .as("%d 条时索引 %d 字符超预算", count, index.length())
                    .isLessThanOrEqualTo(SkillIndexBuilder.MAX_BODY_CHARS);
            if (index.contains("已截断")) {
                // 提示行存在时它自己也在预算内（而不是溢出后才补上）
                String hint = index.lines().filter(l -> l.startsWith("…等共 ")).findFirst().orElseThrow();
                assertThat(index.length() - hint.length()).isLessThanOrEqualTo(
                        SkillIndexBuilder.MAX_BODY_CHARS - hint.length());
            }
        }
    }

    // =========================================================================
    // 装配层联动（AC6 的「新会话也可见」证据）
    // =========================================================================

    @Test
    @DisplayName("有技能 + skill_manage 已注册 → 索引段装入结果含名称与描述、不含正文")
    void indexReachesAssembledPromptWithoutBodies() {
        CapabilityRegistry caps = PromptComposerTestSupport.fullCapabilities();
        List<Skill> skills = List.of(
                skill("code-review", "按团队规范审查 Java 代码",
                        "正文（超长）：" + "review ".repeat(400)),
                skill("db-migrate", "生成 Flyway 迁移脚本", "正文B"));

        ComposedPrompt composed = new PromptComposer(new PromptLoader(null)).compose(
                PromptComposerTestSupport.contextWith(caps, "", skills));

        String text = composed.text();
        assertThat(text).contains("- code-review: 按团队规范审查 Java 代码")
                .contains("- db-migrate: 生成 Flyway 迁移脚本")
                .contains("已沉淀的技能索引");
        assertThat(text).doesNotContain("正文（超长）").doesNotContain("正文B");
        assertThat(composed.segment("skills-index")).get()
                .satisfies(d -> assertThat(d.injected()).isTrue());
        // A19 根治证明：新会话（无历史消息）技能依然可见
        assertThat(text).contains("code-review");
    }

    @Test
    @DisplayName("有技能但 skill_manage 未注册 → 索引不入提示词（引用不可用工具比不引用更糟）")
    void indexIsSkippedWhenSkillToolUnregistered() {
        CapabilityRegistry caps = PromptComposerTestSupport.capabilitiesOf(
                java.util.Map.of("file_operations", List.of("read_file")));

        ComposedPrompt composed = new PromptComposer(new PromptLoader(null)).compose(
                PromptComposerTestSupport.contextWith(caps, "",
                        List.of(skill("orphan-skill", "描述", "正文"))));

        assertThat(composed.text()).doesNotContain("orphan-skill").doesNotContain("已沉淀的技能索引");
        assertThat(composed.segment("skills-index")).get()
                .satisfies(d -> assertThat(d.injected()).isFalse());
    }

    @Test
    @DisplayName("索引内容自身通过一致性自检（skill_manage 已注册 → 无违规）")
    void indexContentShouldPassConsistencyCheck() {
        CapabilityRegistry caps = PromptComposerTestSupport.fullCapabilities();
        ComposedPrompt composed = new PromptComposer(new PromptLoader(null)).compose(
                PromptComposerTestSupport.contextWith(caps, "",
                        List.of(skill("demo", "示例技能", "正文"))));

        assertThat(PromptConsistencyChecker.check(composed, caps)).isEmpty();
    }
}
