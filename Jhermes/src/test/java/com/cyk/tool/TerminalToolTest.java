package com.cyk.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * TerminalTool（run_command）测试：危险命令黑名单、输出截断、进程构建、参数校验。
 *
 * <p>默认只测纯逻辑（不启动子进程）；真实执行用例标记 {@code integration}，
 * 由 surefire 配置默认排除，需要时手动运行验证。</p>
 */
class TerminalToolTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // =========================================================================
    // 危险命令黑名单（checkDangerous / isDangerous）
    // =========================================================================

    @Test
    void shouldBlockRmRfRoot() {
        assertThat(TerminalTool.isDangerous("rm -rf /")).isTrue();
        assertThat(TerminalTool.isDangerous("rm -rf /home/user")).isTrue();
        assertThat(TerminalTool.isDangerous("sudo rm -rf / --no-preserve-root")).isTrue();
    }

    @Test
    void shouldBlockRmRfHomeAndDot() {
        assertThat(TerminalTool.isDangerous("rm -rf ~")).isTrue();
        assertThat(TerminalTool.isDangerous("rm -rf .")).isTrue();
        assertThat(TerminalTool.isDangerous("rm -fr /var")).isTrue();
    }

    @Test
    void shouldBlockRmRfWindowsDriveRoot() {
        assertThat(TerminalTool.isDangerous("rm -rf C:\\")).isTrue();
        assertThat(TerminalTool.isDangerous("rm -rf c:/")).isTrue();
    }

    @Test
    void shouldBlockFormatAndDiskpart() {
        assertThat(TerminalTool.isDangerous("format C:")).isTrue();
        assertThat(TerminalTool.isDangerous("mkfs.ext4 /dev/sda1")).isTrue();
        assertThat(TerminalTool.isDangerous("echo list disk | diskpart")).isTrue();
    }

    @Test
    void shouldBlockShutdownCommands() {
        assertThat(TerminalTool.isDangerous("shutdown /s /t 0")).isTrue();
        assertThat(TerminalTool.isDangerous("shutdown -h now")).isTrue();
        assertThat(TerminalTool.isDangerous("Restart-Computer -Force")).isTrue();
        assertThat(TerminalTool.isDangerous("init 0")).isTrue();
    }

    @Test
    void shouldAllowOrdinaryDangerousFreeCommands() {
        // 日常开发命令绝不能误杀——这是黑名单设计的底线
        assertThat(TerminalTool.checkDangerous("git status")).isNull();
        assertThat(TerminalTool.checkDangerous("mvn -q clean package")).isNull();
        assertThat(TerminalTool.checkDangerous("dir C:\\Users")).isNull();
        assertThat(TerminalTool.checkDangerous("Get-Process | Select-Object -First 5")).isNull();
        assertThat(TerminalTool.checkDangerous("echo hello")).isNull();
    }

    @Test
    void shouldAllowDatabaseDestructiveOpsAtToolLayer() {
        // 设计决策（见 DANGEROUS_PATTERNS javadoc）：数据级破坏操作不在工具层硬拦截，
        // 靠提示词层「先向用户确认」约束——否则用户明确要求删库时模型永远无法执行。
        assertThat(TerminalTool.checkDangerous(
                "psql -h localhost -U postgres -c \"DROP DATABASE IF EXISTS hermes_rag;\"")).isNull();
        assertThat(TerminalTool.checkDangerous("rm -rf ./build")).isNull();
        assertThat(TerminalTool.checkDangerous("git push --force origin feature")).isNull();
    }

    @Test
    void shouldBlockOrdinaryRmOfNormalFiles() {
        // 删除单个文件/普通目录是文件管理日常操作，不拦截
        assertThat(TerminalTool.checkDangerous("rm C:\\Users\\admin\\drop_kb.sql")).isNull();
        assertThat(TerminalTool.checkDangerous("rm -rf ./target")).isNull();
    }

    @Test
    void checkDangerousShouldReturnReason() {
        String reason = TerminalTool.checkDangerous("rm -rf /");
        assertThat(reason).isNotNull().contains("根目录");
    }

    // =========================================================================
    // 危险命令被拦截时的 handler 行为（不启动子进程）
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void runCommandShouldRefuseDangerousCommandWithoutExecuting() throws Exception {
        String json = TerminalTool.runCommand(Map.of("command", "rm -rf /"));
        Map<String, Object> result = mapper.readValue(json, Map.class);
        assertThat(result).containsKey("error");
        assertThat((String) result.get("error")).contains("安全策略拦截");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runCommandShouldRejectBlankCommand() throws Exception {
        String json = TerminalTool.runCommand(Map.of("command", "   "));
        Map<String, Object> result = mapper.readValue(json, Map.class);
        assertThat(result).containsKey("error");
        assertThat((String) result.get("error")).contains("command");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runCommandShouldRejectMissingCommand() throws Exception {
        String json = TerminalTool.runCommand(Map.of());
        Map<String, Object> result = mapper.readValue(json, Map.class);
        assertThat(result).containsKey("error");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runCommandShouldRejectNonexistentWorkdir() throws Exception {
        String json = TerminalTool.runCommand(Map.of(
                "command", "echo hi",
                "workdir", System.getProperty("java.io.tmpdir") + "definitely-not-exist-dir-xyz"));
        Map<String, Object> result = mapper.readValue(json, Map.class);
        assertThat(result).containsKey("error");
        assertThat((String) result.get("error")).contains("工作目录");
    }

    // =========================================================================
    // 输出截断（truncate）
    // =========================================================================

    @Test
    void truncateShouldKeepShortOutputUntouched() {
        String shortText = "hello world";
        assertThat(TerminalTool.truncate(shortText)).isEqualTo(shortText);
        assertThat(TerminalTool.truncate(null)).isEmpty();
    }

    @Test
    void truncateShouldCutLongOutputWithMarker() {
        String longText = "x".repeat(TerminalTool.MAX_OUTPUT_CHARS + 5000);
        String truncated = TerminalTool.truncate(longText);
        assertThat(truncated.length()).isLessThan(longText.length());
        assertThat(truncated).contains("省略");
        // 头尾都必须保留（模型常需要看结尾的报错信息）
        assertThat(truncated).startsWith("xxx");
        assertThat(truncated).endsWith("xxx");
    }

    // =========================================================================
    // 进程构建（buildProcess，不执行）
    // =========================================================================

    @Test
    void buildProcessShouldPickShellByOsAndArgument() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        var pbDefault = TerminalTool.buildProcess("echo hi", null,
                java.nio.file.Paths.get(System.getProperty("user.dir")));
        String defaultCmd = String.join(" ", pbDefault.command());
        if (isWindows) {
            assertThat(defaultCmd).contains("powershell").contains("-NoProfile");
            var pbCmd = TerminalTool.buildProcess("echo hi", "cmd",
                    java.nio.file.Paths.get(System.getProperty("user.dir")));
            assertThat(String.join(" ", pbCmd.command())).contains("cmd.exe").contains("/c");
        } else {
            assertThat(defaultCmd).contains("bash");
            var pbSh = TerminalTool.buildProcess("echo hi", "sh",
                    java.nio.file.Paths.get(System.getProperty("user.dir")));
            assertThat(String.join(" ", pbSh.command())).contains("/bin/sh");
        }
    }

    @Test
    void buildProcessShouldSetWorkdir() {
        var dir = java.nio.file.Paths.get(System.getProperty("user.dir"));
        var pb = TerminalTool.buildProcess("echo hi", null, dir);
        assertThat(pb.directory()).isEqualTo(dir.toFile());
    }

    // =========================================================================
    // 敏感凭据路径拦截（防 token/密钥泄入对话历史）
    // =========================================================================

    @Test
    void shouldBlockReadingCredentialFiles() {
        // 真实事故回归（2026-09-28）：模型把 ~/.ai_tools/.meta.json 里的 auth token 读进了历史
        assertThat(TerminalTool.checkSensitive("Get-Content C:\\Users\\admin\\.ai_tools\\.meta.json -Raw")).isNotNull();
        assertThat(TerminalTool.checkSensitive("cat ~/.ssh/id_rsa")).isNotNull();
        assertThat(TerminalTool.checkSensitive("type C:\\Users\\admin\\.git-credentials")).isNotNull();
        assertThat(TerminalTool.checkSensitive("Get-Content ~/.jhermes/config.yaml")).isNotNull();
        // 旧数据目录名（.skhermes）也必须拦住：迁移失败时凭据仍在旧路径下
        assertThat(TerminalTool.checkSensitive("Get-Content ~/.skhermes/config.yaml")).isNotNull();
    }

    @Test
    void shouldNotFalsePositiveOnSimilarButSafeNames() {
        // 子串误伤防护：.sshare/.sshx 不是 .ssh；项目名包含 aws 但不是凭据文件
        assertThat(TerminalTool.checkSensitive("dir C:\\projects\\.sshare")).isNull();
        assertThat(TerminalTool.checkSensitive("echo aws-cli-guide")).isNull();
        assertThat(TerminalTool.checkSensitive("git status")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void runCommandShouldRefuseSensitivePathAccess() throws Exception {
        String json = TerminalTool.runCommand(Map.of(
                "command", "Get-Content C:\\Users\\admin\\.ai_tools\\.meta.json"));
        Map<String, Object> result = mapper.readValue(json, Map.class);
        assertThat(result).containsKey("error");
        assertThat((String) result.get("error")).contains("敏感凭据");
    }

    // =========================================================================
    // 重复命令护栏（打断“搜不到就换个姿势再全盘扫”的死循环）
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void runCommandShouldRejectThirdIdenticalExecution() throws Exception {
        TerminalTool.resetCommandCounts();
        try {
            // 用一个不存在的目录作为 workdir 会让命令快速失败，但计数护栏在此之前拦截，
            // 因此这里不需要真实可执行环境：前两次走到执行阶段（启动失败也算执行过），第三次被护栏拒绝
            Map<String, Object> args = Map.of("command", "echo hello-guard-test");
            TerminalTool.runCommand(args);
            TerminalTool.runCommand(args);
            String third = TerminalTool.runCommand(args);
            Map<String, Object> result = mapper.readValue(third, Map.class);
            assertThat(result).containsKey("error");
            assertThat((String) result.get("error")).contains("已拒绝重复执行");
        } finally {
            TerminalTool.resetCommandCounts();
        }
    }

    @Test
    void repeatGuardShouldTreatWhitespaceAndCaseAsSameCommand() {
        TerminalTool.resetCommandCounts();
        // 归一化：大小写与多余空白视为同一命令（模型常只改空格重试）
        String a = "Get-ChildItem   C:\\temp -Recurse";
        String b = "get-childitem c:\\temp -recurse";
        assertThat(TerminalTool.normalizeForRepeatGuard(a))
                .isEqualTo(TerminalTool.normalizeForRepeatGuard(b));
    }

    // =========================================================================
    // 真实执行（integration：默认被 surefire 排除）
    // =========================================================================

    @Test
    @Tag("integration")
    @SuppressWarnings("unchecked")
    void runCommandShouldExecuteEchoAndReturnStdout() throws Exception {
        String json = TerminalTool.runCommand(Map.of("command", "echo hermes-terminal-ok"));
        Map<String, Object> result = mapper.readValue(json, Map.class);
        assertThat(result).doesNotContainKey("error");
        assertThat((Integer) result.get("exit_code")).isZero();
        assertThat((Boolean) result.get("timed_out")).isFalse();
        assertThat((String) result.get("stdout")).contains("hermes-terminal-ok");
    }

    @Test
    @Tag("integration")
    @SuppressWarnings("unchecked")
    void runCommandShouldTimeoutAndKillLongRunningCommand() throws Exception {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String sleep = isWindows ? "Start-Sleep -Seconds 10" : "sleep 10";
        String json = TerminalTool.runCommand(Map.of("command", sleep, "timeout_seconds", 2));
        Map<String, Object> result = mapper.readValue(json, Map.class);
        assertThat((Boolean) result.get("timed_out")).isTrue();
        assertThat((Integer) result.get("exit_code")).isEqualTo(-1);
    }
}
