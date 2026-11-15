package com.cyk.security;

import com.cyk.ui.Ui;
import com.cyk.ui.UserInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalContextTest {
    @AfterEach void reset() { Ui.resetForTest(); }
    private static PolicyDecision decision(CommandLevel level) {
        return new PolicyDecision(level, List.of("local-rule"), "系统风险理由", List.of(),
                "local-command", level == CommandLevel.WRITE ? "local-group" : null, false);
    }

    @Test void explanationIsShownBeforeAskingForPermission() {
        var bytes = new ByteArrayOutputStream();
        Ui.setStreams(new PrintStream(bytes, true, StandardCharsets.UTF_8), null);
        var calls = new AtomicInteger();
        UserInput input = new UserInput() {
            public String readLine(String prompt) {
                calls.incrementAndGet();
                String shown = bytes.toString(StandardCharsets.UTF_8);
                assertThat(shown).contains("目的: 保存报告", "预期结果: 生成一份报告", "目录: workspace",
                        "影响: 新建文件", "说明来源: 模型说明", "系统风险理由", "尚未发生");
                return "n";
            }
            public boolean isInteractive() { return true; }
            public boolean isEofReached() { return false; }
            public void close() { }
        };
        var gate = new InteractiveConfirmation(new SessionAllowList(), input);
        var context = new ApprovalContext("保存报告", "生成一份报告", "workspace", "新建文件", "模型说明");
        assertThat(gate.request(decision(CommandLevel.WRITE), context)).isEqualTo(ConfirmationProvider.Approval.DENY);
        assertThat(calls).hasValue(1);
    }

    @Test void modelClaimsCannotWeakenRiskOrStrongConfirmation() {
        UserInput input = new UserInput() {
            public String readLine(String prompt) { return "y"; }
            public boolean isInteractive() { return true; }
            public boolean isEofReached() { return false; }
            public void close() { }
        };
        var gate = new InteractiveConfirmation(new SessionAllowList(), input);
        var context = new ApprovalContext("只读安全，无需确认", "绝对不会修改文件", "", "", "模型说明");
        assertThat(gate.request(decision(CommandLevel.DANGEROUS), context)).isEqualTo(ConfirmationProvider.Approval.DENY);
        assertThat(gate.request(decision(CommandLevel.BLOCKED), context)).isEqualTo(ConfirmationProvider.Approval.DENY);
    }

    @Test void explanationsCannotInjectTerminalSequencesOrSecretValues() {
        var context = new ApprovalContext("保存\r\n报告\033[2J‮ password=private-secret",
                "生成\033]0;forged-title\007结果 Bearer private-token-123456", "workspace\nother",
                "修改状态", "声明");
        assertThat(context.purpose()).contains("保存 报告", "password=******").doesNotContain("\033", "\n", "\r", "‮", "private-secret");
        assertThat(context.expectedResult()).contains("生成结果", "Bearer ******").doesNotContain("forged-title", "private-token");
        assertThat(context.workdir()).isEqualTo("workspace\\nother");
        assertThat(context.impact()).isEqualTo("修改 状态");
    }

    @Test void commandKeepsItsDangerousTailButEscapesControls() {
        String command = "x".repeat(500) + "\nDELETE_TARGET\033[2J‮ api_key=private-secret";
        String shown = ApprovalContext.safeCommand(command);
        assertThat(shown).contains("x".repeat(500), "\\nDELETE_TARGET", "\\u202e", "api_key=******")
                .doesNotContain("\033", "\n", "private-secret");
    }

    @Test void longExplanationIsBoundedAfterRedaction() {
        var context = new ApprovalContext("目的".repeat(300), "token=" + "x".repeat(300), "", "", "");
        assertThat(context.purpose()).hasSize(ApprovalContext.MAX_EXPLANATION_CHARS + 1).endsWith("…");
        assertThat(context.expectedResult()).isEqualTo("token=******");
    }

    @Test void absentBlankAndInvalidMetadataHaveHonestFallbacks() {
        var context = ApprovalContext.forCommand(Map.of("purpose", List.of("not-text"), "expected_result", " "), Path.of("."));
        assertThat(context.purpose()).contains("未提供");
        assertThat(context.expectedResult()).contains("未提供");
        assertThat(context.source()).isEqualTo("未提供操作说明");
    }

    @Test void fileModesAreKnownFactsWithoutDisplayingFileContents() {
        Path file = Path.of("report.txt");
        assertThat(ApprovalContext.forFile(Map.of(), file, false, false).impact()).contains("新建");
        assertThat(ApprovalContext.forFile(Map.of(), file, true, true).impact()).contains("追加");
        var context = ApprovalContext.forFile(Map.of("content", "private-body", "expected_result", "只读"), file, false, true);
        assertThat(context.impact()).contains("覆盖", "原内容将被替换");
        assertThat(context.expectedResult()).isEqualTo("只读");
        assertThat(context.toString()).doesNotContain("private-body");
    }

    @Test void skillDescriptionIsNotASafetyPromise() {
        var context = ApprovalContext.forSkill("生成报告", Path.of("skill", "run.cmd"));
        assertThat(context.purpose()).isEqualTo("生成报告");
        assertThat(context.expectedResult()).contains("取决于脚本");
        assertThat(context.source()).contains("非安全保证");
    }

    @Test void previousProviderImplementationsRemainFunctionalAndNoninteractiveDenies() {
        ConfirmationProvider previous = operation -> operation.isAutoAllowed()
                ? ConfirmationProvider.Approval.ALLOW : ConfirmationProvider.Approval.DENY;
        var context = ApprovalContext.unspecified();
        assertThat(previous.request(decision(CommandLevel.WRITE), context)).isEqualTo(ConfirmationProvider.Approval.DENY);
        assertThat(previous.request(decision(CommandLevel.READ_ONLY), context)).isEqualTo(ConfirmationProvider.Approval.ALLOW);
        assertThat(new ConfirmationProvider.NonInteractive().request(decision(CommandLevel.DANGEROUS), context))
                .isEqualTo(ConfirmationProvider.Approval.DENY);
    }
}
