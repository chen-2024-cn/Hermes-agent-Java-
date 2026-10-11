package com.cyk.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/**
 * <h1>命令策略引擎（JH-SPEC-001 P0-2 R1/R2/R6/R9）</h1>
 *
 * <p>流水线：<b>规范化 → 分段 → 分类 → 决策</b>（R1）。分段由 {@link CommandSegmenter}
 * 完成，规则匹配由 {@link SecurityRuleSet} 完成，本类负责把它们编排成一条命令的最终分级。</p>
 *
 * <h2>关键设计原则</h2>
 * <ol>
 *   <li><b>默认保守</b>（R2/R6）：未命中任何规则的<b>已知命令</b>按 WRITE；
 *       无法可靠解析（变量作命令名、{@code -EncodedCommand} 解码失败、{@code eval}）按至少 WRITE；
 *       <b>绝不默认放行</b>。READ_ONLY 只能来自显式白名单规则的命中。</li>
 *   <li><b>多段取最严</b>：{@code echo hi; rm -rf ~} 里第二段是 BLOCKED，整条即 BLOCKED。</li>
 *   <li><b>路径语义精确判定</b>：递归删除的灾难目标（根/家/盘/绝对路径）→ BLOCKED；
 *       越出工作区 → 升一档（R9）。由 {@link WorkspacePathAnalyzer} 提供，不用子串匹配。</li>
 *   <li><b>复合模式</b>：跨段的「下载管道给 shell」等单段规则无法表达，在此做整命令级检测。</li>
 * </ol>
 *
 * <p>本类<b>不做任何交互、不执行命令、不落审计</b>——只输出 {@link PolicyDecision}。
 * 处置由 {@code TerminalTool} 结合确认闸门与审计日志判定（职责分离，均可独立测试）。</p>
 */
public class CommandPolicy {

    private static final Logger logger = LoggerFactory.getLogger(CommandPolicy.class);

    /** 删除类可执行名（触发灾难目标精确判定）。 */
    private static final Set<String> DELETE_EXES = Set.of(
            "rm", "del", "erase", "rd", "rmdir", "remove-item", "ri", "unlink");

    /** 表示「递归/强制」的标志（命中任一即视为破坏性删除语境）。 */
    private static final Set<String> RECURSIVE_FORCE_FLAGS = Set.of(
            "-r", "-f", "-rf", "-fr", "-recurse", "-force", "/s", "/q", "-delete");

    /** 网络抓取类可执行名（与 shell 组合 = 下载即执行）。 */
    private static final Set<String> NETWORK_FETCH_EXES = Set.of(
            "curl", "wget", "iwr", "invoke-webrequest", "invoke-restmethod", "irm", "fetch");

    /** shell / 解释器类可执行名（下载管道给它们 = 远程代码执行）。 */
    private static final Set<String> SHELL_INTERPRETERS = Set.of(
            "sh", "bash", "zsh", "ksh", "dash", "csh", "powershell", "pwsh",
            "python", "python3", "perl", "ruby", "node", "iex", "invoke-expression", "cmd");

    private final SecurityRuleSet ruleSet;
    private final CommandSegmenter segmenter;
    private final Path workspace;

    /** 审计/排查模式：只分类与记录，不拦截（{@code --policy=audit}，R 风险缓解）。 */
    private final boolean auditOnly;

    public CommandPolicy(SecurityRuleSet ruleSet) {
        this(ruleSet, new CommandSegmenter(), defaultWorkspace(), false);
    }

    public CommandPolicy(SecurityRuleSet ruleSet, boolean auditOnly) {
        this(ruleSet, new CommandSegmenter(), defaultWorkspace(), auditOnly);
    }

    CommandPolicy(SecurityRuleSet ruleSet, CommandSegmenter segmenter, Path workspace, boolean auditOnly) {
        this.ruleSet = ruleSet;
        this.segmenter = segmenter;
        this.workspace = workspace;
        this.auditOnly = auditOnly;
    }

    /** 默认工作区 = 进程启动目录（用户运行 Jhermes 的位置）。 */
    private static Path defaultWorkspace() {
        try {
            return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @return 是否处于审计模式（TerminalTool 据此决定是否真正拦截） */
    public boolean isAuditOnly() {
        return auditOnly;
    }

    /**
     * 对一条命令做完整分级评估。
     *
     * @param command 原始命令串
     * @return 决策；空白命令返回自动放行的 READ_ONLY（TerminalTool 会先行拒绝空命令）
     */
    public PolicyDecision evaluate(String command) {
        if (command == null || command.isBlank()) {
            return new PolicyDecision(CommandLevel.WRITE, java.util.List.of("empty-command"),
                    "空命令", java.util.List.of(), "", null, false);
        }

        var segments = segmenter.segment(command);
        String normalized = normalizeWhitespace(command);
        var matchedIds = new ArrayList<String>();
        String reason = null;
        CommandLevel overall = CommandLevel.READ_ONLY;
        boolean anyUnparseable = false;

        for (CommandSegment seg : segments) {
            if (seg.unparseable()) {
                anyUnparseable = true;
                // R2：无法可靠解析 → 至少 WRITE（保守），但复合/灾难检测仍可能把它抬更高
                overall = CommandLevel.max(overall, CommandLevel.WRITE);
                if (reason == null) {
                    reason = "命令部分无法可靠解析（" + seg.unparseableReason() + "），保守起见需确认";
                    matchedIds.add("unparseable-conservative");
                }
                continue;
            }
            if (seg.isBlank()) {
                continue;
            }

            CommandLevel segLevel = classifySegment(seg, matchedIds);
            // ① 递归删除灾难目标 → BLOCKED（精确路径判定，优先级最高）
            CommandLevel destructive = destructiveDeleteLevel(seg);
            if (destructive != null) {
                segLevel = CommandLevel.max(segLevel, destructive);
                if (destructive == CommandLevel.BLOCKED && (reason == null
                        || overall.severity() < CommandLevel.BLOCKED.severity())) {
                    reason = "递归删除灾难目标（根目录/家目录/整盘/绝对路径）";
                    matchedIds.add("blocked-destructive-delete");
                }
            }
            if (segLevel.severity() > overall.severity() && reason == null && !matchedIds.isEmpty()) {
                reason = reasonForLevel(seg, segLevel);
            }
            overall = CommandLevel.max(overall, segLevel);
        }

        // ② 复合模式（跨段）：kill PID1、fork 炸弹、下载管道执行
        CommandLevel compound = compoundLevel(command, segments, matchedIds);
        if (compound != null && compound.severity() > overall.severity()) {
            overall = compound;
            if (reason == null) {
                reason = "复合危险模式（下载执行 / fork 炸弹 / 终止关键进程）";
            }
        }

        if (overall.severity() == CommandLevel.READ_ONLY.severity() && segments.isEmpty()) {
            overall = CommandLevel.WRITE;
        }

        String finalReason = reason != null ? reason : defaultReason(overall, matchedIds);
        String groupKey = allowGroupKey(segments);

        PolicyDecision decision = new PolicyDecision(overall,
                java.util.List.copyOf(matchedIds), finalReason, segments, normalized,
                groupKey, anyUnparseable);
        logger.debug("命令分级: '{}' -> {} (rules={})", normalized, overall, matchedIds);
        return decision;
    }

    /**
     * 单段分类：规则命中取最严，未命中按 WRITE（未知命令保守默认，R6）。
     */
    private CommandLevel classifySegment(CommandSegment seg, ArrayList<String> matchedIds) {
        CommandLevel level;
        var matched = ruleSet.matching(seg);
        if (matched.isEmpty()) {
            // R6：未知命令一律 WRITE。READ_ONLY 只能来自显式白名单命中。
            // 注意：这里【不能提前 return】——未知命令的路径越出工作区时仍须升档
            // （如 rm -rf ../secret：rm 无专用规则走 WRITE，但越界应升到 DANGEROUS）。
            level = CommandLevel.WRITE;
            if (!matchedIds.contains("unknown-command-write")) {
                matchedIds.add("unknown-command-write");
            }
        } else {
            level = CommandLevel.READ_ONLY;
            for (CommandRule rule : matched) {
                matchedIds.add(rule.id());
                level = CommandLevel.max(level, rule.level());
            }
        }
        // eval 类（eval/iex/source）至少 WRITE；若已有更高命中则保留
        if (CommandSegmenter.isEvalLike(seg.executable()) && level.severity() < CommandLevel.WRITE.severity()) {
            level = CommandLevel.WRITE;
            matchedIds.add("eval-like-conservative");
        }
        // R9 工作区边界：路径越界则升一档（对未知命令与白名单命令都适用）
        if (level.severity() < CommandLevel.DANGEROUS.severity() && segmentPathEscapes(seg)) {
            CommandLevel escalated = level.escalate();
            if (escalated != level) {
                matchedIds.add("workspace-boundary-escalation");
            }
            level = escalated;
        }
        return level;
    }

    private String reasonForLevel(CommandSegment seg, CommandLevel level) {
        for (CommandRule rule : ruleSet.matching(seg)) {
            if (rule.level() == level) {
                return rule.reason();
            }
        }
        return null;
    }

    /**
     * 递归删除灾难目标判定：delete 命令 + （递归/强制标志 或 灾难目标）→ BLOCKED。
     *
     * @return BLOCKED/命中级别；不适用返回 null
     */
    private CommandLevel destructiveDeleteLevel(CommandSegment seg) {
        if (!DELETE_EXES.contains(seg.executable())) {
            return null;
        }
        boolean recursiveForce = seg.flags().stream()
                .anyMatch(f -> RECURSIVE_FORCE_FLAGS.contains(f.toLowerCase(Locale.ROOT)));
        for (String target : seg.pathCandidates()) {
            if (WorkspacePathAnalyzer.isCatastrophicDeleteTarget(target)) {
                // 只要目标是灾难位置就 BLOCKED（无论是否带 r/f，删绝对根位置本身就是灾难）
                return CommandLevel.BLOCKED;
            }
        }
        // 带递归/强制但目标在工作区内 → 不是灾难，交给普通分类（通常 WRITE + 确认）
        return recursiveForce ? CommandLevel.WRITE : null;
    }

    /** 段的任一路径参数是否越出工作区（R9）。 */
    private boolean segmentPathEscapes(CommandSegment seg) {
        for (String p : seg.pathCandidates()) {
            if (WorkspacePathAnalyzer.escapesWorkspace(p, workspace)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 复合模式检测（单段规则无法表达的跨段危险组合）。
     *
     * @return 命中级别；无命中返回 null
     */
    private CommandLevel compoundLevel(String command, java.util.List<CommandSegment> segments,
                                       ArrayList<String> matchedIds) {
        String lower = command.toLowerCase(Locale.ROOT);

        // fork 炸弹特征
        if (lower.contains(":(){") && (lower.contains(":|:&") || lower.contains(":|: &"))) {
            matchedIds.add("blocked-fork-bomb");
            return CommandLevel.BLOCKED;
        }

        // 反弹 shell：bash -i >& /dev/tcp/host/port —— >& 里的 & 会被分段器当分隔符切开，
        // 单段规则匹配不到 /dev/tcp/，故在整命令级用原始串检测（更稳）。
        if (lower.contains("/dev/tcp/") || lower.contains("/dev/udp/")) {
            matchedIds.add("blocked-reverse-shell");
            return CommandLevel.BLOCKED;
        }

        // kill PID 1（终止 init，系统立即崩溃）
        for (CommandSegment seg : segments) {
            if ("kill".equals(seg.executable()) && seg.args().stream().anyMatch(a -> a.trim().equals("1"))) {
                matchedIds.add("blocked-kill-pid1");
                return CommandLevel.BLOCKED;
            }
        }

        // 下载管道给 shell / 解释器：网络抓取 + shell 解释器同时出现在同一命令中
        boolean hasFetch = false;
        boolean hasShell = false;
        for (CommandSegment seg : segments) {
            String exe = seg.executable();
            if (NETWORK_FETCH_EXES.contains(exe)) {
                hasFetch = true;
            }
            if (SHELL_INTERPRETERS.contains(exe)) {
                hasShell = true;
            }
        }
        if (hasFetch && hasShell) {
            matchedIds.add("dangerous-download-pipe-execute");
            return CommandLevel.DANGEROUS;
        }
        return null;
    }

    /**
     * 生成「同类放行」归组键（R7）：可执行名 + 子命令 + 是否工作区内。
     *
     * <p>只对 WRITE 有意义（DANGEROUS/BLOCKED 不允许 a 放行）。多段命令用首段的可执行名+子命令，
     * 保证「同一 git push 在工作区内」归为一类。</p>
     */
    private String allowGroupKey(java.util.List<CommandSegment> segments) {
        for (CommandSegment seg : segments) {
            if (!seg.isBlank() && !seg.executable().isEmpty()) {
                String sub = seg.subcommand() != null ? seg.subcommand() : "";
                boolean inWorkspace = !segmentPathEscapes(seg);
                return seg.executable() + "|" + sub + "|" + (inWorkspace ? "in" : "out");
            }
        }
        return null;
    }

    private String defaultReason(CommandLevel level, ArrayList<String> matchedIds) {
        return switch (level) {
            case BLOCKED -> "系统级破坏命令，永不执行";
            case DANGEROUS -> "高危操作，需强确认（键入 yes）";
            case WRITE -> matchedIds.contains("unknown-command-write")
                    ? "未知命令，保守起见需确认" : "修改文件或状态，需确认";
            case READ_ONLY -> "只读命令，自动放行";
        };
    }

    private static String normalizeWhitespace(String command) {
        return command.replaceAll("\\s+", " ").trim();
    }
}
