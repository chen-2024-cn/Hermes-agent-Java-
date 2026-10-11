package com.cyk.prompt;

import com.cyk.bean.Skill;
import com.cyk.capability.CapabilityRegistry;
import com.cyk.config.HermesConfig;
import com.cyk.prompt.ComposedPrompt.SegmentRender;
import com.cyk.security.SecurityContext;
import com.cyk.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>提示词装配矩阵测试（JH-SPEC-001 P0-3 AC1 / AC2 的主证据）</h1>
 *
 * <h2>要证的命题</h2>
 * <p>对<b>任意</b>能力组合，装配出的系统提示词都不得引用未注册的工具。
 * 这不是抽样检查——AC1 是安全性命题（模型据此调用不存在的工具会反复失败或编造结果），
 * 必须穷举。</p>
 *
 * <h2>两层覆盖（各自证明链路的不同环节）</h2>
 * <ul>
 *   <li><b>A 层·穷举装配</b>：6 个工具集的全部 $2^6=64$ 种在场组合 × 技能有/无 = 128 次装配。
 *       用<b>合成注册表</b>（独立实例，不触全局单例），因此能表达「RAG 已注册」这类
 *       测试环境无法靠真实 DB 达成的状态。证明：能力 → 指引注入 的映射无死角。</li>
 *   <li><b>B 层·真实接线</b>：走真实 {@code ToolRegistry.initialize(config)}，配置维度为
 *       {@code tools.terminal_enabled} × {@code tools.disabled_toolsets} 变体。
 *       证明：配置 → 注册 → 能力 → 提示词 的<b>全链路</b>一致（R7 判据不再是配置项）。
 *       此层 RAG 恒为关（{@code rag.enabled=false}，测试环境无 pgvector），
 *       「RAG 开」的状态由 A 层合成覆盖——两层合起来才是完整证明。</li>
 * </ul>
 *
 * <h2>同时锁定的性质</h2>
 * <ul>
 *   <li><b>前缀稳定</b>（P0-4 R8 前置）：恒注入四段构成所有组合的公共前缀，
 *       能力变化只在尾部增删——否则前缀缓存永远命中不了；</li>
 *   <li><b>无模板残留</b>：任何组合下装配结果都不含 {@code {{}}；</li>
 *   <li><b>确定性</b>：同上下文重复装配逐字节相同；</li>
 *   <li><b>明细可解释</b>：每个跳过段都有非空原因（/context 直接输出给用户看）。</li>
 * </ul>
 *
 * <p>测试纪律：B 层操作全局单例，{@link #tearDown()} 必须
 * {@code clearForTest()} + {@code resetForTest()} 复位，不污染其它测试类。</p>
 */
class PromptComposerMatrixTest {

    /** 工具集 → 该集内全部工具名（与生产各 Tool 类注册一致）。 */
    private static final Map<String, List<String>> TOOLSET_TOOLS = Map.of(
            "file_operations", List.of("grep_files", "read_file", "search_files", "write_file"),
            "memory", List.of("memory_delete", "memory_get", "memory_replace", "memory_save"),
            "skill", List.of("skill_manage"),
            "web", List.of("fetch_page", "web_search"),
            "terminal", List.of("run_command"),
            "rag", List.of("rag_delete", "rag_index", "rag_list", "rag_search"));

    /** 工具集 → 其指引段 id（用于断言「能力在场 ⇒ 指引注入」）。 */
    private static final Map<String, String> TOOLSET_TO_SEGMENT = Map.of(
            "memory", "memory",
            "skill", "skills",
            "web", "web-search",
            "terminal", "terminal-tool",
            "rag", "rag");

    /** 恒注入段（无前缀依赖，构成所有组合的公共前缀）。 */
    private static final List<String> ALWAYS_ON = List.of(
            "identity", "working-dir", "execution", "terminal-output", "capabilities");

    private static final List<String> ALL_TOOLSETS = List.copyOf(TOOLSET_TOOLS.keySet());

    private final PromptComposer composer = new PromptComposer(new PromptLoader(null));

    @AfterEach
    void tearDown() {
        ToolRegistry.clearForTest();
        SecurityContext.resetForTest();
    }

    // =========================================================================
    // A 层：穷举装配（64 组合 × 技能有/无）
    // =========================================================================

    @Test
    @DisplayName("AC1/AC2 穷举：64 种能力组合 × 技能有无，均不引用未注册工具")
    void allCapabilityPermutationsShouldReferenceOnlyRegisteredTools() {
        List<Skill> withSkills = List.of(PromptComposerTestSupport.skill(
                "demo-skill", "示例技能用途", "技能正文不得进入前缀"));
        int assemblies = 0;

        for (List<String> present : subsets(ALL_TOOLSETS)) {
            for (List<Skill> skills : List.of(List.<Skill>of(), withSkills)) {
                CapabilityRegistry caps = capabilitiesWith(present);
                ComposedPrompt composed = composer.compose(
                        PromptComposerTestSupport.contextWith(caps, "记忆尾段", skills));
                assemblies++;

                assertConsistent(composed, caps, present, skills, "记忆尾段");
            }
        }

        // 64 组合 × 2 技能状态；断言此数以证明「穷举」没有退化成少数几个用例
        assertThat(assemblies).isEqualTo(128);
    }

    /**
     * 单个组合的全部不变式（AC1/AC2 + 明细完整性 + 模板残留）。
     *
     * @param memory 装配时传入的记忆快照（需与实际上下文一致，末尾断言依赖它）
     */
    private void assertConsistent(ComposedPrompt composed, CapabilityRegistry caps,
                                  List<String> present, List<Skill> skills, String memory) {
        String text = composed.text();
        String label = "组合" + present + " 技能数=" + skills.size();

        // ① 一致性自检零违规（AC1 的机器判据）
        assertThat(PromptConsistencyChecker.check(composed, caps))
                .as("%s：提示词引用了未注册工具", label).isEmpty();

        // ② 缺席工具集的工具名逐一不在全文中（AC2 的一般化）
        for (Map.Entry<String, List<String>> e : TOOLSET_TOOLS.entrySet()) {
            if (present.contains(e.getKey())) {
                continue;
            }
            for (String toolName : e.getValue()) {
                assertThat(text)
                        .as("%s：工具集 %s 未注册却出现了 %s", label, e.getKey(), toolName)
                        .doesNotContain(toolName);
            }
        }

        // ③ 能力在场 ⇒ 对应指引段注入；缺席 ⇒ 跳过且给出原因
        for (Map.Entry<String, String> e : TOOLSET_TO_SEGMENT.entrySet()) {
            SegmentRender render = composed.segment(e.getValue()).orElseThrow();
            boolean presentHere = present.contains(e.getKey());
            assertThat(render.injected())
                    .as("%s：工具集 %s 在场=%s 但段 %s 注入=%s", label,
                            e.getKey(), presentHere, e.getValue(), render.injected())
                    .isEqualTo(presentHere);
            if (!render.injected()) {
                assertThat(render.skipReason()).as("%s：跳过原因必须可解释", label).isNotBlank();
            }
        }

        // ④ 技能索引：skill_manage 在场且有技能才注入；技能正文永不入前缀
        boolean skillToolPresent = present.contains("skill");
        assertThat(composed.segment("skills-index").orElseThrow().injected())
                .isEqualTo(skillToolPresent && !skills.isEmpty());
        assertThat(text).doesNotContain("技能正文不得进入前缀");

        // ⑤ 无模板残留 + 恒注入段全在场
        assertThat(text).as("%s：存在未替换的模板变量", label).doesNotContain("{{");
        for (String id : ALWAYS_ON) {
            assertThat(composed.segment(id).orElseThrow().injected())
                    .as("%s：恒注入段 %s 缺席", label, id).isTrue();
        }
        // ⑥ 记忆尾段：非空则必在末尾（动态尾段位置固定，保证静态前缀稳定）
        if (memory != null && !memory.isBlank()) {
            assertThat(text).as("%s：记忆尾段未落在末尾", label).endsWith(memory.strip());
        }
    }

    @Test
    @DisplayName("R8 前缀稳定：恒注入四段是所有 64 种能力组合的公共前缀")
    void alwaysOnSegmentsFormCommonPrefixAcrossAllPermutations() {
        // 能力变化只应影响尾部；若前缀随能力抖动，前缀缓存永远命中不了
        // （capabilities 段虽恒注入，但其内容随能力变化，故公共前缀止于它之前）
        String commonPrefix = composer.compose(PromptComposerTestSupport.contextWith(
                        capabilitiesWith(List.of()), "", List.of()))
                .text();
        int cut = commonPrefix.indexOf("当前可用能力（唯一权威清单）");
        assertThat(cut).as("capabilities 段标题应存在").isPositive();
        String stablePrefix = commonPrefix.substring(0, cut);
        assertThat(stablePrefix).isNotBlank().contains("你是Jhermes智能体");

        for (List<String> present : subsets(ALL_TOOLSETS)) {
            String text = composer.compose(PromptComposerTestSupport.contextWith(
                            capabilitiesWith(present), "", List.of()))
                    .text();
            assertThat(text).as("组合 %s 的开头偏离公共前缀", present).startsWith(stablePrefix);
        }
    }

    @Test
    @DisplayName("确定性：同一能力组合重复装配逐字节相同（128 次抽样验证）")
    void compositionShouldBeDeterministicAcrossPermutations() {
        for (List<String> present : subsets(ALL_TOOLSETS)) {
            CapabilityRegistry caps = capabilitiesWith(present);
            String first = composer.compose(
                    PromptComposerTestSupport.contextWith(caps, "记忆", List.of())).text();
            String second = composer.compose(
                    PromptComposerTestSupport.contextWith(caps, "记忆", List.of())).text();
            assertThat(second).as("组合 %s 装配不确定", present).isEqualTo(first);
        }
    }

    // =========================================================================
    // B 层：真实接线（配置 → 注册 → 能力 → 提示词）
    // =========================================================================

    @Test
    @DisplayName("真实接线：terminal_enabled × disabled_toolsets 变体下装配结果与注册实况一致")
    void realWiringShouldStayConsistentAcrossConfigVariants() {
        // 变体表：terminal 开关 × 黑名单（含单集、多集、拼写错误项）
        List<Object[]> variants = List.of(
                new Object[]{true, List.of()},
                new Object[]{false, List.of()},
                new Object[]{true, List.of("web")},
                new Object[]{true, List.of("memory", "skill")},
                new Object[]{true, List.of("terminal")},
                new Object[]{true, List.of("rag")},
                new Object[]{true, List.of("file_operation")}, // 拼写错误：只 WARN 不禁用
                new Object[]{true, List.of("web", "memory", "skill", "file_operations")});

        for (Object[] variant : variants) {
            boolean terminalEnabled = (boolean) variant[0];
            @SuppressWarnings("unchecked")
            List<String> disabled = (List<String>) variant[1];

            ToolRegistry.initialize(configWith(terminalEnabled, disabled));
            CapabilityRegistry caps = CapabilityRegistry.current();
            String label = "terminal_enabled=" + terminalEnabled + " disabled=" + disabled;

            // 断言注册实况本身符合配置意图（链路前半段）
            assertThat(caps.hasTool("run_command"))
                    .as("%s：终端工具在场性不符", label)
                    .isEqualTo(terminalEnabled && !disabled.contains("terminal"));
            assertThat(caps.hasToolset("web")).as(label).isEqualTo(!disabled.contains("web"));
            assertThat(caps.hasToolset("memory")).as(label).isEqualTo(!disabled.contains("memory"));
            assertThat(caps.hasToolset("skill")).as(label).isEqualTo(!disabled.contains("skill"));
            // 拼写错误项不禁用任何东西：file_operations 仍应在场
            assertThat(caps.hasToolset("file_operations")).as(label)
                    .isEqualTo(!disabled.contains("file_operations"));
            // rag.enabled=false → 测试环境恒不注册
            assertThat(caps.hasToolset("rag")).as(label).isFalse();

            // 装配结果与实况一致（链路后半段：AC1/AC2 的真实配置版）
            List<Skill> realSkills = List.of(PromptComposerTestSupport.skill("s", "d", "正文"));
            ComposedPrompt composed = composer.compose(PromptComposerTestSupport.contextWith(
                    caps, "", realSkills));

            assertConsistent(composed, caps, presentToolsetsOf(caps), realSkills, "");
            assertThat(PromptConsistencyChecker.check(composed, caps)).as(label).isEmpty();

            // 被禁能力的指引必须消失（不只是工具名，整段指引都不该教模型用）
            if (disabled.contains("web")) {
                assertThat(composed.text()).as(label).doesNotContain("网页搜索与信息获取");
            }
            if (disabled.contains("memory") || disabled.contains("skill")) {
                assertThat(composed.text()).as(label)
                        .doesNotContain("技能沉淀与复用")
                        .doesNotContain("你拥有跨会话持久记忆功能");
            }
        }
    }

    @Test
    @DisplayName("真实接线：满配（terminal 开、无黑名单）时全部条件指引齐备")
    void realWiringFullConfigShouldInjectAllGuidance() {
        ToolRegistry.initialize(configWith(true, List.of()));
        CapabilityRegistry caps = CapabilityRegistry.current();

        ComposedPrompt composed = composer.compose(PromptComposerTestSupport.contextWith(
                caps, "", List.of(PromptComposerTestSupport.skill("demo", "用途", "正文"))));

        String text = composed.text();
        assertThat(text).contains("终端命令执行（run_command）")   // terminal 在场
                .contains("网页搜索与信息获取")                    // web 在场
                .contains("你拥有跨会话持久记忆功能")               // memory 在场
                .contains("技能沉淀与复用");                        // skill 在场
        assertThat(text).doesNotContain("知识库检索与管理");        // rag 未注册（无 DB）
        assertThat(text).contains("- demo: 用途");                  // 技能索引（A19 已修）
        assertThat(PromptConsistencyChecker.check(composed, caps)).isEmpty();
    }

    // =========================================================================
    // 脚手架
    // =========================================================================

    /** 按「在场工具集列表」合成能力快照。 */
    private static CapabilityRegistry capabilitiesWith(List<String> presentToolsets) {
        Map<String, List<String>> subset = new LinkedHashMap<>();
        for (String toolset : presentToolsets) {
            subset.put(toolset, TOOLSET_TOOLS.get(toolset));
        }
        return PromptComposerTestSupport.capabilitiesOf(subset);
    }

    /** 从真实能力快照反推在场工具集（B 层复用 A 层的断言逻辑）。 */
    private static List<String> presentToolsetsOf(CapabilityRegistry caps) {
        List<String> present = new ArrayList<>();
        for (String toolset : TOOLSET_TOOLS.keySet()) {
            if (caps.hasToolset(toolset)) {
                present.add(toolset);
            }
        }
        return present;
    }

    /** 枚举全部子集（含空集），顺序确定以便失败可复现。 */
    private static List<List<String>> subsets(List<String> items) {
        List<List<String>> result = new ArrayList<>();
        int total = 1 << items.size();
        for (int mask = 0; mask < total; mask++) {
            List<String> subset = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(items.get(i));
                }
            }
            result.add(subset);
        }
        return result;
    }

    /** 构造 B 层用的纯内存配置（RAG 关、审计关：不连库、不写真实日志）。 */
    private static HermesConfig configWith(boolean terminalEnabled, List<String> disabledToolsets) {
        Map<String, Object> cfg = new HashMap<>();
        Map<String, Object> tools = new HashMap<>();
        tools.put("terminal_enabled", terminalEnabled);
        tools.put("disabled_toolsets", disabledToolsets);
        cfg.put("tools", tools);
        cfg.put("rag", Map.of("enabled", false));
        cfg.put("extract", Map.of("enabled", false));
        cfg.put("security", Map.of("audit_enabled", false));
        return new HermesConfig(null, cfg);
    }
}
