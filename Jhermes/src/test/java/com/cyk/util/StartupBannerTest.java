package com.cyk.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StartupBanner 渲染测试。
 *
 * <p>验收关注点全部转化为可执行断言：</p>
 * <ol>
 *   <li><b>无错位</b>：所有框行的<b>显示宽度</b>必须完全相等（含中文/ANSI 时依然成立）；</li>
 *   <li><b>宽度 ≤ 60 列</b>：约束来自需求，窄终端折行是错位的头号元凶；</li>
 *   <li><b>降级路径</b>：colorEnabled=false 时输出不含任何 ANSI 转义（老 conhost 无乱码）；</li>
 *   <li><b>着色路径</b>：真彩色渐变序列存在，且着色不改变可见字符与显示宽度；</li>
 *   <li><b>图字保真</b>：渐变着色不会吃掉空格导致图字塌陷。</li>
 * </ol>
 *
 * <p>渲染一律走 {@link StartupBanner#render(String, boolean)} 显式传色，
 * 不依赖 {@link ConsoleUi#isColorEnabled()} 的运行时探测结果——
 * surefire 无 TTY，探测恒为「无色」，靠探测无法覆盖彩色路径。</p>
 */
class StartupBannerTest {

    /** 按显示宽度（中文算 2 列、ANSI 转义算 0 列）计算的每行宽度。 */
    private static int[] lineWidths(String banner) {
        String[] lines = banner.split("\n", -1);
        int[] widths = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            widths[i] = StartupBanner.displayWidth(lines[i]);
        }
        return widths;
    }

    // =========================================================================
    // 对齐与宽度（无错位 / ≤60 列）
    // =========================================================================

    @Test
    void allLinesShouldHaveIdenticalDisplayWidthInColorMode() {
        int[] widths = lineWidths(StartupBanner.render("2.1.0", true));

        assertThat(widths.length).isGreaterThan(0);
        assertThat(widths)
                .as("彩色模式下每行显示宽度必须一致，否则右边框参差不齐")
                .containsOnly(widths[0]);
    }

    @Test
    void allLinesShouldHaveIdenticalDisplayWidthInPlainMode() {
        int[] widths = lineWidths(StartupBanner.render("2.1.0", false));

        assertThat(widths)
                .as("降级模式下每行显示宽度同样必须一致")
                .containsOnly(widths[0]);
    }

    @Test
    void colorAndPlainModeShouldShareTheSameLayoutWidth() {
        // 关键不变量：着色只是给可见字符套 ANSI，绝不能改变版式宽度
        int[] colored = lineWidths(StartupBanner.render("2.1.0", true));
        int[] plain = lineWidths(StartupBanner.render("2.1.0", false));

        assertThat(colored).isEqualTo(plain);
    }

    @Test
    void bannerShouldNeverExceedSixtyColumns() {
        for (int[] widths : new int[][]{
                lineWidths(StartupBanner.render("2.1.0", true)),
                lineWidths(StartupBanner.render("2.1.0", false)),
        }) {
            for (int w : widths) {
                assertThat(w)
                        .as("横幅宽度受需求硬约束：不超过 60 列")
                        .isLessThanOrEqualTo(60);
            }
        }
    }

    @Test
    void shouldContainCompleteBoxBorder() {
        String[] lines = StartupBanner.render("2.1.0", false).split("\n", -1);

        assertThat(lines[0]).startsWith("\u256d").endsWith("\u256e");   // ╭ ... ╮
        assertThat(lines[lines.length - 1]).startsWith("\u2570").endsWith("\u256f"); // ╰ ... ╯
        // 中间行左右都是竖线
        for (int i = 1; i < lines.length - 1; i++) {
            assertThat(lines[i]).startsWith("\u2502");
            assertThat(lines[i]).endsWith("\u2502");
        }
    }

    // =========================================================================
    // 降级路径（无 ANSI = 老终端无乱码）
    // =========================================================================

    @Test
    void plainModeShouldNotContainAnyAnsiEscape() {
        String banner = StartupBanner.render("2.1.0", false);

        // ESC (U+001B) 一旦出现，老 conhost 就会显示成 ^[ 或方块（用户可见乱码）
        assertThat(banner).doesNotContain("\u001b");
        assertThat(banner).doesNotContain("[38;2;");
        assertThat(banner).doesNotContain("[90m");
    }

    @Test
    void plainModeShouldStillRenderTheAsciiArt() {
        // 降级的正确含义是「失去颜色」而不是「失去内容」
        String banner = StartupBanner.render("2.1.0", false);

        for (String artLine : StartupBanner.ART) {
            assertThat(banner).contains(artLine.trim());
        }
    }

    @Test
    void plainModeShouldContainTaglineAndVersion() {
        String banner = StartupBanner.render("2.1.0", false);

        assertThat(banner).contains("Self-Evolving AI Agent").contains("v2.1.0");
    }

    @Test
    void shouldOmitVersionSegmentWhenVersionIsBlank() {
        assertThat(StartupBanner.render(null, false)).doesNotContain(" v");
        assertThat(StartupBanner.render("   ", false)).doesNotContain(" v");
        assertThat(StartupBanner.tagline(null)).isEqualTo("Self-Evolving AI Agent");
    }

    // =========================================================================
    // 彩色路径（渐变 / 图字保真）
    // =========================================================================

    @Test
    void colorModeShouldEmitTruecolorSequences() {
        String banner = StartupBanner.render("2.1.0", true);

        // 24-bit 真彩色前景序列：ESC[38;2;r;g;bm
        assertThat(banner).contains("\u001b[38;2;");
        assertThat(banner).contains("\u001b[0m"); // 每段颜色后必须复位，防止溢出到后续输出
    }

    @Test
    void colorModeShouldUsePaletteEndpoints() {
        // 首列取起始锚点色（青 #22d3ee），最后一列取终点锚点色（粉 #ec4899）
        int[] first = StartupBanner.gradientColor(0, StartupBanner.ART[0].length());
        int[] last = StartupBanner.gradientColor(StartupBanner.ART[0].length() - 1, StartupBanner.ART[0].length());

        assertThat(first).containsExactly(34, 211, 238);
        assertThat(last).containsExactly(236, 72, 153);
    }

    @Test
    void gradientShouldStayWithinRgbRangeAndTransitionSmoothly() {
        int cols = 50;
        int[] prev = null;
        for (int col = 0; col < cols; col++) {
            int[] rgb = StartupBanner.gradientColor(col, cols);
            for (int channel : rgb) {
                assertThat(channel).isBetween(0, 255);
            }
            if (prev != null) {
                // 每列之间的变化量应远小于全幅跨度（线性插值 = 平滑过渡，无跳变）
                for (int c = 0; c < 3; c++) {
                    assertThat(Math.abs(rgb[c] - prev[c])).isLessThan(20);
                }
            }
            prev = rgb;
        }
    }

    @Test
    void coloringShouldPreserveSpacesAndVisibleCharacters() {
        // 关键回归：早期实现对空格 continue 会把空格从输出中整个丢掉，图字塌成一团
        String artLine = StartupBanner.ART[0];
        String colored = StartupBanner.colorLine(artLine, true);

        assertThat(StartupBanner.stripAnsi(colored)).isEqualTo(artLine);
        assertThat(StartupBanner.displayWidth(colored))
                .as("着色不改变显示宽度")
                .isEqualTo(artLine.length());
    }

    @Test
    void colorModeShouldDimTheTagline() {
        assertThat(StartupBanner.colorTagline("x", true)).contains("\u001b[90m");
        assertThat(StartupBanner.colorTagline("x", false)).isEqualTo("x");
    }

    // =========================================================================
    // 显示宽度计算（对齐的数学基础）
    // =========================================================================

    @Test
    void displayWidthShouldTreatAnsiAsZeroWidth() {
        String colored = "\u001b[38;2;255;0;0mRED\u001b[0m";

        assertThat(StartupBanner.displayWidth(colored)).isEqualTo(3);
        assertThat(StartupBanner.stripAnsi(colored)).isEqualTo("RED");
    }

    @Test
    void displayWidthShouldCountCjkAsTwoColumns() {
        assertThat(StartupBanner.displayWidth("会话累计")).isEqualTo(8);
        assertThat(StartupBanner.displayWidth("ab")).isEqualTo(2);
        assertThat(StartupBanner.displayWidth("a会b")).isEqualTo(4);
    }

    @Test
    void displayWidthShouldHandleEdgeCases() {
        assertThat(StartupBanner.displayWidth(null)).isZero();
        assertThat(StartupBanner.displayWidth("")).isZero();
        // 中点 · 是窄字符（1 列），与 PowerShell / Windows Terminal 实际渲染一致
        assertThat(StartupBanner.isWide('\u00b7')).isFalse();
        assertThat(StartupBanner.isWide('A')).isFalse();
        assertThat(StartupBanner.isWide('会')).isTrue();
        assertThat(StartupBanner.isWide('ァ')).isTrue();  // 片假名
    }

    @Test
    void padShouldFillToTargetDisplayWidthNotCharCount() {
        // 若按 length() 补空格，"会话"（length=2、实际占 4 列）会被补 8 个空格
        // → 实际 12 列，右边框立刻错位。正确做法是按显示宽度补 6 个空格。
        String padded = StartupBanner.pad("会话", 10);

        assertThat(StartupBanner.displayWidth(padded))
                .as("补齐后显示宽度必须精确等于目标列数")
                .isEqualTo(10);
        assertThat(padded)
                .as("2 个汉字（4 列）+ 6 个补齐空格 = 8 个字符")
                .hasSize(8);
    }

    @Test
    void padShouldReturnUnchangedWhenAlreadyWideEnough() {
        assertThat(StartupBanner.pad("verylongtext", 5)).isEqualTo("verylongtext");
        assertThat(StartupBanner.pad("abc", -1)).isEqualTo("abc");
    }

    @Test
    void artShouldBeRectangularAndWithinBudget() {
        // 图字各行等宽是全篇对齐的前提（figlet 程序化拼合保证，此处固化该不变量）
        int expected = StartupBanner.ART[0].length();
        for (String line : StartupBanner.ART) {
            assertThat(line.length())
                    .as("每行图字必须等宽: " + line)
                    .isEqualTo(expected);
        }
        // 图字 + 内边距 + 边框 ≤ 60
        assertThat(expected + 2 * 2 + 2).isLessThanOrEqualTo(60);
    }
}
