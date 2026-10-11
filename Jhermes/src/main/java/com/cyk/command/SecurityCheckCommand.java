package com.cyk.command;

import com.cyk.security.CommandLevel;
import com.cyk.security.CommandPolicy;
import com.cyk.security.PolicyDecision;
import com.cyk.security.SecurityRuleSet;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * <h1>{@code Jhermes security check} —— 命令纯分类自检（JH-SPEC-001 P0-2 AC9 / 误杀排查）</h1>
 *
 * <p>对一批命令跑完整 {@link CommandPolicy} 分类流水线，打印每条的<b>级别 / 命中规则 /
 * 理由 / 处置</b>，但<b>绝不执行任何命令</b>、不联网、不消耗 token——因此在一次性沙盒里
 * 可安全反复运行，是 AC9 人工验证的第一层。</p>
 *
 * <h2>两种输入</h2>
 * <ul>
 *   <li>{@code --file <path>}：逐行读取，格式 {@code <命令> ||| <期望最低级别> ||| <说明>}，
 *       以 {@code #} 开头的行与空行忽略。给出期望级别时，实际级别 ≥ 期望记 PASS，
 *       否则 FAIL（进程退出码 1），可用于回归门禁。</li>
 *   <li>位置参数：直接分类若干命令（无期望值，仅展示），如
 *       {@code Jhermes security check "rm -rf /" "git status"}。</li>
 * </ul>
 *
 * <p>{@code --file} 传文件而非把命令作 CLI 参数：部分用例含双引号与管道
 * （如 {@code grep -r "rm -rf /" .}），Windows PowerShell 传给原生命令时会破坏引号。</p>
 */
@CommandLine.Command(name = "check", description = "对命令做纯分类自检（不执行、不联网、零 token）")
public class SecurityCheckCommand implements Callable<Integer> {

    /** 字段分隔符（避免与命令内容里的 | 冲突，故用三竖线）。 */
    private static final String FIELD_SEP = "|||";

    @CommandLine.Option(names = {"--file"}, description = "命令清单文件，每行 <命令> ||| <期望最低级别> ||| <说明>")
    private Path file;

    @CommandLine.Parameters(arity = "0..*", description = "也可直接给出要分类的命令")
    private List<String> commands;

    @Override
    public Integer call() throws Exception {
        CommandPolicy policy = new CommandPolicy(SecurityRuleSet.loadDefaultOnly());

        List<Case> cases = new ArrayList<>();
        if (file != null) {
            cases.addAll(loadFromFile(file));
        }
        if (commands != null) {
            for (String c : commands) {
                if (c != null && !c.isBlank() && !c.trim().startsWith("#")) {
                    cases.add(new Case(c.trim(), null, null));
                }
            }
        }
        if (cases.isEmpty()) {
            System.out.println("没有要检查的命令。用法：Jhermes security check --file <清单> 或 "
                    + "Jhermes security check \"<命令>\" ...");
            return 0;
        }

        int pass = 0;
        int fail = 0;
        System.out.println("=== Jhermes 命令安全自检（纯分类，未执行任何命令）===");
        System.out.println("平台: " + detectPlatform() + "   规则数: " + policy.ruleCount() + "   用例: " + cases.size());
        System.out.println();

        for (Case c : cases) {
            // 镜像 TerminalTool.runCommand 的真实门禁次序：敏感路径守卫【先于】命令策略。
            // 否则 .env/.pem/id_rsa 这类靠 SensitivePathGuard 硬拦的命令，自检会误报为 READ_ONLY，
            // 与实际处置不符，误导人工验证。
            String sensitive = com.cyk.util.SensitivePathGuard.check(c.command());
            PolicyDecision d = (sensitive != null)
                    ? new PolicyDecision(CommandLevel.BLOCKED,
                            List.of("sensitive-path:" + sensitive),
                            "触及敏感凭据/策略/审计路径（" + sensitive + "），永不放行",
                            policy.evaluate(c.command()).segments(),
                            c.command(), null, false)
                    : policy.evaluate(c.command());
            boolean ok = expectationMet(c.expected(), d.level());
            if (c.expected() != null) {
                if (ok) {
                    pass++;
                } else {
                    fail++;
                }
            }
            printCase(c, d, ok);
        }

        System.out.println();
        if (!cases.isEmpty() && pass + fail > 0) {
            System.out.println("--- 汇总: PASS=" + pass + " FAIL=" + fail + " 合计=" + cases.size() + " ---");
        }
        System.out.println("(本命令仅分类展示，未执行、未联网、未消耗 token)");
        return fail == 0 ? 0 : 1;
    }

    /** 期望级别达成判定：实际 ≥ 期望即 PASS（期望表示"最低危险级别"）。 */
    private static boolean expectationMet(CommandLevel expected, CommandLevel actual) {
        if (expected == null) {
            return true;
        }
        return actual.severity() >= expected.severity();
    }

    private void printCase(Case c, PolicyDecision d, boolean ok) {
        String tag = c.expected() == null ? "----" : (ok ? "PASS" : "FAIL");
        System.out.println("[" + tag + "] " + pad(d.level().name(), 10) + "  " + c.command());
        System.out.println("         规则: " + (d.matchedRuleIds().isEmpty() ? "(无，未知命令默认 WRITE)"
                : String.join(", ", d.matchedRuleIds())));
        System.out.println("         理由: " + d.reason());
        System.out.println("         处置: " + disposition(d.level()));
        if (c.expected() != null) {
            System.out.println("         期望≥: " + c.expected().name()
                    + (c.note() != null ? "   # " + c.note() : ""));
        }
    }

    private static String disposition(CommandLevel level) {
        return switch (level) {
            case READ_ONLY -> "自动放行";
            case WRITE -> "确认 [y]/[n]/[a]；非交互拒绝；--yolo 可跳过";
            case DANGEROUS -> "强确认（键入 yes）；非交互拒绝；--yolo 不跳过";
            case BLOCKED -> "硬拦截，永不执行（--yolo 也不例外）";
        };
    }

    /** 读取清单文件，解析 {@code cmd ||| expected ||| note}，忽略 # 与空行。 */
    private List<Case> loadFromFile(Path p) throws java.io.IOException {
        if (!Files.isRegularFile(p)) {
            System.err.println("命令清单文件不存在: " + p);
            return List.of();
        }
        List<Case> out = new ArrayList<>();
        for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split(java.util.regex.Pattern.quote(FIELD_SEP));
            String cmd = parts[0].trim();
            CommandLevel expected = null;
            String note = null;
            if (parts.length >= 2) {
                expected = CommandLevel.parse(parts[1].trim());
            }
            if (parts.length >= 3) {
                note = parts[2].trim();
            }
            if (!cmd.isEmpty()) {
                out.add(new Case(cmd, expected, note));
            }
        }
        return out;
    }

    private static String detectPlatform() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "windows" : "unix";
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /** 一条待检用例。 */
    private record Case(String command, CommandLevel expected, String note) {
    }
}
