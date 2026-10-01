package com.cyk.util;

/**
 * <h1>启动横幅（Startup Banner）</h1>
 *
 * <p>程序启动时打印<b>一次</b>的品牌横幅：ASCII Art 标题（图字）+ 渐变色渲染
 * + 版本标语。渲染完全由本类负责，业务侧只需调用 {@link #print(String)}。</p>
 *
 * <h2>三条设计红线（对应「无乱码、无错位」的验收要求）</h2>
 * <ol>
 *   <li><b>颜色是可选增强，字是保底</b>：复用 {@link ConsoleUi#isColorEnabled()}
 *       的保守探测结果（NO_COLOR / 非 TTY / 老 conhost 一律判否）。
 *       探测失败时输出<b>纯文本 ASCII Art</b>——不含任何 ANSI 转义，绝无乱码。</li>
 *   <li><b>对齐按「显示宽度」而非 {@code String.length()}</b>：框内既有 ASCII 图字
 *       又有中文标语，而中文与全角字符在等宽终端里占 <b>2 列</b>。
 *       若用 {@code length()} 补齐，含中文的行必然向右溢出、竖线错位。
 *       {@link #displayWidth(String)} 按 Unicode 区间判定宽度，
 *       并对已着色的字符串剔除 ANSI 转义后再计算。</li>
 *   <li><b>宽度硬上限 60 列</b>：图字本体 50 列 + 左右内边距 2×2 + 边框 2 = 56 列。
 *       窄终端（Windows 默认 80 列）也不会折行；折行是 ASCII Art 错位的头号元凶。</li>
 * </ol>
 *
 * <h2>渐变实现</h2>
 * <p>逐字符使用 24-bit 真彩色 {@code ESC[38;2;r;g;b m}，沿图字的<b>列方向</b>
 * 在青 → 蓝 → 紫 → 粉四个锚点色之间线性插值（RGB 分量分别 lerp）。
 * 空白字符不着色但照常输出（丢空格会让图字塌成一团），标语行用淡灰弱化。
 * 之所以用真彩色而非 256 色：{@link ConsoleUi} 的颜色白名单
 * （Windows Terminal / VS Code / ConEmu / 非 Windows 非 dumb）均已支持真彩色，
 * 256 色的插值精度不足以呈现平滑过渡。</p>
 *
 * <h2>可测性</h2>
 * <p>{@link ConsoleUi#isColorEnabled()} 的结果是<b>类加载时就固化的静态常量</b>，
 * 而 surefire 的 JVM 没有 TTY，探测结果必然为「无色」。为了让两条路径都能被
 * 单元测试覆盖，真正的渲染逻辑放在 {@link #render(String, boolean)}（着色与否是显式入参），
 * 公开的 {@link #render(String)} 只是把探测结果传进去。</p>
 */
public final class StartupBanner {

    private static final String ESC = "\u001b[";
    private static final String RESET = ESC + "0m";

    /** 边框字符（U+256D/2500/256E/2502/2570/256F），与 ConsoleUi 分隔线同一字符族 */
    private static final char BOX_TL = '\u256d'; // ╭
    private static final char BOX_TR = '\u256e'; // ╮
    private static final char BOX_BL = '\u2570'; // ╰
    private static final char BOX_BR = '\u256f'; // ╯
    private static final char BOX_H = '\u2500';  // ─
    private static final char BOX_V = '\u2502';  // │

    /** 框内左右内边距（空格数） */
    private static final int PADDING = 2;

    /** ASCII Art 宽度（列），由 {@link #ART} 各行长度决定 */
    private static final int ART_WIDTH = 50;

    /** 横幅最大总宽度（硬约束：不超过 60 列） */
    private static final int MAX_WIDTH = 60;

    /**
     * ASCII Art 标题「JHERMES」。
     *
     * <p>使用 figlet 的 <b>standard</b> 字体逐字符拼合生成（非手抄，杜绝错位风险）。
     * 每行等宽 50 列，5 行为字符主体，末行留白由渲染逻辑补。</p>
     */
    static final String[] ART = {
            "     _  _   _  _____  ____   __  __  _____  ____  ",
            "    | || | | || ____||  _ \\ |  \\/  || ____|/ ___| ",
            " _  | || |_| ||  _|  | |_) || |\\/| ||  _|  \\___ \\ ",
            "| |_| ||  _  || |___ |  _ < | |  | || |___  ___) |",
            "\\____/ |_| |_||_____||_| \\_\\|_|  |_||_____||____/ ",
    };

    /**
     * 渐变锚点色（RGB），沿列方向依次插值：青 → 蓝 → 紫 → 粉。
     */
    private static final int[][] PALETTE = {
            {34, 211, 238},   // #22d3ee 青
            {59, 130, 246},   // #3b82f6 蓝
            {168, 85, 247},   // #a855f7 紫
            {236, 72, 153},   // #ec4899 粉
    };

    /**
     * 打印启动横幅（应在进程启动流程中调用<b>恰好一次</b>）。
     *
     * <p>本方法不做「是否已打印」的状态守卫：调用点在
     * {@link com.cyk.command.ChatRunner#start} —— chat 与 resume 两个命令的唯一汇聚路径，
     * 二者在一次进程生命周期内互斥，天然满足「只打印一次」。
     * 刻意不用静态布尔量做守卫：那会引入难以在单测中复位的隐藏状态。</p>
     *
     * @param version 版本号字符串（如 "2.1.0"），拼入标语行；null/空白则不显示版本段
     */
    public static void print(String version) {
        System.out.println(render(version));
        System.out.flush();
    }

    /**
     * 渲染完整横幅为字符串（不打印）；是否着色由 {@link ConsoleUi#isColorEnabled()} 决定。
     *
     * @param version 版本号，可为 null/空白
     * @return 多行横幅文本
     */
    public static String render(String version) {
        return render(version, ConsoleUi.isColorEnabled());
    }

    /**
     * 渲染完整横幅（着色与否显式指定，便于单元测试覆盖两条路径）。
     *
     * @param version 版本号，可为 null/空白
     * @param colorEnabled true = 逐字符渐变 + 真彩色 ANSI；false = 纯文本降级
     * @return 多行横幅文本（不含结尾换行）
     */
    public static String render(String version, boolean colorEnabled) {
        String tagline = tagline(version);
        // 内容区宽度 = max(图字宽, 标语宽)。图字是 ASCII（1 列/字符），
        // 标语若含中文按 2 列计（displayWidth），否则竖线会被推歪
        int contentWidth = Math.max(ART_WIDTH, displayWidth(tagline));
        // 两侧内边距之外再加左右边框各 1 列
        int innerWidth = contentWidth + PADDING * 2;
        // 安全阀：任何情况下都不让横幅超过 60 列
        innerWidth = Math.min(innerWidth, MAX_WIDTH - 2);
        contentWidth = innerWidth - PADDING * 2;

        StringBuilder sb = new StringBuilder();
        sb.append(BOX_TL).append(repeat(BOX_H, innerWidth)).append(BOX_TR).append('\n');
        for (String artLine : ART) {
            // pad 接收「已着色文本」：displayWidth 会先剔除 ANSI 再算列数，
            // 因此有色/无色两种模式下的补齐空格数完全一致（右边框必然对齐）
            sb.append(BOX_V)
                    .append(repeat(' ', PADDING))
                    .append(pad(colorLine(artLine, colorEnabled), contentWidth))
                    .append(repeat(' ', PADDING))
                    .append(BOX_V).append('\n');
        }
        sb.append(BOX_V).append(repeat(' ', innerWidth)).append(BOX_V).append('\n');
        sb.append(BOX_V)
                .append(repeat(' ', PADDING))
                .append(pad(colorTagline(tagline, colorEnabled), contentWidth))
                .append(repeat(' ', PADDING))
                .append(BOX_V).append('\n');
        sb.append(BOX_BL).append(repeat(BOX_H, innerWidth)).append(BOX_BR);
        return sb.toString();
    }

    /** 标语行着色：淡灰（90）弱化视觉权重，让 ASCII Art 保持主角地位。 */
    static String colorTagline(String tagline, boolean colorEnabled) {
        if (!colorEnabled) {
            return tagline;
        }
        return ESC + "90m" + tagline + RESET;
    }

    /**
     * 标语行内容：品牌定位 + 版本。
     *
     * <p>刻意保持一行简短，避免在窄终端折行。中英文混排时
     * {@link #displayWidth} 已按 2 列计算中文，右侧补齐不会错位。</p>
     */
    static String tagline(String version) {
        String base = "Self-Evolving AI Agent";
        if (version != null && !version.isBlank()) {
            base = base + " · v" + version.trim();
        }
        return base;
    }

    /**
     * 为一行 ASCII Art 施加逐字符渐变。
     *
     * <p>颜色禁用时<b>原样返回</b>入参（不是返回空串）——这样调用方无论哪种模式
     * 都能拿到「可展示的完整内容」交给 {@link #pad} 对齐，两条路径的补齐宽度一致。</p>
     *
     * @param artLine 图字的一行（纯文本，不含颜色）
     * @return 着色后的字符串；无色模式下即入参本身
     */
    static String colorLine(String artLine, boolean colorEnabled) {
        if (!colorEnabled) {
            return artLine;
        }
        StringBuilder sb = new StringBuilder(artLine.length() * 16);
        for (int col = 0; col < artLine.length(); col++) {
            char c = artLine.charAt(col);
            if (c == ' ') {
                // 空白不着色（省 ANSI 体积，也不给终端背景刷色块），
                // 但必须照常写入输出——丢掉空格会让整幅图字塌成一团
                sb.append(c);
                continue;
            }
            int[] rgb = gradientColor(col, artLine.length());
            sb.append(ESC).append("38;2;").append(rgb[0]).append(';')
                    .append(rgb[1]).append(';').append(rgb[2]).append('m')
                    .append(c).append(RESET);
        }
        return sb.toString();
    }

    /**
     * 计算某一列在渐变色带上的 RGB 值（分段线性插值）。
     *
     * @param col      当前列索引
     * @param cols     总列数（色带按 0..cols-1 归一化）
     * @return {r, g, b}，各分量 0..255
     */
    static int[] gradientColor(int col, int cols) {
        int segments = PALETTE.length - 1;
        double t = cols <= 1 ? 0.0 : (double) col / (cols - 1);
        double pos = t * segments;
        int idx = (int) Math.floor(pos);
        if (idx >= segments) {
            return PALETTE[segments];
        }
        double frac = pos - idx;
        int[] from = PALETTE[idx];
        int[] to = PALETTE[idx + 1];
        return new int[]{
                lerp(from[0], to[0], frac),
                lerp(from[1], to[1], frac),
                lerp(from[2], to[2], frac),
        };
    }

    private static int lerp(int a, int b, double frac) {
        int v = (int) Math.round(a + (b - a) * frac);
        return Math.max(0, Math.min(255, v));
    }

    /**
     * 按<b>显示宽度</b>把文本右侧补齐到目标宽度。
     *
     * <p>为什么不能用 {@code String.format("%-" + n + "s")}：它按字符数补空格，
     * 中文行会因每个汉字占 2 列而整体溢出目标宽度，导致右边框参差不齐
     * （这是 CLI 边框错位的经典成因）。</p>
     *
     * @param text   原文（可含 ANSI 颜色，宽度计算会先剔除转义）
     * @param target 目标显示宽度（列数）
     * @return 补齐后的字符串；已达/超过目标宽度时原样返回
     */
    static String pad(String text, int target) {
        int width = displayWidth(text);
        if (width >= target) {
            return text;
        }
        return text + repeat(' ', target - width);
    }

    /**
     * 计算字符串在等宽终端中的显示宽度（列数）。
     *
     * <p>规则：先剔除 ANSI 转义序列（它们不占列，但会计入 {@code length()}）；
     * 再逐码点判断——CJK 统一表意文字、全角标点、假名、谚文、Emoji 等
     * 东亚宽字符按 2 列计，其余按 1 列。</p>
     *
     * <p>覆盖范围以「本项目横幅会出现的字符」为准（ASCII + 中文 + 常用符号 ·），
     * 不做完整 wcwidth 实现，避免引入重型依赖或过度设计。</p>
     */
    static int displayWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String plain = stripAnsi(text);
        int width = 0;
        for (int i = 0; i < plain.length(); ) {
            int cp = plain.codePointAt(i);
            i += Character.charCount(cp);
            width += isWide(cp) ? 2 : 1;
        }
        return width;
    }

    /** 剔除 ANSI CSI 转义序列（形如 ESC[ ... m），返回不含颜色的纯文本。 */
    static String stripAnsi(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\u001b\\[[0-9;]*m", "");
    }

    /**
     * 判断码点是否为「东亚宽字符」（终端占 2 列）。
     *
     * <p>覆盖：CJK 统一表意文字及其扩展 A、CJK 符号与标点、全角形式、
     * 平假名/片假名、谚文、以及常见 Emoji 区段。中点「·」(U+00B7) 属窄字符，
     * 按 1 列处理——这与 PowerShell / Windows Terminal / VS Code 终端的实际渲染一致。</p>
     */
    static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)      // 谚文字母
                || (cp >= 0x2E80 && cp <= 0xA4CF)  // CJK 部首、符号标点、假名、汉字、彝文
                || (cp >= 0xAC00 && cp <= 0xD7A3)  // 谚文音节
                || (cp >= 0xF900 && cp <= 0xFAFF)  // CJK 兼容表意文字
                || (cp >= 0xFE30 && cp <= 0xFE4F)  // CJK 兼容形式
                || (cp >= 0xFF00 && cp <= 0xFF60)  // 全角 ASCII 变体
                || (cp >= 0xFFE0 && cp <= 0xFFE6)  // 全角货币符号
                || (cp >= 0x1F300 && cp <= 0x1F64F) // Emoji / 符号象形
                || (cp >= 0x1F900 && cp <= 0x1F9FF); // 补充符号与象形文字
    }

    private static String repeat(char c, int count) {
        return count <= 0 ? "" : String.valueOf(c).repeat(count);
    }

    private StartupBanner() {
    }
}
