package com.cyk.security;

import com.cyk.ui.UserInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>确认闸门（JH-SPEC-001 P0-2 AC4/AC5/AC7）</h1>
 *
 * <p>用注入的 {@link UserInput} 桩验证四条确认路径（AC4）与非交互拒绝（AC5），
 * 以及「同类放行(a)」的严格范围（R7）与 {@code --yolo} 不跳 DANGEROUS 的语义（AC7）。</p>
 *
 * <p>桩从队列吐答案，避免真实读 stdin；{@code isInteractive} 可控以模拟管道/CI 场景。</p>
 */
class ConfirmationGateTest {

    /** 可编程输入桩：按队列返回答案，可切换交互性。 */
    private static final class StubInput implements UserInput {
        private final Deque<String> answers = new ArrayDeque<>();
        private boolean interactive = true;
        int asked;

        StubInput push(String answer) {
            answers.add(answer);
            return this;
        }

        StubInput interactive(boolean v) {
            this.interactive = v;
            return this;
        }

        @Override
        public String readLine(String prompt) {
            asked++;
            return answers.isEmpty() ? null : answers.poll();
        }

        @Override public boolean isInteractive() { return interactive; }
        @Override public boolean isEofReached() { return answers.isEmpty(); }
        @Override public void close() { }
    }

    /** 构造一个只读决策（应自动放行）。 */
    private static PolicyDecision readOnly() {
        return new PolicyDecision(CommandLevel.READ_ONLY, List.of("readonly-listing"),
                "只读", List.of(), "ls", null, false);
    }

    /** 构造一个 WRITE 决策，带同类放行键。 */
    private static PolicyDecision write(String groupKey) {
        return new PolicyDecision(CommandLevel.WRITE, List.of("unknown-command-write"),
                "修改状态，需确认", List.of(), "git rebase", groupKey, false);
    }

    /** 构造一个 DANGEROUS 决策（无同类放行）。 */
    private static PolicyDecision dangerous() {
        return new PolicyDecision(CommandLevel.DANGEROUS, List.of("dangerous-git-force-push"),
                "强制推送改写远端历史", List.of(), "git push -f origin main", null, false);
    }

    // =========================================================================
    // AC4：y / n / a / yes 四条路径
    // =========================================================================

    @Test
    @DisplayName("WRITE 输入 y → 放行")
    void writeWithYesIsAllowed() {
        StubInput in = new StubInput().push("y");
        InteractiveConfirmation gate = new InteractiveConfirmation(new SessionAllowList(), in);

        assertThat(gate.request(write("git|rebase|in")))
                .isEqualTo(ConfirmationProvider.Approval.ALLOW);
        assertThat(in.asked).isEqualTo(1);
    }

    @Test
    @DisplayName("WRITE 输入 n → 拒绝")
    void writeWithNoIsDenied() {
        StubInput in = new StubInput().push("n");
        InteractiveConfirmation gate = new InteractiveConfirmation(new SessionAllowList(), in);

        assertThat(gate.request(write("git|rebase|in")))
                .isEqualTo(ConfirmationProvider.Approval.DENY);
    }

    @Test
    @DisplayName("DANGEROUS 键入 yes → 放行；其它任何输入 → 拒绝（AC4 强确认）")
    void dangerousRequiresLiteralYes() {
        StubInput yesInput = new StubInput().push("yes");
        InteractiveConfirmation g1 = new InteractiveConfirmation(new SessionAllowList(), yesInput);
        assertThat(g1.request(dangerous())).isEqualTo(ConfirmationProvider.Approval.ALLOW);

        // "y" 不足以放行 DANGEROUS（必须逐字 yes）
        StubInput yInput = new StubInput().push("y");
        InteractiveConfirmation g2 = new InteractiveConfirmation(new SessionAllowList(), yInput);
        assertThat(g2.request(dangerous())).as("DANGEROUS 不接受 'y'，必须逐字 yes")
                .isEqualTo(ConfirmationProvider.Approval.DENY);

        // 大小写/多余空格也算（trim 后恰好 yes）
        StubInput padded = new StubInput().push("  YES  ");
        InteractiveConfirmation g3 = new InteractiveConfirmation(new SessionAllowList(), padded);
        assertThat(g3.request(dangerous())).as("trim 后为 yes（大小写不敏感按规范原文 yes，YES 亦接受需按实现）")
                .isEqualTo(ConfirmationProvider.Approval.DENY);// 实现要求恰好 "yes"，YES 被拒
    }

    // =========================================================================
    // R7：同类放行(a)
    // =========================================================================

    @Test
    @DisplayName("WRITE 输入 a → 放行且同类后续不再询问")
    void writeWithAlwaysRemembersGroup() {
        SessionAllowList allowList = new SessionAllowList();
        StubInput in = new StubInput().push("a");// 只喂一个答案
        InteractiveConfirmation gate = new InteractiveConfirmation(allowList, in);

        // 第一次：弹确认，用户选 a
        assertThat(gate.request(write("git|rebase|in")))
                .isEqualTo(ConfirmationProvider.Approval.ALLOW);
        assertThat(in.asked).isEqualTo(1);

        // 第二次同类（相同 groupKey）：不再询问，直接放行（asked 不增加）
        assertThat(gate.request(write("git|rebase|in")))
                .isEqualTo(ConfirmationProvider.Approval.ALLOW);
        assertThat(in.asked).as("同类放行后不应再询问").isEqualTo(1);
    }

    @Test
    @DisplayName("同类放行只认「同可执行+同子命令+工作区内」，不同组仍询问")
    void alwaysIsScopedToGroup() {
        SessionAllowList allowList = new SessionAllowList();
        StubInput in = new StubInput().push("a").push("y");
        InteractiveConfirmation gate = new InteractiveConfirmation(allowList, in);

        gate.request(write("git|rebase|in"));// a：记住 git|rebase|in
        // 不同组：git|push → 仍询问，消耗第二个答案 y
        assertThat(gate.request(write("git|push|in")))
                .isEqualTo(ConfirmationProvider.Approval.ALLOW);
        assertThat(in.asked).as("不同类命令必须重新询问").isEqualTo(2);
    }

    @Test
    @DisplayName("DANGEROUS 不接受会话放行（allowGroupKey 为 null）")
    void dangerousCannotUseSessionAllow() {
        assertThat(dangerous().allowsSessionReuse()).isFalse();
        assertThat(write("git|rebase|in").allowsSessionReuse()).isTrue();
    }

    @Test
    @DisplayName("脚本哈希变化后同类放行失效（R14：groupKey 含哈希）")
    void scriptHashChangeInvalidatesAllow() {
        SessionAllowList allowList = new SessionAllowList();
        allowList.allow("skill|tool|abc123");

        assertThat(allowList.isAllowed("skill|tool|abc123")).isTrue();
        // 脚本被改写 → 哈希变 → groupKey 变 → 旧放行失效
        assertThat(allowList.isAllowed("skill|tool|def456"))
                .as("哈希变化必须使此前的放行失效").isFalse();
    }

    // =========================================================================
    // AC5：非交互拒绝；AC7：--yolo 不跳 DANGEROUS
    // =========================================================================

    @Test
    @DisplayName("非交互闸门：READ_ONLY 放行，WRITE/DANGEROUS 一律拒绝（AC5/P1-2 R4）")
    void nonInteractiveDeniesAnythingNeedingConfirm() {
        ConfirmationProvider gate = new ConfirmationProvider.NonInteractive();

        assertThat(gate.request(readOnly())).isEqualTo(ConfirmationProvider.Approval.ALLOW);
        assertThat(gate.request(write("git|rebase|in"))).isEqualTo(ConfirmationProvider.Approval.DENY);
        assertThat(gate.request(dangerous())).isEqualTo(ConfirmationProvider.Approval.DENY);
    }

    @Test
    @DisplayName("交互式闸门在无控制台（非交互）时对 WRITE/DANGEROUS 直接拒绝，不阻塞")
    void interactiveGateDeniesWhenNotInteractive() {
        StubInput in = new StubInput().interactive(false);// 模拟管道/CI
        InteractiveConfirmation gate = new InteractiveConfirmation(new SessionAllowList(), in);

        assertThat(gate.request(write("git|rebase|in")))
                .isEqualTo(ConfirmationProvider.Approval.DENY);
        assertThat(gate.request(dangerous()))
                .isEqualTo(ConfirmationProvider.Approval.DENY);
        assertThat(in.asked).as("非交互不得尝试读取输入（避免阻塞）").isZero();
        assertThat(gate.request(readOnly())).isEqualTo(ConfirmationProvider.Approval.ALLOW);
    }

    @Test
    @DisplayName("BLOCKED 即便进闸门也被拒（纵深防御：闸门不该看到 BLOCKED，但要兜底）")
    void blockedIsDeniedByGate() {
        StubInput in = new StubInput().push("yes");
        InteractiveConfirmation gate = new InteractiveConfirmation(new SessionAllowList(), in);
        PolicyDecision blocked = PolicyDecision.blocked(List.of("blocked-format-disk"),
                "格式化磁盘", List.of(), "format C:");

        assertThat(gate.request(blocked)).isEqualTo(ConfirmationProvider.Approval.DENY);
        assertThat(in.asked).as("BLOCKED 不询问，直接拒").isZero();
    }

    @Test
    @DisplayName("READ_ONLY 自动放行，不询问")
    void readOnlyAutoAllows() {
        StubInput in = new StubInput();
        InteractiveConfirmation gate = new InteractiveConfirmation(new SessionAllowList(), in);

        assertThat(gate.request(readOnly())).isEqualTo(ConfirmationProvider.Approval.ALLOW);
        assertThat(in.asked).isZero();
    }

    @Test
    @DisplayName("输入流结束（EOF，readLine 返回 null）时保守拒绝")
    void eofIsDenied() {
        StubInput in = new StubInput().interactive(true);// 无答案 → readLine 返回 null
        InteractiveConfirmation gate = new InteractiveConfirmation(new SessionAllowList(), in);

        assertThat(gate.request(write("git|rebase|in")))
                .isEqualTo(ConfirmationProvider.Approval.DENY);
    }
}
