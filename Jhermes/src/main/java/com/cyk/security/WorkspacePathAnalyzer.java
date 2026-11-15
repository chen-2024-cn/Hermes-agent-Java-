package com.cyk.security;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h1>工作区路径语义分析（JH-SPEC-001 P0-2 R9）</h1>
 *
 * <p>递归删除、越界访问是否危险，取决于<b>目标路径的语义</b>，而不是命令串里有没有出现
 * 某个子串。改造前的实现是「整串小写化跑正则」，既会误杀（引号里的字样）也会漏过
 * （变量写法）。本类把路径判定收敛为精确语义，供 {@link CommandPolicy} 调用。</p>
 *
 * <h2>两类判定</h2>
 * <ul>
 *   <li>{@link #isCatastrophicDeleteTarget} 递归删除的<b>灾难目标</b>：根目录 {@code /}、
 *       家目录 {@code ~}/<code>$HOME</code>、整盘 {@code C:\}、裸 {@code .}/{@code ./}、
 *       任意绝对路径（Unix {@code /...} 或 Windows 盘符）——命中即 BLOCKED。<br>
 *       「任意绝对路径」与改造前行为一致：递归删绝对位置极可能是灾难，宁可保守拦截。</li>
 *   <li>{@link #escapesWorkspace} <b>越界</b>（但不灾难）：{@code ..} 穿越、{@code ~}、
 *       环境变量家目录、绝对路径但不在工作区内——命中则升一档（READ_ONLY→WRITE，WRITE→DANGEROUS）。
 *       这是 R9 的「尽力检测」，文档明示非隔离。</li>
 * </ul>
 *
 * <h2>平台感知与「正向判定」原则（P0-2 缺陷修复）</h2>
 * <p>升档是<b>保守启发式</b>：误报只多一次确认（体验损耗），漏报却可能放过真实越界。
 * 因此本类只在能<b>正向判定「它不是路径」</b>时才豁免，其余情况一律保持保守：</p>
 * <ul>
 *   <li><b>Windows cmd 开关</b>：{@code date /t} 的 {@code /t}、{@code /s}、{@code /f} 是参数开关，
 *       不是 Unix 绝对路径。仅当平台为 Windows 且 token 为 {@code /} + 1~2 个字母数字时豁免；
 *       限短长度保证 {@code /tmp}、{@code /etc}、{@code /var}、{@code /usr}、{@code /bin}
 *       这类真路径仍被当作路径，Unix 平台上行为零变化。</li>
 *   <li><b>环境变量的三态判定</b>：原来凡 {@code %...%} / {@code $env:...} 一律当越界，
 *       而 {@code echo %date%} 里的 {@code %date%} 根本不是位置信息。改为：
 *       ①已知指向工作区外（userprofile/appdata/temp/windir 等）→ 越界；
 *       ②已知非路径值（date/time/random/cd/os 等）→ 不越界；
 *       ③<b>未知变量 → 仍按越界</b>（宁误报不漏报，绝不因“不认识”而放宽）。</li>
 * </ul>
 */
public final class WorkspacePathAnalyzer {

    /**
     * Windows cmd 参数开关形态：{@code /} + 1~2 个字母数字（已小写化）。
     *
     * <p>长度上限取 2 是安全边界：常见开关均为单字符或双字符（{@code /t /s /f /q /v /a /ad /nh}），
     * 而真实的 Unix 一级目录名至少 3~4 字符（tmp/etc/var/usr/bin/dev/sys），不会被误判为开关。</p>
     */
    private static final Pattern WINDOWS_SWITCH = Pattern.compile("/[a-z0-9]{1,2}");

    /** cmd 环境变量写法 {@code %VAR%}（可嵌在更长字符串中，如 {@code %temp%\x.txt}）。 */
    private static final Pattern CMD_ENV_VAR = Pattern.compile("%([a-z0-9_]+)%");

    /** PowerShell 环境变量写法 {@code $env:VAR}。 */
    private static final Pattern PS_ENV_VAR = Pattern.compile("\\$env:([a-z0-9_]+)");

    /**
     * 已知<b>不是位置信息</b>的环境变量（小写），命中则不判越界。
     *
     * <p>本名单是「不越界」的<b>唯一出口</b>：不在名单内的变量（无论是
     * {@code %userprofile%} 这类确定指向工作区外，还是本类不认识的自定义变量）
     * 一律仍按越界处理。因此不需再维护一份「已知在外」名单——未知即保守，
     * 两个集合反而会随时间漂移并产生矛盾。</p>
     *
     * <p>故意保持短小：只收录“肯定是值而不是路径”的那几个。
     * {@code %cd%} 是当前目录（即工作区本身），其余均为日期/时间/主机类标量。</p>
     */
    private static final Set<String> NON_PATH_ENV_VARS = Set.of(
            "date", "time", "random", "errorlevel", "username", "path", "cd",
            "os", "computername", "processor_architecture", "cmdcmdline");

    private WorkspacePathAnalyzer() {
    }

    /**
     * 判断一个路径参数是否为「递归删除的灾难目标」。
     *
     * @param rawPath 原始路径参数（可能带引号、大小写混杂）
     * @return true 表示灾难目标，应 BLOCKED
     */
    public static boolean isCatastrophicDeleteTarget(String rawPath) {
        String p = normalize(rawPath);
        if (p.isEmpty()) {
            return false;
        }
        // 根目录及通配
        if (p.equals("/") || p.equals("/*") || p.startsWith("//") || p.equals("*")) {
            return true;
        }
        // 家目录
        if (p.equals("~") || p.startsWith("~/") || p.startsWith("~\\")
                || p.equals("$home") || p.equals("${home}") || p.equals("%userprofile%")
                || p.startsWith("$env:userprofile")) {
            return true;
        }
        // 裸当前目录的递归删除（rm -rf . / ./）
        if (p.equals(".") || p.equals("./") || p.equals(".\\")) {
            return true;
        }
        // Windows 盘符根：c: / c:\ / c:/
        if (p.matches("[a-z]:[\\\\/]?$")) {
            return true;
        }
        // 任意绝对路径（Unix / 或 Windows 盘符开头）：递归删除绝对位置一律视为灾难
        if (p.startsWith("/") || p.matches("[a-z]:[\\\\/].*")) {
            return true;
        }
        // 关键系统目录（即使写成相对形式也拦）
        if (isSystemDir(p)) {
            return true;
        }
        return false;
    }

    /**
     * 判断路径是否越出工作区（但不属于灾难目标）——<b>Unix 语义</b>的兼容重载。
     *
     * <p>保留旧签名以兼容既有调用方与测试：不传平台时不应用 Windows cmd 开关豁免，
     * 即 {@code /t} 仍被视为绝对路径。需要平台感知的调用方应用
     * {@link #escapesWorkspace(String, Path, boolean)}。</p>
     *
     * @param rawPath 原始路径参数
     * @param workDir 工作区根（启动目录）；null 表示未知，按越界保守处理绝对路径
     * @return true 表示越界，应升一档
     */
    public static boolean escapesWorkspace(String rawPath, Path workDir) {
        return escapesWorkspace(rawPath, workDir, false);
    }

    /**
     * 判断路径是否越出工作区（平台感知版，P0-2 缺陷修复的正入口）。
     *
     * <p><b>判定顺序有严格讲究</b>（每一项都不能随便换位）：</p>
     * <ol>
     *   <li>Windows 开关豁免必须在绝对路径判定之前，否则 {@code /t} 会先被
     *       {@code startsWith("/")} 捕获；</li>
     *   <li>{@code ..} / {@code ~} / 绝对路径这三类<b>确定是路径且确定越界</b>的信号，
     *       必须优先于环境变量三态——像 {@code C:\Users\%username%\docs} 这种
     *       「绝对路径里嵌了个非路径变量」的情况，不能因为 {@code %username%} 不是路径
     *       就放过；否则本类的判定就会默默依赖分段器的 unparseable 保守兼底，
     *       一旦分段器行为变化就会出现真实漏报；</li>
     *   <li>环境变量三态放最后：到这一步说明串里没有确定的路径信号，
     *       才去问「这个变量到底指不指向工作区外」。</li>
     * </ol>
     *
     * @param rawPath         原始路径参数
     * @param workDir         工作区根；null 表示未知
     * @param windowsPlatform 当前平台是否为 Windows；false 时完全不放宽（Unix 语义）
     * @return true 表示越界，应升一档
     */
    public static boolean escapesWorkspace(String rawPath, Path workDir, boolean windowsPlatform) {
        String p = normalize(rawPath);
        if (p.isEmpty()) {
            return false;
        }
        // ① Windows cmd 开关（/t、/s、/f）：正向判定为「不是路径」，仅 Windows 生效
        if (windowsPlatform && WINDOWS_SWITCH.matcher(p).matches()) {
            return false;
        }
        // ② 相对路径穿越
        if (p.equals("..") || p.startsWith("../") || p.startsWith("..\\")) {
            return true;
        }
        // ③ 家目录：几乎必然在工作区外
        if (p.startsWith("~") || p.equals("$home") || p.equals("${home}")) {
            return true;
        }
        // ④ 绝对路径（优先于环境变量判定，理由见方法注释）
        if (p.startsWith("/") || p.matches("[a-z]:[\\\\/].*")) {
            if (workDir == null) {
                return true;// 工作区未知时，绝对路径按越界保守处理
            }
            try {
                Path target = Paths.get(rawPathUnquote(rawPath));
                // Non-native absolute syntax must not become workspace-relative on this host.
                if (!target.isAbsolute()) return true;
                target = target.normalize();
                Path root = workDir.toAbsolutePath().normalize();
                return !target.startsWith(root);
            } catch (RuntimeException e) {
                return true;// 无法解析的路径按越界处理（保守）
            }
        }
        // ⑤ 环境变量的三态判定（未知变量保守当越界，见类注释）
        String envVar = firstEnvVarName(p);
        if (envVar != null) {
            return !NON_PATH_ENV_VARS.contains(envVar);
        }
        return false;
    }

    /**
     * 提取字符串中<b>首个</b>环境变量的名称（已小写），兼容 cmd 与 PowerShell 两种写法。
     *
     * <p>用「搜索」而非「整串相等」：{@code cat %temp%\x.txt} 这类嵌入式引用必须同样被识别，
     * 否则会退回旧行为（任意以 % 开头即越界）。两种写法都查，以便
     * {@code dir $env:APPDATA} 与 {@code dir %APPDATA%} 得到一致结论。</p>
     *
     * @return 变量名（小写）；串中不含环境变量时返回 null
     */
    private static String firstEnvVarName(String normalizedLower) {
        for (Pattern pattern : new Pattern[]{CMD_ENV_VAR, PS_ENV_VAR}) {
            Matcher matcher = pattern.matcher(normalizedLower);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** 关键系统目录（相对写法的兜底拦截）。 */
    private static boolean isSystemDir(String p) {
        String stripped = p.replaceAll("[\\\\/]+$", "");
        return switch (stripped) {
            case "etc", "usr", "bin", "sbin", "boot", "dev", "proc", "sys",
                 "var", "home", "root", "windows", "system32", "program files" -> true;
            default -> false;
        };
    }

    /** 归一化：去包裹引号、小写化、去首尾空白（用于关键词与模式比对）。 */
    private static String normalize(String raw) {
        return rawPathUnquote(raw).toLowerCase(Locale.ROOT).trim();
    }

    /** 去掉首尾成对的引号（双引号或单引号），保留内部原样。 */
    private static String rawPathUnquote(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
