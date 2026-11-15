package com.cyk.tool;

import com.cyk.ProcessFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>技能脚本执行的超时与并发泵（JH-SPEC-001 P0-5 R10 / AC9）</h1>
 *
 * <p>改造前 {@code SkillTool.createScriptHandler} 有三个真实缺陷（附录 A9）：</p>
 * <ol>
 *   <li><b>无超时</b>：挂死的脚本会把整个会话永久阻塞在 {@code waitFor()}，用户只能强杀进程；</li>
 *   <li><b>串行读取 stdout 再读 stderr</b>：管道缓冲区（Windows 约 4KB）写满后子进程阻塞，
 *       而父进程正等在另一路输出上——形成经典死锁；</li>
 *   <li><b>未关闭子进程 stdin</b>：需要交互输入的脚本会永久挂起。</li>
 * </ol>
 *
 * <p>命令安全门禁（经 {@code CommandPolicy} 按 WRITE 确认、脚本哈希变更失效）属 P0-2 R14，
 * 不在本需求范围内。</p>
 */
class SkillToolTimeoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workDir;

    /** 生成一个「向 stderr 喷大量数据后以非零码退出」的脚本，用于验证不会死锁。 */
    private Path writeFloodScript(int lines) throws IOException {
        StringBuilder body = new StringBuilder();
        if (ProcessFixtures.WINDOWS) {
            // cmd 批处理：for /L 循环输出，1>&2 重定向到 stderr
            body.append("@echo off\r\n")
                    .append("for /L %%i in (1,1,").append(lines).append(") do ")
                    .append("echo stderr-line-%%i-padding-padding-padding 1>&2\r\n")
                    .append("exit /b 3\r\n");
        } else {
            body.append("#!/bin/sh\n")
                    .append("i=0\n")
                    .append("while [ $i -lt ").append(lines).append(" ]; do\n")
                    .append("  echo \"stderr-line-$i-padding-padding-padding\" >&2\n")
                    .append("  i=$((i+1))\n")
                    .append("done\n")
                    .append("exit 3\n");
        }
        Path script = workDir.resolve(ProcessFixtures.WINDOWS ? "flood.cmd" : "flood.sh");
        Files.writeString(script, body.toString(), StandardCharsets.UTF_8);
        if (!ProcessFixtures.WINDOWS) {
            // Unix 下脚本必须有执行位，否则 sh 之外直接 exec 会失败
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return script;
    }

    private static List<String> runScriptCommand(Path script) {
        return ProcessFixtures.WINDOWS
                ? List.of("cmd", "/c", script.toString())
                : List.of("sh", script.toString());
    }

    @Test
    @DisplayName("挂死脚本在超时后被终止（AC9），不会拖满脚本自身的存活时间")
    void hangingScriptIsTerminatedOnTimeout() {
        int timeoutSeconds = 2;
        List<String> command = ProcessFixtures.sleeper(30);

        long start = System.nanoTime();
        String result = SkillTool.runScriptWithTimeout(command, workDir, "hang-script", timeoutSeconds);
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

        assertThat(result).contains("error").contains("超时");
        assertThat(result).as("错误文案要给出可操作的修复指引").contains("script_timeout_seconds");
        assertThat(elapsedSeconds)
                .as("应在超时点附近返回（%ds），而不是等脚本自然结束的 30s", timeoutSeconds)
                .isBetween((long) timeoutSeconds, (long) timeoutSeconds + 8);
    }

    @Test
    @DisplayName("脚本狂写 stderr 不会死锁：两路并发泵生效（AC9 的无死锁部分）")
    void floodingStderrDoesNotDeadlock() throws Exception {
        // 3000 行 ≈ 100KB，远超管道缓冲区（Windows 约 4KB）：
        // 旧的「先读完 stdout 再读 stderr」实现会在这里死锁到超时
        Path script = writeFloodScript(3000);

        long start = System.nanoTime();
        String result = SkillTool.runScriptWithTimeout(runScriptCommand(script), workDir, "flood", 15);
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

        assertThat(result).as("不得因死锁而超时").doesNotContain("超时");
        JsonNode node = MAPPER.readTree(result);
        assertThat(node.path("error").asText())
                .contains("exit 3")
                .contains("stderr-line");
        assertThat(elapsedSeconds).as("应快速完成").isLessThan(15);
    }

    @Test
    @DisplayName("正常脚本返回 stdout 与退出码 0")
    void successfulScriptReturnsOutput() throws Exception {
        String result = SkillTool.runScriptWithTimeout(
                ProcessFixtures.echoStdout("skill-ok"), workDir, "quick-script", 10);

        JsonNode node = MAPPER.readTree(result);
        assertThat(node.has("error")).as("不应报错：%s", result).isFalse();
        assertThat(node.path("output").asText()).contains("skill-ok");
        assertThat(node.path("exit_code").asInt()).isZero();
    }

    @Test
    @DisplayName("非零退出码回传 stderr 供模型判断")
    void failingScriptSurfacesStderr() throws Exception {
        String result = SkillTool.runScriptWithTimeout(
                ProcessFixtures.echoStderrThenFail("boom-detail", 7), workDir, "fail-script", 10);

        JsonNode node = MAPPER.readTree(result);
        assertThat(node.path("error").asText())
                .contains("exit 7")
                .contains("boom-detail");
    }

    @Test
    @DisplayName("超时上限常量有效：配置写天文数字也会被钳制")
    void timeoutUpperBoundIsEnforced() {
        assertThat(SkillTool.MAX_SCRIPT_TIMEOUT_SECONDS)
                .as("必须存在上限，否则配置写错会让护栏失效")
                .isLessThanOrEqualTo(3600);
    }
}
