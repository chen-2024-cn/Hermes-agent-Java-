package com.cyk.security;

import java.util.List;

/**
 * <h1>命令分段结果（JH-SPEC-001 P0-2 R1）</h1>
 *
 * <p>一条 shell 命令字符串可能包含多段（{@code echo a; rm -rf ~}），
 * 甚至嵌套子命令（{@code cmd /c del /s C:\}）。分类必须作用在<b>段</b>上而非整串上，
 * 这正是改造前「整串小写化后跑正则」的两个失败模式的解药：</p>
 * <ul>
 *   <li><b>误杀</b>：{@code grep -r "rm -rf /" .} 里引号中的文本被当成命令 → 现在
 *       引号内内容进 {@link #quotedLiterals()}，绝不参与可执行名识别；</li>
 *   <li><b>漏过</b>：{@code echo hi; rm -rf ~} 的第二段在旧规则下匹配不到
 *       （旧正则要求 {@code rm} 后紧跟 flag 与路径，但整串里有前缀噪音）→ 现在逐段判定。</li>
 * </ul>
 *
 * @param rawText         本段原始文本（未去引号），用于展示给用户与写入审计
 * @param executable      归一化后的可执行名：去路径、去常见扩展名、小写。
 *                        空串表示本段没有可识别的可执行名
 * @param rawExecutable   可执行名原样（含路径），用于展示
 * @param subcommand      首个位置参数（不以 {@code -} / {@code /} 开头），如 git 的 push；无则为 null
 * @param flags           归一化后的标志集合：小写化、Unix 合并短选项已展开
 *                        （{@code -rf} 同时产出 {@code -rf}、{@code -r}、{@code -f}），
 *                        Windows 风格 {@code /s} 原样保留
 * @param args            除可执行名外的全部参数（保留引号内原样文本），用于路径越界检测
 * @param quotedLiterals  所有引号包裹的字面量内容（<b>数据</b>，不参与命令识别，
 *                        但参与路径与敏感特征检测）
 * @param depth           嵌套深度：0 = 顶层，1 = 由 {@code cmd /c} 等壳内层解析而来
 * @param unparseable     无法可靠解析（变量作命令名、{@code -EncodedCommand} 解不开、
 *                        动态拼接等）。R2 要求此类<b>至少按 WRITE</b>，绝不允许默认放行
 * @param unparseableReason 不可解析的原因，展示给用户与写入审计，便于排查误判
 */
public record CommandSegment(
        String rawText,
        String executable,
        String rawExecutable,
        String subcommand,
        List<String> flags,
        List<String> args,
        List<String> quotedLiterals,
        int depth,
        boolean unparseable,
        String unparseableReason) {

    /** 构造一个「不可解析」的段。 */
    public static CommandSegment unparseable(String rawText, int depth, String reason) {
        return new CommandSegment(rawText, "", "", null, List.of(), List.of(), List.of(),
                depth, true, reason);
    }

    /** 构造一个空白段（例如连续的 {@code ;;} 产生的空片段），分类时应直接跳过。 */
    public static CommandSegment empty(String rawText, int depth) {
        return new CommandSegment(rawText, "", "", null, List.of(), List.of(), List.of(),
                depth, false, null);
    }

    /** @return 本段是否为空（无可执行名、无参数、无引号内容） */
    public boolean isBlank() {
        return executable.isEmpty() && args.isEmpty() && quotedLiterals.isEmpty();
    }

    /** @return 参与路径检测的全部文本：参数 + 引号内字面量 */
    public List<String> pathCandidates() {
        if (args.isEmpty()) {
            return quotedLiterals;
        }
        if (quotedLiterals.isEmpty()) {
            return args;
        }
        return java.util.stream.Stream.concat(args.stream(), quotedLiterals.stream()).toList();
    }
}
