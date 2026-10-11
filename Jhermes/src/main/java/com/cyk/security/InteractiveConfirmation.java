package com.cyk.security;

import com.cyk.ui.Ui;
import com.cyk.ui.UserInput;
import com.cyk.util.ConsoleUi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <h1>交互式确认闸门（JH-SPEC-001 P0-2 R7）</h1>
 *
 * <p>向用户展示命令、级别、理由、命中规则，并按级别索取确认：</p>
 * <ul>
 *   <li><b>WRITE</b>：{@code [y] 执行 / [n] 拒绝 / [a] 本会话内同类放行}——
 *       大小写不敏感，直接回车视为拒绝（保守：不明确同意就不执行）；</li>
 *   <li><b>DANGEROUS</b>：展示完整命令与理由，要求逐字键入 {@code yes}，
 *       其余任何输入（含大小写不符、多余空格）一律拒绝。</li>
 * </ul>
 *
 * <p><b>输入通道</b>：经 {@link Ui#userInput()} 读取，与 REPL 共用同一 {@code BufferedReader}
 * （R0 硬约束）。确认闸门绝不自建第二个 {@code System.in} reader。</p>
 *
 * <p><b>非交互降级</b>：{@link UserInput#isInteractive()} 为 false（管道/重定向/CI）时，
 * 需要确认的操作直接 DENY，不阻塞等待（否则 {@code echo x | Jhermes} 会永久挂起）。</p>
 */
public final class InteractiveConfirmation implements ConfirmationProvider {

    private static final Logger logger = LoggerFactory.getLogger(InteractiveConfirmation.class);

    private final SessionAllowList allowList;
    private final UserInput input;

    /** 生产构造：从 Ui 取共享输入通道。 */
    public InteractiveConfirmation(SessionAllowList allowList) {
        this(allowList, Ui.userInput());
    }

    /** 测试构造：注入桩 UserInput。 */
    InteractiveConfirmation(SessionAllowList allowList, UserInput input) {
        this.allowList = allowList;
        this.input = input;
    }

    @Override
    public Approval request(PolicyDecision decision) {
        // READ_ONLY 自动放行
        if (decision.isAutoAllowed()) {
            return Approval.ALLOW;
        }
        // BLOCKED 不该走到确认闸门（调用方硬拦），但作纵深防御：真进来也一律拒绝
        if (decision.isBlocked()) {
            return Approval.DENY;
        }
        // 会话内已放行同类（仅 WRITE）
        if (decision.allowsSessionReuse() && allowList.isAllowed(decision.allowGroupKey())) {
            return Approval.ALLOW;
        }
        // 非交互：需要确认一律拒绝，绝不阻塞
        if (!input.isInteractive()) {
            logger.debug("非交互模式，命令需确认，按拒绝处理: {}", decision.normalizedCommand());
            return Approval.DENY;
        }

        showCommand(decision);
        return decision.needsStrongConfirmation()
                ? askStrongConfirmation(decision)
                : askWriteConfirmation(decision);
    }

    /** 展示命令、级别、理由、命中规则（对齐规范 §14.1 目标体验）。 */
    private void showCommand(PolicyDecision decision) {
        Ui.out().println();
        Ui.out().println(ConsoleUi.dimPrefix() + "待确认命令");
        Ui.out().println("  级别: " + decision.level() + (decision.level() == CommandLevel.DANGEROUS
                ? "（高危）" : "") + (decision.unparseable() ? " · 含无法可靠解析部分" : ""));
        Ui.out().println("  命令: " + decision.normalizedCommand());
        if (decision.reason() != null && !decision.reason().isBlank()) {
            Ui.out().println("  理由: " + decision.reason());
        }
        if (!decision.matchedRuleIds().isEmpty()) {
            Ui.out().println("  规则: " + String.join(", ", decision.matchedRuleIds()));
        }
    }

    /** WRITE：y/n/a。 */
    private Approval askWriteConfirmation(PolicyDecision decision) {
        String answer = input.readLine("  [y] 执行  [n] 拒绝  [a] 本会话内同类不再询问  选择 > ");
        if (answer == null) {
            // 输入流结束：保守拒绝
            return Approval.DENY;
        }
        String a = answer.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (a) {
            case "y", "yes" -> Approval.ALLOW;
            case "a", "all" -> {
                if (decision.allowsSessionReuse()) {
                    allowList.allow(decision.allowGroupKey());
                    yield Approval.ALLOW;
                }
                // 不允许会话放行（理论到不了这里）：退化为单次批准，不记住
                logger.debug("命令不支持会话放行，本次单独批准");
                yield Approval.ALLOW;
            }
            // 直接回车或任何其它输入视为拒绝（不明确同意就不执行）
            default -> Approval.DENY;
        };
    }

    /** DANGEROUS：必须逐字键入 yes。 */
    private Approval askStrongConfirmation(PolicyDecision decision) {
        String answer = input.readLine("  ⚠ 高危操作不可撤销。确认执行请逐字输入 yes（其它任何输入都拒绝）> ");
        if (answer == null) {
            return Approval.DENY;
        }
        // 严格：trim 后必须恰好等于 yes，大小写敏感按规范原文（键入 yes）
        return "yes".equals(answer.trim()) ? Approval.ALLOW : Approval.DENY;
    }

    /** @return 会话放行表（供 TerminalTool 统计/自检，包级可见）。 */
    SessionAllowList allowList() {
        return allowList;
    }
}
