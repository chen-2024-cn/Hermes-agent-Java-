package com.cyk.util;

import java.util.function.Consumer;

/**
 * <h1>终端 UI 渲染器（ConsoleUi）</h1>
 *
 * <p>集中管理 Jhermes 命令行交互的<b>视觉呈现</b>，把「怎么打印得好看、好定位」
 * 从 Agent 的对话逻辑中剥离出来（单一职责）。解决用户的两个核心体验痛点：</p>
 *
 * <ol>
 *   <li><b>回答换行杂乱、Markdown 符号满屏</b> — 模型（尤其轻量模型）常输出
 *       大量连续空行和 {@code ## ** } 等无法在终端渲染的 Markdown 标记。
 *       这里提供两级兜底：{@link StreamingRenderer}（流式，跨 token 安全地压缩空行/行尾空白）
 *       与 {@link #stripMarkdown(String)}（非流式，拿到完整文本后彻底清理）。</li>
 *   <li><b>滚动输出里找不到自己提的问题</b> — 用醒目的分隔线 + 彩色图标前缀
 *       （{@link #userPromptLine()} / {@link #assistantPrefix()}）把每一轮对话的起点
 *       高亮出来，User 与 Assistant 用不同颜色和符号区分，一眼可辨。</li>
 * </ol>
 *
 * <h2>跨平台稳健性设计（重要）</h2>
 * <p>颜色（ANSI）是<b>可选增强</b>，符号与分隔线是<b>始终显示的保底</b>。
 * 原因：老版 Windows conhost 默认不启用 VirtualTerminalProcessing，直接输出
 * {@code \u001b[32m} 会显示成乱码转义符（这是 CLI 工具的经典坑）。
 * 因此 {@link #isColorEnabled()} 用环境变量启发式探测现代终端（Windows Terminal /
 * VS Code / ConEmu / 非 Windows），探测失败则自动降级为「纯符号无颜色」——
 * 醒目度依然足够（▶ ◆ ─ 大图标 + 大写标签），且绝无乱码风险。
 * 同时尊重业界 {@code NO_COLOR} 约定与被重定向（非 TTY）场景。</p>
 */
public final class ConsoleUi {

    // =========================================================================
    // ANSI 颜色（仅在探测到支持时启用）
    // =========================================================================

    private static final String ESC = "\u001b[";
    private static final String RESET = ESC + "0m";

    private static final boolean COLOR = detectColorSupport();

    /** 分隔线字符（U+2500），Unicode 在所有 UTF-8 终端均可显示，不依赖 ANSI */
    private static final String DIVIDER_CHAR = "─";

    /** 分隔线长度 */
    private static final int DIVIDER_WIDTH = 46;

    private ConsoleUi() {
    }

    /**
     * 探测当前终端是否支持 ANSI 颜色。
     *
     * <p>判定顺序：NO_COLOR 约定 &gt; 非 TTY（重定向到文件/管道）&gt; 平台启发式。
     * 任何一个「不支持」信号都直接返回 false，确保只在有把握时才输出颜色。</p>
     */
    static boolean detectColorSupport() {
        // 业界通用约定：设置了 NO_COLOR（任意值）就禁用颜色
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        // 被重定向到文件或管道时不是交互式终端，输出颜色码只会污染内容
        if (System.console() == null) {
            String term = System.getenv("TERM");
            // 某些环境（如 VS Code 集成终端经 node shim）console() 为 null 但仍是彩色终端，
            // 用 TERM/WT_SESSION 等显式信号兜底放行
            if (term == null && System.getenv("WT_SESSION") == null
                    && System.getenv("VSCODE_INJECTION") == null) {
                return false;
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            // 老 conhost 不支持 ANSI；仅当检测到现代终端时才启用颜色
            return System.getenv("WT_SESSION") != null          // Windows Terminal
                    || System.getenv("ConEmuANSI") != null       // ConEmu/Cmder
                    || System.getenv("VSCODE_INJECTION") != null // VS Code 集成终端
                    || System.getenv("GIT_ASKPASS") != null      // Git Bash
                    || System.getenv("TERMINAL_EMULATOR") != null;
        }
        // 非 Windows：绝大多数终端支持，除非显式声明 dumb
        String term = System.getenv("TERM");
        return !"dumb".equals(term);
    }

    /** 颜色是否启用（供测试与内部渲染判断） */
    public static boolean isColorEnabled() {
        return COLOR;
    }

    // =========================================================================
    // 角色前缀与分隔线
    // =========================================================================

    /**
     * 生成「用户提问」提示行。
     *
     * <p>这是用户在滚动输出里定位自己每一次提问的锚点：一条分隔线 + 醒目的
     * 绿色「▶ 你:」标签。分隔线让整个对话轮次在视觉上断开，回滚时极易扫到。</p>
     *
     * @param showDivider 是否在提示符前打印分隔线（首轮可不打印避免与启动横幅重叠）
     * @return 提示符字符串（调用方直接 print 后读取用户输入，光标停在末尾）
     */
    public static String userPrompt(boolean showDivider) {
        StringBuilder sb = new StringBuilder();
        if (showDivider) {
            sb.append('\n').append(color(divider(), "90")).append('\n');
        }
        sb.append(color("▶ ", "1;32")).append(color("你:", "1;32")).append(' ');
        return sb.toString();
    }

    /** 兼容旧调用：始终带分隔线的用户提示符 */
    public static String userPromptLine() {
        return userPrompt(true);
    }

    /**
     * 生成「助手回答」前缀。
     *
     * <p>青色「◆ Hermes:」标签，与绿色的用户提示符形成鲜明的颜色 + 符号双重区分。</p>
     */
    public static String assistantPrefix() {
        return "\n" + color("◆ ", "1;36") + color("Hermes:", "1;36") + "\n";
    }

    /** 工具执行等次要信息的淡化前缀（暗灰），弱化视觉权重 */
    public static String dimPrefix() {
        return color("  ⚙ ", "90");
    }

    private static String divider() {
        return DIVIDER_CHAR.repeat(DIVIDER_WIDTH);
    }

    /** 若颜色启用则包裹 ANSI 序列，否则原样返回（优雅降级） */
    private static String color(String text, String ansi) {
        return COLOR ? ESC + ansi + "m" + text + RESET : text;
    }

    // =========================================================================
    // token 用量汇总行（每轮回复末尾附加）
    // =========================================================================

    /**
     * 渲染「本轮 + 会话累计」token 用量汇总（固定两行，纯数据展示，与助手正文视觉分离）。
     *
     * <p>输出形如（暗灰色，弱化视觉权重但不缺信息量）：</p>
     * <pre>
     *   ⚙ 本轮 输入 1,234 / 输出 56 / 总计 1,290 tokens（含 3 次模型请求）
     *   ⚙ 会话累计 输入 5,678 / 输出 890 / 总计 6,568 tokens
     * </pre>
     *
     * <p>设计要点：</p>
     * <ul>
     *   <li><b>数值来自 API 的 usage 字段</b>（{@link TokenUsageTracker} 只做累加），
     *       与服务商账单同口径，不是本地估算；</li>
     *   <li><b>「本轮」= 一次提问的全部模型请求之和</b>：Agent 的工具调用循环会让
     *       单次提问产生多次 HTTP 请求，只统计最后一次会严重低报。请求次数 &gt; 1 时
     *       额外标注次数，让用户一眼看出「这轮走了几趟工具」；</li>
     *   <li><b>无用量数据时返回空串</b>（本轮请求次数为 0）：部分兼容层不回传 usage，
     *       此时打印一排 0 是误导，宁可不显示——这是降级而非丢失功能。</li>
     * </ul>
     *
     * @param turn    当轮用量快照
     * @param session 会话累计用量快照
     * @return 两行汇总文本（无数据时为空串，调用方判空后跳过打印）
     */
    public static String usageLine(TokenUsageTracker.Snapshot turn, TokenUsageTracker.Snapshot session) {
        if (turn == null || turn.requests() <= 0) {
            return "";
        }
        // 整段统一包裹暗灰（90）：若只给标签着色，数字会保持终端默认色，
        // 一行内两种视觉权重反而显得杂乱
        StringBuilder sb = new StringBuilder();
        sb.append(dimPrefix()).append(color(
                "本轮 " + formatUsage(turn) + " tokens"
                        + (turn.requests() > 1 ? "（含 " + turn.requests() + " 次模型请求）" : ""),
                "90"));
        sb.append('\n');
        sb.append(dimPrefix()).append(color(
                "会话累计 " + formatUsage(session == null ? EMPTY_USAGE : session) + " tokens",
                "90"));
        return sb.toString();
    }

    /** 会话累计快照缺失（理论上不会发生）时的占位，保证行格式恒定。 */
    private static final TokenUsageTracker.Snapshot EMPTY_USAGE =
            new TokenUsageTracker.Snapshot(0, 0, 0, 0);

    /** 把快照格式化为「输入 x / 输出 y / 总计 z」（千分位分隔，便于扫读数位）。 */
    static String formatUsage(TokenUsageTracker.Snapshot s) {
        return "输入 " + thousands(s.promptTokens())
                + " / 输出 " + thousands(s.completionTokens())
                + " / 总计 " + thousands(s.totalTokens());
    }

    private static String thousands(long n) {
        return String.format("%,d", n);
    }

    // =========================================================================
    // Markdown 清理（非流式：拿到完整文本后彻底清理）
    // =========================================================================

    /**
     * 清理模型输出中不适合终端渲染的 Markdown 痕迹，并规范化空白（非流式入口）。
     *
     * <p>处理策略与流式渲染器完全一致：都基于「整行清理」——
     * {@link #isFence} 识别围栏代码块边界（块内代码原样保留，不误伤 {@code #include}、
     * {@code a*b} 等），{@link #stripLineMarkdown} 清理单行正文里的 Markdown 噪声。</p>
     *
     * <p>清理项（纯改善、不改语义）：</p>
     * <ul>
     *   <li>删除围栏代码块标记行 {@code ```} / {@code ~~~}（保留块内代码）</li>
     *   <li>去除行首 ATX 标题标记 {@code # ## ###}（后面跟空格或中文）</li>
     *   <li>去除成对/落单的 {@code **} 加粗、{@code *} 斜体、反引号行内代码</li>
     *   <li>行首 {@code * } / {@code + } 列表符统一为 {@code - }，去除 {@code >} 引用符</li>
     *   <li>连续空行压缩为最多一个、去除行尾空白与整体首尾空白</li>
     * </ul>
     *
     * @param text 模型完整输出
     * @return 适合终端显示的纯文本
     */
    public static String stripMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // CRLF 归一化，简化后续按行处理
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = s.split("\n", -1);

        StringBuilder sb = new StringBuilder();
        boolean inCode = false;     // 是否处于围栏代码块内
        boolean started = false;    // 是否已输出过正文（抑制开头空行）
        boolean pendingBlank = false; // 是否有待释放的空行（实现「最多一个空行」）

        for (String raw : lines) {
            String trimmed = stripTrailing(raw);
            if (isFence(trimmed)) {
                // 围栏标记行本身丢弃，只切换代码块状态
                inCode = !inCode;
                continue;
            }
            String cleaned = inCode ? trimmed : stripLineMarkdown(trimmed);
            if (cleaned.isEmpty()) {
                if (started) {
                    pendingBlank = true;
                }
                continue;
            }
            if (started) {
                sb.append('\n');
                if (pendingBlank) {
                    sb.append('\n');
                }
            }
            sb.append(cleaned);
            started = true;
            pendingBlank = false;
        }
        return sb.toString();
    }

    /**
     * 判断某行是否为围栏代码块标记（{@code ```} 或 {@code ~~~}，允许最多 3 空格缩进）。
     */
    static boolean isFence(String trimmedLine) {
        String t = trimmedLine.replaceFirst("^\\s{0,3}", "");
        return t.startsWith("```") || t.startsWith("~~~");
    }

    /**
     * 清理单行正文中的 Markdown 噪声（仅用于非代码块内的行）。
     *
     * <p>保守原则：标题标记只在「#后跟空格」或「#后直接跟中文」时剥离，
     * 以保护 {@code #include}、{@code #1}、{@code C#} 等正常文本；不处理下划线
     * （保护 {@code __init__}、{@code snake_case}）。</p>
     */
    static String stripLineMarkdown(String line) {
        String s = stripTrailing(line);
        // ATX 标题：行首 1-6 个 '#' 后跟空格 → 剥离标记
        s = s.replaceFirst("^\\s{0,3}#{1,6}\\s+", "");
        // 中文场景标题：## 后直接跟中文（模型常见写法），剥离 '#'
        s = s.replaceFirst("^\\s{0,3}#{1,6}(?=[\\u4e00-\\u9fff])", "");
        // 引用符 '>' 剥离（保留缩进层级）
        s = s.replaceFirst("^(\\s*)>\\s?", "$1");
        // 行首列表符 * / + 统一为 -（在剥离加粗前处理，避免与 ** 混淆）
        s = s.replaceFirst("^(\\s*)[*+]\\s+", "$1- ");
        // 成对加粗 **文字** → 文字
        s = s.replaceAll("\\*\\*([^*]+?)\\*\\*", "$1");
        // 成对斜体 *文字* → 文字（排除 ** 与单词内星号）
        s = s.replaceAll("(?<![*\\w])\\*([^*\\n]+?)\\*(?![*\\w])", "$1");
        // 行内代码 `文字` → 文字（支持多反引号）
        s = s.replaceAll("`+([^`]*)`+", "$1");
        // 落单的 ** 残留（未配对）直接删除
        s = s.replace("**", "");
        return stripTrailing(s);
    }

    /** 压缩连续空行为最多一个、去除每行行尾空白、去除整体首尾空白 */
    public static String normalizeBlankLines(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean prevBlank = false;
        for (String line : lines) {
            String trimmedEnd = stripTrailing(line);
            boolean blank = trimmedEnd.isEmpty();
            // 连续空行只保留一个；开头空行全部丢弃
            if (blank) {
                if (prevBlank || sb.length() == 0) {
                    continue;
                }
                sb.append('\n');
                prevBlank = true;
            } else {
                sb.append(trimmedEnd).append('\n');
                prevBlank = false;
            }
        }
        // 去掉末尾多余换行
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '\n') {
            end--;
        }
        sb.setLength(end);
        return sb.toString();
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(0, end);
    }

    // =========================================================================
    // 流式渲染器（行缓冲：跨 token 安全的 Markdown 清理 + 空白规范化）
    // =========================================================================

    /**
     * 创建一个流式渲染器，供 {@code chatCompletionStream} 的增量回调使用。
     *
     * @param sink 真正写往终端的消费者（通常是 {@code System.out::print}）
     * @return 渲染器实例
     */
    public static StreamingRenderer newStreamingRenderer(Consumer<String> sink) {
        return new StreamingRenderer(sink);
    }

    /**
     * 流式输出渲染器（行缓冲方案）：逐 token 接收模型增量，按「整行」为单位
     * 完成 Markdown 清理（{@link #stripLineMarkdown}）与空白规范化后写出。
     *
     * <h3>为什么是行缓冲而不是逐字符状态机？</h3>
     * <p>SSE 会把 {@code **加粗**}、{@code ### 标题}、围栏标记 ``` 切成任意片段
     * 送达；逐 token 处理必须为每种标记维护挂起状态，token 边界组合爆炸、极易出 bug。
     * 行缓冲把「一行」作为原子处理单位：行内的所有标记必然完整落在缓冲里，
     * 直接复用与非流式完全相同的正则清理逻辑（{@link #stripLineMarkdown}），
     * 代码更简单、行为一致、可单测。代价是输出粒度为「行」而非「字的即时喷涌」，
     * 对聊天式 CLI 的观感影响可忽略（一行通常在几十毫秒内到齐）。</p>
     *
     * <h3>处理的三类问题（对应「回答很多换行且不美观」）</h3>
     * <ul>
     *   <li>围栏代码块标记行整行丢弃；块内代码<b>原样保留</b>（不误伤 #include、a*b）</li>
     *   <li>行内 Markdown 标记剥离（标题 #、加粗、斜体、反引号、引用符、列表符归一）</li>
     *   <li>连续空行压缩为最多一个；开头空行抑制；行尾空白清除</li>
     * </ul>
     */
    public static final class StreamingRenderer {

        private final Consumer<String> sink;
        /** 当前行的增量缓冲（未遇到 \n 前持续累积，保证行内标记完整后才清理） */
        private final StringBuilder lineBuffer = new StringBuilder();
        /** 是否处于围栏代码块内（跨行状态） */
        private boolean inCode = false;
        /** 是否已输出过任何正文行（用于抑制开头空行） */
        private boolean started = false;
        /** 是否有待释放的空行（配合 started 实现「连续空行最多一个」） */
        private boolean pendingBlank = false;

        private StreamingRenderer(Consumer<String> sink) {
            this.sink = sink;
        }

        /**
         * 接收一个流式增量片段：切分出完整行逐行清理输出，残行留在缓冲等后续片段。
         *
         * @param piece 模型吐出的文本片段（SSE 切分，可能是单字符、词或整句）
         */
        public void accept(String piece) {
            if (piece == null || piece.isEmpty()) {
                return;
            }
            for (int i = 0; i < piece.length(); i++) {
                char c = piece.charAt(i);
                if (c == '\r') {
                    // 忽略 CR：\r\n 只按 LF 算一次换行
                    continue;
                }
                if (c == '\n') {
                    // 一整行到齐：清理并输出
                    emitLine(lineBuffer.toString());
                    lineBuffer.setLength(0);
                } else {
                    lineBuffer.append(c);
                }
            }
        }

        /**
         * 流结束时收尾：把缓冲中最后一行（无换行结尾）也清理输出。
         *
         * @return 是否真正输出过可见内容（调用方据此决定要不要补换行）
         */
        public boolean finish() {
            if (lineBuffer.length() > 0) {
                emitLine(lineBuffer.toString());
                lineBuffer.setLength(0);
            }
            return started;
        }

        /**
         * 清理并输出一整行。
         *
         * <p>决策树：围栏标记 → 切换代码块状态并丢弃该行；
         * 代码块内 → 原样输出（保留代码语义）；普通行 → Markdown 清理后输出。
         * 空行走 pendingBlank 机制（连续多个只放一个，开头的不放）。</p>
         */
        private void emitLine(String raw) {
            String trimmed = stripTrailing(raw);
            if (isFence(trimmed)) {
                inCode = !inCode;
                return;
            }
            String cleaned = inCode ? trimmed : stripLineMarkdown(trimmed);
            if (cleaned.isEmpty()) {
                if (started) {
                    pendingBlank = true;
                }
                return;
            }
            StringBuilder out = new StringBuilder();
            if (started) {
                out.append('\n');
                if (pendingBlank) {
                    out.append('\n');
                }
            }
            out.append(cleaned);
            started = true;
            pendingBlank = false;
            sink.accept(out.toString());
        }
    }
}
