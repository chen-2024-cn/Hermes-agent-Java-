package com.cyk.prompt;

import java.util.List;

/**
 * 提示词段的注入条件（{@code prompts/manifest.yaml} 中每段的 {@code requires} 声明）。
 *
 * <h2>为什么判据是「注册实况」而非配置（JH-SPEC-001 P0-3 R7，修复 C-01）</h2>
 * <p>改造前唯一一段条件注入（终端指引）判的是 {@code tools.terminal_enabled} 配置项，
 * 而工具是否真的可用还受 {@code tools.disabled_toolsets}、RAG 数据库连通性等影响——
 * 配置开着但工具没注册上时，提示词仍教模型去用一个不存在的工具（AC2 缺陷）。
 * 本 record 只描述「需要什么能力」，判定统一由 {@code PromptComposer} 拿
 * {@code CapabilityRegistry}（注册表派生）来做，指引与实况不可能脱节。</p>
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>{@code toolsets}：列出的<b>每个</b>工具集都至少注册了一个工具（AND）；</li>
 *   <li>{@code tools}：列出的<b>每个</b>工具名都真实注册（AND）——
 *       比工具集更精确：rag 工具集注册了但 run_command 被禁时，
 *       {@code requires.tools=[run_command]} 的段落照样不注入；</li>
 *   <li>{@code requireSkills}：为 true 时还要求本机技能数 ≥ 1（skills-index 段用，
 *       空索引注入只会白占 token）。</li>
 * </ul>
 *
 * @param toolsets      所需工具集名（不可变，可为空）
 * @param tools         所需工具名（不可变，可为空）
 * @param requireSkills 是否要求本地存在至少一个技能
 */
public record PromptRequirements(List<String> toolsets, List<String> tools, boolean requireSkills) {

    /** 恒注入条件（manifest 中缺省 requires 的段）。 */
    public static final PromptRequirements ALWAYS = new PromptRequirements(List.of(), List.of(), false);

    public PromptRequirements {
        // 防御式规范化：null → 空表；List.copyOf 冻结内容并拒绝 null 元素，
        // record 的不可变承诺由此在运行时兜底（调用方拿到后无法篡改）
        toolsets = toolsets == null ? List.of() : List.copyOf(toolsets);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 是否无任何条件（恒注入段）。 */
    public boolean isAlways() {
        return toolsets.isEmpty() && tools.isEmpty() && !requireSkills;
    }
}
