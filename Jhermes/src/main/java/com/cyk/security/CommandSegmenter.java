package com.cyk.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <h1>命令分段器（JH-SPEC-001 P0-2 R1）</h1>
 *
 * <p>整条命令 → 若干个可独立分类的 {@link CommandSegment}。这是命令门禁的地基：
 * 分类的正确性完全取决于分段的正确性。改造前的实现是「整串小写化后跑正则」，
 * 因此同时存在误杀与漏过（Phase 0 B7 实测：82 条用例中 2 条误杀、44 条漏过）。</p>
 *
 * <h2>处理的问题</h2>
 * <ol>
 *   <li><b>引号内是数据</b>：{@code grep -r "rm -rf /" .} 不会把引号内容当命令。</li>
 *   <li><b>分隔符切段</b>：{@code ; && || | &} 与换行；PowerShell 的 {@code ;} 同理。</li>
 *   <li><b>子命令替换</b>：{@code $(...)} 与 bash 反引号 {@code `...`} 的内部递归解析；
 *       PowerShell 的行续接反引号（{@code `} + 换行）不视为子命令。</li>
 *   <li><b>嵌套 shell 递归</b>：{@code cmd /c}、{@code powershell -Command}、
 *       {@code bash -c} 的内层字符串再分段一层（R1 明确要求）。</li>
 *   <li><b>不可解析即保守</b>：变量作命令名、{@code -EncodedCommand}、{@code eval} /
 *       {@code Invoke-Expression}、动态拼接 → 标记 {@code unparseable}，
 *       由 {@link CommandPolicy} 至少按 WRITE 处理（R2：默认保守，不得默认放行）。</li>
 * </ol>
 *
 * <h2>明确的边界（与 D-03 一致）</h2>
 * <p>这不是一个完整的 shell/PowerShell 解析器，也不提供隔离。它做的是
 * 「结构化理解 + 保守回退」：看不懂就升级到需确认，而不是猜一个级别放行。
 * 深度限制为 {@link #MAX_DEPTH}，超限直接判不可解析，避免恶意嵌套导致指数展开。</p>
 */
public final class CommandSegmenter {

    private static final Logger logger = LoggerFactory.getLogger(CommandSegmenter.class);

    /** 嵌套解析的最大深度：超限即判不可解析，防止恶意嵌套导致展开爆炸。 */
    static final int MAX_DEPTH = 3;

    /** 会把后续字符串当作命令交给 shell 执行的「壳」程序与其触发标志。 */
    private static final Set<String> CMD_SHELLS = Set.of("cmd", "command");
    private static final Set<String> PS_SHELLS = Set.of("powershell", "pwsh", "powershell.exe", "pwsh.exe");
    private static final Set<String> UNIX_SHELLS = Set.of("bash", "sh", "zsh", "ksh", "dash", "csh");

    /**
     * 提权包装器：{@code sudo <cmd>} 后面的参数是被提权执行的真实命令。
     *
     * <p>不解包就会漏掉 {@code sudo rm -rf /}——外层只看到 sudo（DANGEROUS），
     * 内层的 {@code rm -rf /}（BLOCKED）被当成普通参数忽略。解包后外层提权 + 内层删根
     * 分别评估，取最严 → BLOCKED。</p>
     */
    private static final Set<String> PRIVILEGE_WRAPPERS = Set.of("sudo", "doas");

    /** 求值类命令：参数内容在运行期才确定，静态无法可靠分类。 */
    private static final Set<String> EVAL_LIKE = Set.of("eval", "iex", "invoke-expression", "source", ".");

    /** 常见可执行文件扩展名（归一化可执行名时剥离）。 */
    private static final Set<String> EXECUTABLE_SUFFIXES =
            Set.of(".exe", ".cmd", ".bat", ".com", ".ps1", ".sh", ".msi", ".jar");

    public CommandSegmenter() {
    }

    /**
     * 把整条命令拆分为若干段（含嵌套内层段，内层的 {@code depth} 更大）。
     *
     * @param command 原始命令串（可含多段与嵌套）
     * @return 段列表；入参为空时返回空列表。列表顺序为「外层段在前、内层展开在后」，
     *         分类时取全部段中最严的一档即可，顺序不影响结果
     */
    public List<CommandSegment> segment(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        List<CommandSegment> result = new ArrayList<>();
        try {
            for (RawSegment raw : tokenize(command)) {
                expandRawSegment(raw, 0, result);
            }
        } catch (RuntimeException e) {
            // 分段器自身出错时绝不能「当作安全」放行：整条命令判为不可解析（保守回退）
            logger.warn("命令分段失败，按不可解析处理: {} -> {}", command, e.toString());
            return List.of(CommandSegment.unparseable(command, 0,
                    "分段器解析异常: " + e.getClass().getSimpleName()));
        }
        return result.isEmpty()
                ? List.of(CommandSegment.empty(command, 0))
                : List.copyOf(result);
    }

    // =========================================================================
    // 段展开（含嵌套与子命令）
    // =========================================================================

    /**
     * 把一个原始段转成 {@link CommandSegment}，并递归展开其嵌套部分。
     */
    private void expandRawSegment(RawSegment raw, int depth, List<CommandSegment> out) {
        List<Token> tokens = raw.tokens();
        if (tokens.isEmpty()) {
            return;
        }

        // ① 先递归展开 $(...) 与反引号内的子命令：它们可能藏着真正的危险动作
        List<String> subshellSources = new ArrayList<>();
        for (Token t : tokens) {
            if (t.subshellSource() != null) {
                subshellSources.add(t.subshellSource());
            }
        }

        // ② 本体段
        Token first = tokens.get(0);
        String rawExecutable = first.value();
        CommandSegment self;

        if (first.fullyQuoted()) {
            // 整段第一个 token 被引号包裹：shell 会把它当作「一个含空格的程序名」，
            // 现实中几乎必然执行失败，但静态无法确定，按不可解析保守处理
            self = CommandSegment.unparseable(raw.text(), depth,
                    "可执行名被引号包裹，无法静态确定实际命令");
        } else if (isVariableReference(rawExecutable)) {
            self = CommandSegment.unparseable(raw.text(), depth,
                    "可执行名来自变量（" + rawExecutable + "），运行期才确定");
        } else if (depth >= MAX_DEPTH) {
            self = CommandSegment.unparseable(raw.text(), depth,
                    "嵌套层级超过上限 " + MAX_DEPTH + "，放弃进一步解析");
        } else {
            String executable = normalizeExecutable(rawExecutable);
            List<String> args = new ArrayList<>();
            List<String> flags = new ArrayList<>();
            List<String> quoted = new ArrayList<>();
            for (int i = 1; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                args.add(t.value());
                quoted.addAll(t.quotedParts());
                if (isFlag(t.value())) {
                    flags.addAll(normalizeFlags(t.value()));
                }
            }
            // 首个 token 的引号内容也要参与路径/敏感检测（如 Get-Content ".env"）
            quoted.addAll(first.quotedParts());
            String subcommand = extractSubcommand(args);
            self = new CommandSegment(raw.text(), executable, rawExecutable, subcommand,
                    List.copyOf(flags), List.copyOf(args), List.copyOf(quoted),
                    depth, false, null);
        }
        out.add(self);

        // ③ 嵌套 shell：把交给 shell 的字符串递归分段一层（R1）
        if (!self.unparseable()) {
            String nested = extractNestedShellPayload(self);
            if (nested != null && !nested.isBlank() && depth < MAX_DEPTH) {
                for (RawSegment inner : tokenize(nested)) {
                    expandRawSegment(inner, depth + 1, out);
                }
            }
        }

        // ④ 子命令替换（$(...) / `...`）递归分段
        for (String source : subshellSources) {
            if (source == null || source.isBlank() || depth >= MAX_DEPTH) {
                continue;
            }
            for (RawSegment inner : tokenize(source)) {
                expandRawSegment(inner, depth + 1, out);
            }
        }
    }

    /**
     * 若本段是「壳 + 载荷」形式，返回交给内层 shell 的字符串，否则 null。
     *
     * <p>载荷取「触发标志之后的全部参数」：{@code cmd /c chcp 65001>nul & del x}
     * 的载荷是 {@code chcp 65001>nul & del x}（&amp; 会被内层再次分段）。</p>
     */
    private String extractNestedShellPayload(CommandSegment seg) {
        String exe = seg.executable();
        List<String> args = seg.args();
        if (exe.isEmpty() || args.isEmpty()) {
            return null;
        }
        if (CMD_SHELLS.contains(exe)) {
            return payloadAfterFlag(args, Set.of("/c", "/k", "-c"));
        }
        if (PS_SHELLS.contains(exe)) {
            // -EncodedCommand 需要 base64 解码（UTF-16LE），单独处理
            int encodedIdx = indexOfPrefixFlag(args, "encodedcommand");
            if (encodedIdx >= 0 && encodedIdx + 1 < args.size()) {
                return decodePowerShellEncoded(args.get(encodedIdx + 1));
            }
            return payloadAfterFlag(args, Set.of("-command", "-c"));
        }
        if (UNIX_SHELLS.contains(exe)) {
            return payloadAfterFlag(args, Set.of("-c", "-lc", "-ic"));
        }
        if (PRIVILEGE_WRAPPERS.contains(exe)) {
            // sudo/doas 后面直接跟真实命令（无 -c 之类的标志），整段作为内层载荷递归
            return String.join(" ", args);
        }
        return null;
    }

    /** 找到给定标志并返回其后的全部参数拼接结果（即交给内层 shell 的命令串）。 */
    private static String payloadAfterFlag(List<String> args, Set<String> triggers) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i).toLowerCase(Locale.ROOT);
            if (triggers.contains(a)) {
                return String.join(" ", args.subList(i + 1, args.size()));
            }
        }
        return null;
    }

    /** 按前缀匹配标志（{@code -EncodedCommand} 常写作 {@code -enc}、{@code -e} 等缩写）。 */
    private static int indexOfPrefixFlag(List<String> args, String flagName) {
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i).toLowerCase(Locale.ROOT);
            if (a.startsWith("-") && flagName.startsWith(a.substring(1)) && a.length() > 1) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 解码 PowerShell {@code -EncodedCommand} 的载荷。
     *
     * <p>格式是 UTF-16LE 的 Base64。解不开（截断、伪造）时返回 null，
     * 由调用方走「不可解析 → 至少 WRITE」的保守路径。</p>
     */
    private static String decodePowerShellEncoded(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String normalized = encoded.trim();
            // PowerShell 允许省略 Base64 尾部填充
            while (normalized.length() % 4 != 0) {
                normalized += "=";
            }
            byte[] bytes = Base64.getDecoder().decode(normalized);
            String decoded = new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE).trim();
            logger.debug("已解码 -EncodedCommand 载荷（{} 字符）", decoded.length());
            return decoded.isEmpty() ? null : decoded;
        } catch (RuntimeException e) {
            // IllegalArgumentException 是 RuntimeException 子类，无需重复列举
            logger.debug("EncodedCommand 解码失败，按不可解析处理: {}", e.toString());
            return null;
        }
    }

    // =========================================================================
    // 词法分析（单遍状态机）
    // =========================================================================

    /** 一个原始段：文本 + token 列表。 */
    private record RawSegment(String text, List<Token> tokens) {
    }

    /**
     * 一个 token。
     *
     * @param raw            原文（含引号）
     * @param value          去引号后的值（用于匹配规则）
     * @param quotedParts    本 token 内所有引号包裹的字面量（数据，不参与命令识别）
     * @param fullyQuoted    是否整体被引号包裹
     * @param subshellSource {@code $(...)} / 反引号内的子命令源文本；无则 null
     */
    private record Token(String raw, String value, List<String> quotedParts,
                         boolean fullyQuoted, String subshellSource) {
    }

    /**
     * 单遍扫描：处理引号、转义、{@code $(...)}、反引号与分隔符。
     *
     * <p>扫描期不区分 shell 方言（bash 与 PowerShell 的差异见反引号处理的注释）——
     * 差异只在「保守 vs 精确」之间取舍，本实现一律取保守方向。</p>
     */
    private List<RawSegment> tokenize(String cmd) {
        List<RawSegment> segments = new ArrayList<>();
        List<Token> tokens = new ArrayList<>();

        int n = cmd.length();
        int segmentStart = 0;
        int tokenStart = -1;

        StringBuilder plain = new StringBuilder();     // 非引号部分
        List<String> quotedParts = new ArrayList<>();  // 引号内字面量（参与路径检测）

        int i = 0;
        while (i < n) {
            char c = cmd.charAt(i);

            // ---- 引号字符串：内容进 quotedParts，绝不当作命令识别 ----
            if (c == '"' || c == '\'') {
                if (tokenStart < 0) {
                    tokenStart = i;
                }
                char quote = c;
                StringBuilder inner = new StringBuilder();
                i++;
                while (i < n) {
                    char d = cmd.charAt(i);
                    if (d == '\\' && quote == '"' && i + 1 < n) {
                        // bash 双引号内的反斜杠转义；单引号内无转义语义
                        inner.append(cmd.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (d == quote) {
                        i++;
                        break;
                    }
                    inner.append(d);
                    i++;
                }
                String content = inner.toString();
                quotedParts.add(content);
                plain.append(content);
                continue;
            }

            // ---- $(...) 与反引号：子命令替换，内部整体作为一个 token 收集 ----
            if (c == '$' && i + 1 < n && cmd.charAt(i + 1) == '(') {
                int close = matchParen(cmd, i + 1);
                if (close < 0) {
                    // 未闭合的 $(：无法确定语义。把 $ 当普通字符继续扫描，
                    // 最终可执行名会含 "$("，由 isVariableReference 判为不可解析（保守）
                    if (tokenStart < 0) {
                        tokenStart = i;
                    }
                    plain.append(c);
                    i++;
                    continue;
                }
                // 形如 x=$(...) 时前缀已累积在当前 token 里，先提交，避免丢掉 x=
                if (tokenStart >= 0) {
                    flushToken(tokens, cmd, tokenStart, i, plain, quotedParts);
                    plain.setLength(0);
                    quotedParts = new ArrayList<>();
                }
                String source = (close > i + 2) ? cmd.substring(i + 2, close) : "";
                tokens.add(new Token(cmd.substring(i, close + 1),
                        source, List.of(), false, source.isEmpty() ? null : source));
                tokenStart = -1;
                i = close + 1;
                continue;
            }
            if (c == '`') {
                // PowerShell 里 ` 是转义符，`+换行 是行续接；bash 里 `...` 是命令替换。
                // 方言无法静态区分，故：紧跟换行按行续接忽略；否则按命令替换保守处理
                // （最坏情况是把一个 PS 转义序列当成子命令解析，结果只会更严格，不会漏过）。
                if (i + 1 < n && (cmd.charAt(i + 1) == '\n' || cmd.charAt(i + 1) == '\r')) {
                    i += 2;
                    if (i < n && cmd.charAt(i - 1) == '\r' && cmd.charAt(i) == '\n') {
                        i++;
                    }
                    continue;
                }
                int close = cmd.indexOf('`', i + 1);
                if (close > i) {
                    if (tokenStart < 0) {
                        tokenStart = i;
                    }
                    String source = cmd.substring(i + 1, close);
                    tokens.add(new Token(cmd.substring(i, close + 1), source,
                            List.of(), false, source.isEmpty() ? null : source));
                    i = close + 1;
                    continue;
                }
                // 未闭合的反引号：无法确定语义，原样保留为普通字符
                if (tokenStart < 0) {
                    tokenStart = i;
                }
                plain.append(c);
                i++;
                continue;
            }

            // ---- 反斜杠转义：仅在转义「特殊字符」时生效 ----
            // Windows 路径分隔符 \ 必须保留（C:\Users），因此只对引号、$、`、空格、
            // 分隔符等做转义处理，其余情况把 \ 当普通字符
            if (c == '\\' && i + 1 < n && isEscapable(cmd.charAt(i + 1))) {
                if (tokenStart < 0) {
                    tokenStart = i;
                }
                plain.append(cmd.charAt(i + 1));
                i += 2;
                continue;
            }

            // ---- 分隔符：结束当前 token 与当前段 ----
            if (c == ';' || c == '\n' || c == '\r') {
                flushToken(tokens, cmd, tokenStart, i, plain, quotedParts);
                tokenStart = -1;
                plain.setLength(0);
                quotedParts = new ArrayList<>();
                flushSegment(segments, cmd, segmentStart, i, tokens);
                tokens = new ArrayList<>();
                segmentStart = i + 1;
                i++;
                continue;
            }
            if (c == '|' || c == '&') {
                boolean doubled = i + 1 < n && cmd.charAt(i + 1) == c;
                flushToken(tokens, cmd, tokenStart, i, plain, quotedParts);
                tokenStart = -1;
                plain.setLength(0);
                quotedParts = new ArrayList<>();
                int end = i + (doubled ? 2 : 1);
                flushSegment(segments, cmd, segmentStart, end, tokens);
                tokens = new ArrayList<>();
                segmentStart = end;
                i = end;
                continue;
            }

            // ---- 普通空白：结束当前 token，但不结束段 ----
            if (Character.isWhitespace(c)) {
                flushToken(tokens, cmd, tokenStart, i, plain, quotedParts);
                tokenStart = -1;
                plain.setLength(0);
                quotedParts = new ArrayList<>();
                i++;
                continue;
            }

            // ---- 普通字符 ----
            if (tokenStart < 0) {
                tokenStart = i;
            }
            plain.append(c);
            i++;
        }

        // 收尾：最后一个 token 与最后一段
        flushToken(tokens, cmd, tokenStart, n, plain, quotedParts);
        flushSegment(segments, cmd, segmentStart, n, tokens);
        return segments;
    }

    /** 转义目标字符集合：这些字符前的反斜杠表示转义而非路径分隔。 */
    private static boolean isEscapable(char c) {
        return c == '"' || c == '\'' || c == '`' || c == '$' || c == '\\'
                || c == ' ' || c == ';' || c == '&' || c == '|' || c == '(' || c == ')';
    }

    /** 从开括号位置起找到匹配的右括号，返回其下标；未闭合返回 -1。 */
    private static int matchParen(String cmd, int openIndex) {
        int depth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = openIndex; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 提交当前 token（若有内容）。 */
    private static void flushToken(List<Token> tokens, String cmd, int tokenStart, int end,
                                   StringBuilder plain, List<String> quotedParts) {
        if (tokenStart < 0 || (plain.length() == 0 && quotedParts.isEmpty())) {
            return;
        }
        int to = Math.min(end, cmd.length());
        String raw = (tokenStart < to) ? cmd.substring(tokenStart, to) : plain.toString();
        String value = plain.toString();
        // 完全被引号包裹：引号内容即 token 全部值，且原文以引号开头。
        // 单独引出一个布尔量而不是连写 &&/||，避免优先级误读（&& 高于 ||）
        boolean startsWithQuote = raw.startsWith("\"") || raw.startsWith("'");
        boolean fullyQuoted = quotedParts.size() == 1 && value.equals(quotedParts.get(0))
                && startsWithQuote;
        tokens.add(new Token(raw, value, List.copyOf(quotedParts), fullyQuoted, null));
    }

    /** 提交当前段（若含 token）。 */
    private static void flushSegment(List<RawSegment> segments, String cmd,
                                     int start, int end, List<Token> tokens) {
        if (tokens.isEmpty()) {
            return;
        }
        int from = Math.max(0, Math.min(start, cmd.length()));
        int to = Math.max(from, Math.min(end, cmd.length()));
        // 去掉段首段尾的空白，使 rawText 干净可展示
        String text = cmd.substring(from, to).trim();
        segments.add(new RawSegment(text.isEmpty() ? String.join(" ",
                tokens.stream().map(Token::raw).toList()) : text, List.copyOf(tokens)));
    }

    // =========================================================================
    // 归一化辅助
    // =========================================================================

    /**
     * 归一化可执行名：去路径前缀、去可执行扩展名、小写化。
     *
     * <p>同时处理 Windows 的 {@code .exe} 与常见壳脚本后缀，使规则表可以只写
     * {@code exe: [del]} 而不必枚举 {@code del.exe}。</p>
     *
     * <p>对 {@code .} / {@code ..} 这类「命令」保持原样（它们在 bash 里是 source 语义，
     * 已在 {@link #EVAL_LIKE} 中登记）。</p>
     */
    static String normalizeExecutable(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String name = raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : EXECUTABLE_SUFFIXES) {
            if (lower.endsWith(suffix) && lower.length() > suffix.length()) {
                return lower.substring(0, lower.length() - suffix.length());
            }
        }
        return lower;
    }

    /** 是否为标志（{@code -x} / {@code --xxx} / Windows 的 {@code /x}）。 */
    static boolean isFlag(String token) {
        if (token == null || token.length() < 2) {
            return false;
        }
        if (token.charAt(0) == '-') {
            return true;
        }
        // Windows 风格 /s /q /f：仅认「/ + 1~2 个字母」，
        // 否则 /etc/passwd、/dev/null 这类 Unix 绝对路径会被误当标志
        return token.charAt(0) == '/' && token.length() <= 3
                && token.chars().skip(1).allMatch(Character::isLetter);
    }

    /**
     * 归一化标志：小写化，并把 Unix 合并短选项展开。
     *
     * <p>{@code -rf} 同时产出 {@code -rf}、{@code -r}、{@code -f}，使规则无论写
     * {@code flags_any: [-rf]} 还是 {@code [-r]} 都能命中；长选项
     * {@code -Recurse} → {@code -recurse}。</p>
     */
    static List<String> normalizeFlags(String token) {
        List<String> out = new ArrayList<>();
        String lower = token.toLowerCase(Locale.ROOT);
        out.add(lower);
        if (lower.length() > 2 && !lower.startsWith("--") && lower.charAt(0) == '-') {
            // 仅当全部字符都像短标志时才展开，避免把 -1 这类数值参数误拆
            boolean allShortFlags = lower.substring(1).chars()
                    .allMatch(c -> Character.isLetter(c) || c == '?');
            if (allShortFlags) {
                for (int i = 1; i < lower.length(); i++) {
                    out.add("-" + lower.charAt(i));
                }
            }
        }
        return out;
    }

    /**
     * 提取子命令：第一个「像命令词」的位置参数。
     *
     * <p>判据为纯字母/数字/连字符且不含路径分隔符与冒号——这样
     * {@code git push} 的 {@code push} 会被取到，而 {@code del /s /q C:\} 的
     * {@code C:\}（含反斜杠与冒号）不会被误当作子命令。</p>
     */
    private static String extractSubcommand(List<String> args) {
        for (String a : args) {
            if (a == null || a.isEmpty()) {
                continue;
            }
            if (isFlag(a)) {
                continue;
            }
            if (a.matches("[A-Za-z][A-Za-z0-9._-]*")) {
                return a.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * 是否为变量引用（运行期才展开，静态不可靠）。
     *
     * <p>覆盖 bash 的 {@code $VAR} / {@code ${VAR}}、cmd 的 {@code %VAR%}、
     * PowerShell 的 {@code $env:VAR}。命令名来自变量时无法分类，
     * 必须按不可解析处理（R2）。</p>
     */
    static boolean isVariableReference(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        String t = token.trim();
        if (t.startsWith("$")) {
            return true;
        }
        if (t.startsWith("%") && t.endsWith("%") && t.length() > 2) {
            return true;
        }
        // 命令名里嵌了变量（如 r"$X"m）同样不可靠
        return t.contains("$(") || t.contains("${") || t.contains("%");
    }

    /** 是否为求值类命令（eval / iex / Invoke-Expression / source / .），供策略层判级使用。 */
    public static boolean isEvalLike(String executable) {
        return executable != null && EVAL_LIKE.contains(executable.toLowerCase(Locale.ROOT));
    }
}
