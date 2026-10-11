package com.cyk.security;

import java.util.List;

/**
 * <h1>命令策略决策结果（JH-SPEC-001 P0-2）</h1>
 *
 * <p>{@link CommandPolicy#evaluate} 的输出：一条命令的分级、命中的规则、
 * 理由、是否需确认，以及供审计与「同类放行」归组用的元数据。</p>
 *
 * <p>这是<b>纯分类结果</b>，不含任何交互——真正的放行/拒绝由调用方结合确认闸门
 * （{@link ConfirmationProvider}）、{@code --yolo}、非交互模式与审计模式来判定，
 * 使「怎么分级」与「怎么处置」解耦，两者都能独立测试。</p>
 *
 * @param level            最终分级（多段取最严 + 复合模式 + 越界升档后）
 * @param matchedRuleIds   命中的规则 id（含内部生成的合成规则如 workspace-boundary）
 * @param reason           面向用户的理由（取最严命中规则的理由）
 * @param segments         分段结果（供审计写入与用户展示）
 * @param normalizedCommand 归一化后的命令（去多余空白，供展示/审计；保留原文可读性）
 * @param allowGroupKey    「同类放行」归组键：可执行名 + 子命令 + 是否工作区内；
 *                         null 表示不适用会话放行（DANGEROUS/BLOCKED 不允许 a 放行）
 * @param unparseable      是否含无法可靠解析的段（审计与调试用）
 */
public record PolicyDecision(
        CommandLevel level,
        List<String> matchedRuleIds,
        String reason,
        List<CommandSegment> segments,
        String normalizedCommand,
        String allowGroupKey,
        boolean unparseable) {

    /** @return 是否被硬拦截（永不执行，任何标志不可绕过） */
    public boolean isBlocked() {
        return level == CommandLevel.BLOCKED;
    }

    /** @return 是否自动放行（只读白名单，无需确认） */
    public boolean isAutoAllowed() {
        return level == CommandLevel.READ_ONLY;
    }

    /** @return 是否需要确认（WRITE 或 DANGEROUS） */
    public boolean needsConfirmation() {
        return level.requiresConfirmation();
    }

    /** @return 是否需要「键入 yes」级别的强确认 */
    public boolean needsStrongConfirmation() {
        return level.requiresStrongConfirmation();
    }

    /** @return 是否允许「本会话内同类放行」（仅 WRITE，且 allowGroupKey 非空） */
    public boolean allowsSessionReuse() {
        return level == CommandLevel.WRITE && allowGroupKey != null && !allowGroupKey.isEmpty();
    }

    /** 构造一个 BLOCKED 决策。 */
    public static PolicyDecision blocked(List<String> ruleIds, String reason,
                                         List<CommandSegment> segments, String normalized) {
        return new PolicyDecision(CommandLevel.BLOCKED, ruleIds, reason, segments, normalized, null,
                segments != null && segments.stream().anyMatch(CommandSegment::unparseable));
    }
}
