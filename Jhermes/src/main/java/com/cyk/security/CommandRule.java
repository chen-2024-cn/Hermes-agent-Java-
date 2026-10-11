package com.cyk.security;

import java.util.List;
import java.util.Locale;

/**
 * <h1>命令策略规则（JH-SPEC-001 P0-2 R3）</h1>
 *
 * <p>规则是<b>结构化匹配器</b>而非整串正则——这是规范 R3 的明确要求，也是修复
 * 「字符串正则易绕过 + 引号内文本被当命令」（更正项 C-02）的关键。
 * 按「可执行名 + 子命令 + 标志」匹配，比整串正则更难被空格/引号/大小写绕过。</p>
 *
 * <h2>匹配语义（各条件为 AND，条件内的候选为 OR）</h2>
 * <ol>
 *   <li>{@code platforms} 非空 → 当前平台必须在其中（{@code windows/unix/any}）；</li>
 *   <li>{@code exes} 非空 → 段的归一化可执行名必须命中其一；</li>
 *   <li>{@code subs} 非空 → 段的子命令必须命中其一；</li>
 *   <li>{@code flagsAny} 非空 → 段必须至少含其中一个标志；</li>
 *   <li>{@code flagsNone} 非空 → 段<b>不得</b>含其中任一标志（命中则本规则失配）——
 *       用于只读白名单排除危险变体，如 {@code find} 排除 {@code -delete/-exec}、
 *       {@code sed} 排除 {@code -i}；</li>
 *   <li>{@code argsContain} 非空 → 段的参数/引号内容必须包含其中一个子串——
 *       用于识别特定目标（如 {@code dd} 且含 {@code if=}）。</li>
 * </ol>
 *
 * <p>全部条件满足才算命中。READ_ONLY 级规则必须配 {@code flagsNone} 或精确的
 * {@code subs} 约束，绝不能是裸的可执行名（否则 {@code git} 整体成只读白名单，
 * {@code git push -f} 就漏过了）——规范 R6：只读是「白名单 + 参数约束」。</p>
 *
 * @param id          规则唯一标识，用于审计与「命中规则 id」展示
 * @param platforms   适用平台：windows / unix / any；空表示不限
 * @param exes        可执行名候选（归一化后匹配，小写）
 * @param subs        子命令候选；空表示不限子命令
 * @param flagsAny    「命中任一」的标志集合（已归一化，含展开的短选项）
 * @param flagsNone   「命中即失配」的标志集合（白名单排除危险变体）
 * @param argsContain 参数必须包含的子串之一（识别特定目标）
 * @param level       命中后的分级
 * @param reason      面向用户的理由（审计与确认闸门展示）
 */
public record CommandRule(
        String id,
        List<String> platforms,
        List<String> exes,
        List<String> subs,
        List<String> flagsAny,
        List<String> flagsNone,
        List<String> argsContain,
        CommandLevel level,
        String reason) {

    /** 判定当前规则是否适用于给定运行平台。 */
    public boolean appliesToPlatform(String currentPlatform) {
        if (platforms == null || platforms.isEmpty()) {
            return true;
        }
        for (String p : platforms) {
            if (p == null) {
                continue;
            }
            String lp = p.toLowerCase(Locale.ROOT);
            if ("any".equals(lp) || lp.equals(currentPlatform)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本规则是否命中给定段。
     *
     * @param seg              已归一化的命令段
     * @param currentPlatform  当前平台（windows / unix）
     * @return 命中返回 true
     */
    public boolean matches(CommandSegment seg, String currentPlatform) {
        if (seg == null || seg.unparseable() || seg.executable().isEmpty()) {
            return false;
        }
        if (!appliesToPlatform(currentPlatform)) {
            return false;
        }
        if (notEmpty(exes) && !containsIgnoreCase(exes, seg.executable())) {
            return false;
        }
        if (notEmpty(subs)) {
            String sub = seg.subcommand();
            if (sub == null || !containsIgnoreCase(subs, sub)) {
                return false;
            }
        }
        if (notEmpty(flagsAny) && !anyPresent(seg.flags(), flagsAny)) {
            return false;
        }
        if (notEmpty(flagsNone) && anyPresent(seg.flags(), flagsNone)) {
            return false;
        }
        if (notEmpty(argsContain) && !anyArgContains(seg, argsContain)) {
            return false;
        }
        return true;
    }

    /** 段的标志集合里是否出现候选中的任一个（忽略大小写）。 */
    private static boolean anyPresent(List<String> segFlags, List<String> candidates) {
        if (segFlags == null || segFlags.isEmpty()) {
            return false;
        }
        for (String f : segFlags) {
            for (String c : candidates) {
                if (c != null && c.equalsIgnoreCase(f)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 段的参数或引号内容是否包含候选子串之一（识别 dd if= 、chmod 777 等特定目标）。 */
    private static boolean anyArgContains(CommandSegment seg, List<String> candidates) {
        List<String> haystack = seg.pathCandidates();
        List<String> all = new java.util.ArrayList<>(haystack);
        all.addAll(seg.args());
        for (String text : all) {
            if (text == null) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (String c : candidates) {
                if (c != null && !c.isBlank() && lower.contains(c.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean notEmpty(List<String> list) {
        return list != null && !list.isEmpty();
    }

    private static boolean containsIgnoreCase(List<String> candidates, String value) {
        String v = value.toLowerCase(Locale.ROOT);
        for (String c : candidates) {
            if (c != null && c.toLowerCase(Locale.ROOT).equals(v)) {
                return true;
            }
        }
        return false;
    }
}
