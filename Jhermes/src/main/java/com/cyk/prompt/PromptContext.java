package com.cyk.prompt;

import com.cyk.bean.Skill;
import com.cyk.capability.CapabilityRegistry;

import java.util.List;
import java.util.Objects;

/**
 * <h1>提示词装配上下文（PromptComposer 的唯一入参）</h1>
 *
 * <p>把「装配系统提示词所需的全部动态输入」收口为一个不可变值对象——
 * 深模块接口：composer 对外只有一个 {@code compose(PromptContext)} 方法，
 * 调用方（Agent）不需要知道内部分了几段、每段要什么变量；
 * 期 4 增加一致性自检、期 P0-4 增加预算度量时也只在此 record 上扩展，
 * 不改 Agent 与 composer 之间的调用形态。</p>
 *
 * <p>四项输入各有明确职责：</p>
 * <ul>
 *   <li>{@code capabilities} —— 注册实况（requires 判定 + {{capabilities}} 渲染，
 *       两处同源，AC1/AC2 的根基）；</li>
 *   <li>{@code workingDir} —— 目录快照（working-dir.md 的 4 个模板变量）；</li>
 *   <li>{@code memorySnapshot} —— 长期记忆预渲染文本（MemoryManager 产出，
 *       作为动态尾段拼接，非空才注入）；</li>
 *   <li>{@code skills} —— 本机技能清单（skills-index.md 的索引渲染与
 *       requireSkills 判定；SkillManager.listAll() 的原始 bean 列表，
 *       索引格式化是 {@link SkillIndexBuilder} 的职责，不在此处预处理）。</li>
 * </ul>
 *
 * @param capabilities    能力注册表快照（非 null）
 * @param workingDir      工作目录快照（非 null）
 * @param memorySnapshot  长期记忆快照文本（null 视为空串 = 无记忆）
 * @param skills          本机技能列表（null 视为空列表）
 */
public record PromptContext(CapabilityRegistry capabilities,
                            WorkingDirSnapshot workingDir,
                            String memorySnapshot,
                            List<Skill> skills) {

    public PromptContext {
        Objects.requireNonNull(capabilities, "capabilities 不能为 null（可用 CapabilityRegistry.from(空注册表) 表达无工具）");
        Objects.requireNonNull(workingDir, "workingDir 不能为 null");
        memorySnapshot = memorySnapshot == null ? "" : memorySnapshot;
        skills = skills == null ? List.of() : List.copyOf(skills);
    }
}
