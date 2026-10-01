package com.cyk.tool;

import com.cyk.bean.ToolEntry;
import com.cyk.util.SensitivePathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h1>终端命令执行工具（run_command）</h1>
 *
 * <p>让模型具备在用户本机执行命令行操作的能力：查询系统信息、执行构建测试、
 * 操作数据库（psql/mysql）、运行 git 等。这是 Agent「言出必行」的关键工具——
 * 没有它，模型只能口头指导用户敲命令，甚至幻觉出自己不存在的执行能力。</p>
 *
 * <h2>企业级安全设计（纵深防御）</h2>
 * <ol>
 *   <li><b>配置开关</b> — {@code tools.terminal_enabled=false} 可整体关闭注册</li>
 *   <li><b>危险命令黑名单</b> — {@link #isDangerous} 正则拦截 rm -rf /、format、
 *       DROP DATABASE、shutdown 等不可逆命令，命中直接拒绝执行并把原因返回给模型，
 *       模型会如实转告用户（提示词层还有第二道「破坏性操作先确认」约束）</li>
 *   <li><b>超时强杀</b> — 命令超时后 destroyForcibly，防止挂死进程拖垮会话</li>
 *   <li><b>输出截断</b> — stdout/stderr 各限 8KB，防止海量输出撑爆对话上下文</li>
 *   <li><b>禁止交互</b> — 子进程 stdin 直接关闭，需要交互输入的命令快速失败而非永久挂起</li>
 * </ol>
 *
 * <h2>与 Claude Code / Cursor 的同类设计对比</h2>
 * <p>业内 Agent 的终端工具通常采用「默认允许 + 黑名单拦截 + 用户可配置白名单/确认机制」。
 * 本项目是单用户本地 CLI，采用黑名单 + 配置开关的轻量方案；若未来多租户化，
 * 应在 dispatch 前加交互式确认（y/n gate）。</p>
 */
public class TerminalTool {

    private static final Logger logger = LoggerFactory.getLogger(TerminalTool.class);

    /** 默认超时（秒）：覆盖绝大多数查询/构建类命令 */
    static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 超时上限（秒）：防止模型传入离谱值导致会话挂死 */
    static final int MAX_TIMEOUT_SECONDS = 300;

    /** 单路输出（stdout 或 stderr）保留的最大字符数，超出后截断并标注 */
    static final int MAX_OUTPUT_CHARS = 8192;

    /** 命令序号计数器：用于终端回显 [run #N]，方便用户在滚动输出中定位每条命令 */
    private static final AtomicLong COMMAND_SEQ = new AtomicLong();

    /**
     * 同一命令（归一化后）在同一会话内的重复执行计数。
     *
     * <p>针对轻量模型的典型失败模式：搜不到目标就换个几乎一样的命令反复全盘扫描
     * （实测一次“删知识库”跑出 47 条命令，token 平方级燃烧）。达到阈值后
     * 直接拒绝并返回「换策略」指令，打断死循环。</p>
     *
     * <p>进程级单会话 CLI，static Map 即可；key=压缩空白后的小写命令。
     * 超过 {@link #COMMAND_COUNTS_LIMIT} 条时整体清空（计数仅作护栏而非审计，丢失无害）。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> COMMAND_COUNTS =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 同一命令最多允许重复执行次数（第 N+1 次被拒） */
    static final int MAX_SAME_COMMAND_REPEATS = 2;

    /** 命令计数表容量上限：达到后清空重建，防止极端长会话下 Map 无限增长 */
    private static final int COMMAND_COUNTS_LIMIT = 500;

    /**
     * 检查命令是否触碰敏感凭据路径（包级可见便于单测）。
     *
     * <p>实现已下沉到 {@link SensitivePathGuard}：终端工具与文件工具
     * （{@code read_file}/{@code write_file}/{@code grep_files}/{@code search_files}）
     * 共享同一份黑名单，避免「终端拦了、文件工具没拦」的防护不对齐。
     * 保留本方法是为了不破坏既有单测与本类的调用点。</p>
     *
     * <p>匹配规则（小写化 + 右边界校验，防 {@code .sshare} 误伤）见
     * {@link SensitivePathGuard#check(String)}。</p>
     *
     * @return 命中的敏感标记；未命中返回 null
     */
    static String checkSensitive(String command) {
        return SensitivePathGuard.check(command);
    }

    private TerminalTool() {
    }

    /** 测试钩子：清空命令重复计数，保证各测试用例之间状态隔离（包级可见，不进入公共 API）。 */
    static void resetCommandCounts() {
        COMMAND_COUNTS.clear();
    }

    /**
     * 重复护栏的命令归一化：小写化 + 连续空白压缩为单空格。
     *
     * <p>模型的典型重试姿势是只改大小写或空格（“Get-ChildItem”→“get-childitem”），
     * 归一化后视为同一命令，护栏才能拦住。</p>
     */
    static String normalizeForRepeatGuard(String command) {
        return command.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    // =========================================================================
    // 危险命令黑名单
    // =========================================================================

    /**
     * 不可逆/灾难性命令特征（小写归一化后做正则匹配）。
     *
     * <p>拦截边界设计（重要）：只硬拦截「系统级灾难」——格式化磁盘、删根目录、关机等
     * 一旦执行整机不可用的命令；而数据级破坏操作（DROP DATABASE、批量删文件等）
     * <b>不在此拦截</b>，改由提示词层要求模型「先向用户说明影响并获得确认」——
     * 因为用户对话中的明确指令（如“直接帮我删除这个知识库”）本身就是确认，
     * 工具层再拦会导致模型永远无法完成用户明确要求的任务。</p>
     */
    private record DangerPattern(java.util.regex.Pattern pattern, String reason) {
    }

    private static final List<DangerPattern> DANGEROUS_PATTERNS = List.of(
        // rm -rf 后跟 Unix 绝对路径（/、/home/...、/var 等）或家目录 ~、或裸 . 与 ./：
        // 全部拦截。相对子目录（rm -rf ./target、rm -rf build）是正常的清理操作，放行。
        // 注意：正则曾漏掉「/后跟多级路径」场景（/home/user、/var），单测修复（2026-09-28）。
        new DangerPattern(java.util.regex.Pattern.compile(
            "\\brm\\s+(-[a-z]*[rf][a-z]*\\s+)+(/[^\\s\"']*|~[^\\s\"']*|\\.(/)?)([\\s\"']|$)"),
            "递归强制删除根目录/家目录"),
        new DangerPattern(java.util.regex.Pattern.compile(
            "\\brm\\s+(-[a-z]*[rf][a-z]*\\s+)+[a-z]:[\\\\/](\\s|$|[\"'])"),
            "递归强制删除整个磁盘"),
        new DangerPattern(java.util.regex.Pattern.compile(
            "\\b(format\\s+[a-z]:|mkfs(\\.\\w+)?\\s|diskpart\\b)"),
            "格式化磁盘/分区操作"),
        new DangerPattern(java.util.regex.Pattern.compile(
            "\\b(shutdown(\\s|$)|restart-computer|init\\s+0|halt(\\s|$))"),
            "关机/重启系统"),
        new DangerPattern(java.util.regex.Pattern.compile(
            "\\b(del|rd|rmdir)\\s+[^|;&]*\\s[a-z]:[\\\\/](\\s|$)"),
            "删除整个磁盘根目录"),
        new DangerPattern(java.util.regex.Pattern.compile(
            ":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&?\\s*\\}\\s*;?\\s*:\\b"),
            "疑似 fork 炸弹/资源耗尽攻击")
    );

    /**
     * 判断命令是否命中危险黑名单。
     *
     * <p>包级可见：供单元测试直接验证拦截规则，无需真实执行命令。</p>
     *
     * @param command 原始命令串
     * @return 命中原因；未命中返回 null
     */
    static String checkDangerous(String command) {
        String normalized = command.toLowerCase(Locale.ROOT);
        for (DangerPattern dp : DANGEROUS_PATTERNS) {
            if (dp.pattern().matcher(normalized).find()) {
                return dp.reason();
            }
        }
        return null;
    }

    /** 兼容旧命名：是否危险命令（true=拦截）。 */
    static boolean isDangerous(String command) {
        return checkDangerous(command) != null;
    }

    // =========================================================================
    // 核心执行逻辑
    // =========================================================================

    /**
     * run_command 的 handler：执行命令、收集输出、统一 JSON 返回。
     *
     * <p>执行流程：</p>
     * <pre>
     *   模型发起 tool call
     *      ↓
     *   参数校验（command 必填）
     *      ↓
     *   危险命令黑名单检查 → 命中则拒绝并返回原因
     *      ↓
     *   ProcessBuilder 启动子进程（workdir/shell 解析）
     *      ↓
     *   双线程泵 stdout/stderr（防管道缓冲区死锁）
     *      ↓
     *   waitFor(timeout) → 超时 destroyForcibly
     *      ↓
     *   输出截断 + JSON 返回
     * </pre>
     */
    public static String runCommand(Map<String, Object> args) {
        // 第一步：参数解析与校验
        String command = asString(args.get("command"));
        if (command == null || command.isBlank()) {
            return ToolRegistry.toolError("command 参数不能为空");
        }
        command = command.trim();

        int timeout = DEFAULT_TIMEOUT_SECONDS;
        Object timeoutArg = args.get("timeout_seconds");
        if (timeoutArg instanceof Number n) {
            timeout = Math.max(1, Math.min(n.intValue(), MAX_TIMEOUT_SECONDS));
        }

        // 第二步：危险命令拦截（黑名单）
        String danger = checkDangerous(command);
        if (danger != null) {
            logger.warn("已拦截危险命令: {} （原因: {}）", command, danger);
            return ToolRegistry.toolError(
                "命令被安全策略拦截：" + danger
                + "。此类不可逆操作不允许自动执行，请明确告知用户风险并由用户手动操作。");
        }

        // 第二步之一：敏感凭据文件访问拦截（防密钥泄露进对话历史被持久化）
        String sensitive = checkSensitive(command);
        if (sensitive != null) {
            logger.warn("已拦截敏感路径访问: {} （标记: {}）", command, sensitive);
            return ToolRegistry.toolError(
                "命令被安全策略拦截：触及敏感凭据路径（" + sensitive
                + "）。用户的密钥/token 绝不允许被读入对话上下文。"
                + "如确需相关配置信息，请告知用户自行查看。");
        }

        // 第二步之二：重复命令护栏——同一命令第 3 次起拒绝，打断“搜不到就换个姿势再全盘扫”的死循环。
        // 计数在拦截之前就累加：被拒的命令也计入，否则模型可以用同一条命令无限重试。
        if (COMMAND_COUNTS.size() >= COMMAND_COUNTS_LIMIT) {
            COMMAND_COUNTS.clear();
        }
        String commandKey = normalizeForRepeatGuard(command);
        int repeats = COMMAND_COUNTS.merge(commandKey, 1, Integer::sum);
        if (repeats > MAX_SAME_COMMAND_REPEATS) {
            logger.warn("拒绝重复命令（第 {} 次）: {}", repeats, command);
            return ToolRegistry.toolError(
                "这条命令在本会话已执行过 " + MAX_SAME_COMMAND_REPEATS + " 次，结果不会变，已拒绝重复执行。"
                + "不要再用微调后的同类搜索命令重试。请换策略："
                + "①目标应用自带的能力优先（如知识库操作用 rag_list/rag_delete，文件查找用 search_files）；"
                + "②直接问用户具体路径/凭据位置；③如实告知用户用现有工具无法完成。");
        }

        // 第三步：工作目录解析（默认当前进程目录，即用户启动 Jhermes 的位置）
        Path workDir;
        try {
            String workDirArg = asString(args.get("workdir"));
            workDir = (workDirArg == null || workDirArg.isBlank())
                ? Paths.get(System.getProperty("user.dir"))
                : Paths.get(workDirArg).toAbsolutePath().normalize();
            if (!java.nio.file.Files.isDirectory(workDir)) {
                return ToolRegistry.toolError("工作目录不存在: " + workDir);
            }
        } catch (Exception e) {
            return ToolRegistry.toolError("工作目录无效: " + e.getMessage());
        }

        // 第四步：终端回显——让用户实时看到模型正在执行什么命令（透明性原则）
        long seq = COMMAND_SEQ.incrementAndGet();
        String cwdDisplay = shortenHome(workDir.toString());
        System.out.println("\n┌─ [run #" + seq + "] " + cwdDisplay);
        System.out.println("│ $ " + command);
        System.out.println("└─ 执行中（超时 " + timeout + "s）...");

        // 第五步：构建进程并执行
        try {
            ProcessBuilder pb = buildProcess(command, asString(args.get("shell")), workDir);
            // 子进程禁止交互：立即关闭 stdin，需要输入的命令会快速报错而非永久挂起
            pb.redirectErrorStream(false);

            Process process = pb.start();
            process.getOutputStream().close();

            // 双线程异步泵输出：stdout/stderr 管道缓冲区（Windows 约 4KB）写满后
            // 子进程会阻塞；若串行读两路，子进程写 stderr 时主线程在读 stdout 就会死锁
            CompletableFuture<String> stdoutFuture =
                CompletableFuture.supplyAsync(() -> pumpStream(process, true));
            CompletableFuture<String> stderrFuture =
                CompletableFuture.supplyAsync(() -> pumpStream(process, false));

            // 第六步：限时等待 + 超时强杀
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                String partialStdout = safeGet(stdoutFuture);
                String partialStderr = safeGet(stderrFuture);
                System.out.println("  ⚠ 命令超时（" + timeout + "s），已强制终止");
                return ToolRegistry.toolResult(Map.of(
                    "command", command,
                    "exit_code", -1,
                    "timed_out", true,
                    "timeout_seconds", timeout,
                    "stdout", truncate(partialStdout),
                    "stderr", truncate(partialStderr),
                    "note", "命令超时被强制终止。长耗时命令请增大 timeout_seconds，或拆分执行。"));
            }

            int exitCode = process.exitValue();
            String stdout = truncate(safeGet(stdoutFuture));
            String stderr = truncate(safeGet(stderrFuture));

            // 第七步：终端回显执行结果摘要（前几行），完整内容返回给模型
            echoResultSummary(seq, exitCode, stdout, stderr);

            return ToolRegistry.toolResult(Map.of(
                "command", command,
                "exit_code", exitCode,
                "timed_out", false,
                "stdout", stdout,
                "stderr", stderr,
                "workdir", workDir.toString()));

        } catch (IOException e) {
            System.out.println("  ✗ 命令启动失败: " + e.getMessage());
            logger.warn("命令执行失败: {} -> {}", command, e.getMessage());
            return ToolRegistry.toolError("命令启动失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolRegistry.toolError("命令执行被中断");
        }
    }

    /**
     * 构建 ProcessBuilder：按操作系统与 shell 参数选择命令行。
     *
     * <ul>
     *   <li>Windows 默认 PowerShell（现代 Windows 的主力 shell，模型生成的命令多为 PS 语法）；
     *       shell=cmd 时切换 cmd /c（兼容 .bat 场景）</li>
     *   <li>Linux/macOS 默认 /bin/bash -lc（login shell 加载用户 PATH/profile）；
     *       shell=sh 时切换 /bin/sh -c</li>
     * </ul>
     */
    static ProcessBuilder buildProcess(String command, String shell, Path workDir) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String shellChoice = shell == null ? "" : shell.trim().toLowerCase(Locale.ROOT);

        ProcessBuilder pb;
        if (isWindows) {
            if ("cmd".equals(shellChoice)) {
                // chcp 65001 切代码页为 UTF-8，避免中文输出乱码
                pb = new ProcessBuilder("cmd.exe", "/c", "chcp 65001>nul & " + command);
            } else {
                // -NoProfile 加快启动并避免用户 profile 干扰；-NonInteractive 禁止交互式提示；
                // 前置设 OutputEncoding=UTF8：子进程输出（含 psql/git 的中文）统一按 UTF-8 采集，
                // 避免 Windows 默认 GBK 代码页导致的乱码（pumpStream 侧还有解码兜底）
                pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command",
                    "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; " + command);
            }
        } else {
            if ("sh".equals(shellChoice)) {
                pb = new ProcessBuilder("/bin/sh", "-c", command);
            } else {
                pb = new ProcessBuilder("/bin/bash", "-lc", command);
            }
        }
        pb.directory(workDir.toFile());
        return pb;
    }

    /**
     * 泵干一路输出流并返回全文。
     *
     * <p>编码策略：Windows 控制台子进程默认输出 GBK（代码页 936），
     * 强行按 UTF-8 解码中文会乱码；PowerShell 子进程可通过启动参数要求 UTF-8，
     * 这里统一用「UTF-8 优先、平台默认编码兜底」——先按 UTF-8 严格解码，
     * 失败（出现替换符说明不是合法 UTF-8）则回退平台默认编码重解。</p>
     */
    static String pumpStream(Process process, boolean stdout) {
        Charset utf8 = StandardCharsets.UTF_8;
        try (var raw = stdout ? process.getInputStream() : process.getErrorStream()) {
            byte[] bytes = raw.readAllBytes();
            String asUtf8 = new String(bytes, utf8);
            // U+FFFD 替换符大量出现基本可断定不是 UTF-8 编码，回退平台默认（Windows=GBK）
            if (asUtf8.indexOf('\uFFFD') >= 0) {
                String asNative = new String(bytes, Charset.defaultCharset());
                if (asNative.indexOf('\uFFFD') < 0) {
                    return asNative;
                }
            }
            return asUtf8;
        } catch (IOException e) {
            logger.debug("读取子进程输出失败: {}", e.getMessage());
            return "";
        }
    }

    // =========================================================================
    // 输出处理辅助
    // =========================================================================

    /** 安全获取异步泵结果（泵线程异常不影响主流程） */
    private static String safeGet(CompletableFuture<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException e) {
            return "";
        }
    }

    /** 截断超长输出，保留头尾并在中间标注省略量（模型需要知道输出被截断过） */
    static String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        int head = MAX_OUTPUT_CHARS * 3 / 4;
        int tail = MAX_OUTPUT_CHARS / 4;
        int omitted = text.length() - head - tail;
        return text.substring(0, head)
            + "\n...[中间省略 " + omitted + " 字符]...\n"
            + text.substring(text.length() - tail);
    }

    /** 在终端回显执行结果摘要：完整输出可能很长，屏幕只显示前 6 行 */
    private static void echoResultSummary(long seq, int exitCode, String stdout, String stderr) {
        String status = exitCode == 0 ? "✓ 退出码 0" : "✗ 退出码 " + exitCode;
        System.out.println("  [" + seq + "] " + status);
        printHead(stdout, 6, "  │ ");
        if (exitCode != 0 && stderr != null && !stderr.isBlank()) {
            System.out.println("  │ stderr:");
            printHead(stderr, 4, "  │ ");
        }
    }

    /** 按行打印前 maxLines 行，超出标注还有多少行（完整内容在工具结果里给模型） */
    private static void printHead(String text, int maxLines, String prefix) {
        if (text == null || text.isBlank()) {
            return;
        }
        String[] lines = text.split("\r?\n", -1);
        int shown = 0;
        for (String line : lines) {
            if (shown >= maxLines) {
                System.out.println(prefix + "...（共 " + lines.length + " 行，其余已省略）");
                break;
            }
            System.out.println(prefix + line);
            shown++;
        }
    }

    /** 把用户主目录前缀缩写为 ~，让回显更短 */
    private static String shortenHome(String path) {
        String home = System.getProperty("user.home");
        if (home != null && path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        return path;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // =========================================================================
    // 工具注册
    // =========================================================================

    /**
     * 注册 run_command 工具。
     *
     * <p>schema 的 description 是模型决定「何时调用」的唯一依据，
     * 必须把能力边界（可做什么）、平台差异（PS 语法）、安全约束（黑名单）写清楚。</p>
     */
    public static void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
            .name("run_command")
            .toolset("terminal")
            .schema(Map.of(
                "description", "在用户本机终端执行 shell 命令并返回输出。"
                    + "适用于：查询系统信息（时间/进程/端口/磁盘）、执行构建与测试（mvn/npm/pytest）、"
                    + "数据库操作（psql/mysql）、git 操作、运行脚本等。"
                    + "Windows 默认 PowerShell（多命令用 ; 分隔，不要用 &&），可用 shell 参数切换 cmd；"
                    + "Linux/macOS 默认 bash。系统级灾难命令（格式化磁盘、删根目录、关机等）会被自动拦截；"
                    + "数据级破坏操作（删库、批量删文件）执行前必须先向用户说明影响并获得确认。",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "command", Map.of("type", "string",
                            "description", "要执行的完整命令串"),
                        "workdir", Map.of("type", "string",
                            "description", "工作目录（绝对路径），缺省为用户启动 Jhermes 时所在目录"),
                        "timeout_seconds", Map.of("type", "integer",
                            "description", "超时秒数，默认 30，上限 300；构建/测试等长耗时命令建议 120"),
                        "shell", Map.of("type", "string",
                            "description", "指定 shell：Windows 可选 cmd（默认 powershell）；"
                                + "Linux/macOS 可选 sh（默认 bash）")),
                    "required", List.of("command"))))
            .handler(TerminalTool::runCommand)
            .emoji("💻")
            .description("Execute shell command in local terminal")
            .build());
        logger.info("终端工具 run_command 已注册");
    }
}
