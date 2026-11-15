package com.cyk.prompt;

import com.cyk.bean.Function;
import com.cyk.bean.ModelMessage;
import com.cyk.bean.ToolCall;
import com.cyk.prompt.ComposedPrompt.SegmentRender;
import com.cyk.util.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ContextReport} 测试（JH-SPEC-001 P0-4 R2）。
 *
 * <p>本类用一组"数字好算"的假数据，把报告里的每个数字都手算一遍再对照，
 * 这样一旦统计口径被改坏，测试会立刻报警。</p>
 *
 * <h2>测试数据的手算过程</h2>
 * <pre>
 *   system 正文    : identity 60 + memory-snapshot 40 = 100，段间分隔符 2，合计 102 字符
 *   tools JSON    : 500 字符
 *   history:user  : "a" × 50                          = 50 字符
 *   history:assistant: 正文 20 + 工具名 read_file(9) + 参数 {"path":"a"}(12) = 41 字符
 *   history:tool  : "c" × 30                          = 30 字符
 *   历史里的 system 消息（"sys"）不重复计算
 *   合计 = 102 + 500 + 50 + 41 + 30 = 723 字符，→ ⌈723 ÷ 2.5⌉ = 290 tokens
 * </pre>
 */
class ContextReportTest {

    private static final int TOTAL_CHARS = 723;
    private static final int TOOLS_CHARS = 500;

    private static ComposedPrompt prompt() {
        List<SegmentRender> segments = List.of(
                new SegmentRender("identity", 60, true, null),
                new SegmentRender("rag", 0, false, "requires 工具集 [rag] 未注册"),
                new SegmentRender("memory-snapshot", 40, true, null));
        return new ComposedPrompt("x".repeat(102), segments, "x".repeat(60));
    }

    private static List<ModelMessage> history() {
        ModelMessage assistant = ModelMessage.assistant("b".repeat(20));
        Function function = new Function();
        function.setName("read_file");
        function.setArguments("{\"path\":\"a\"}");
        ToolCall call = new ToolCall();
        call.setFunction(function);
        assistant.setToolCalls(List.of(call));

        return List.of(
                ModelMessage.system("sys"),
                ModelMessage.user("a".repeat(50)),
                assistant,
                ModelMessage.tool("c".repeat(30), "call-1"));
    }

    /** 取出以某个标签开头的那一行（去掉首尾空白后匹配），便于逐行断言。 */
    private static String lineOf(String report, String labelPrefix) {
        return report.lines()
                .filter(l -> l.trim().startsWith(labelPrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("报告中找不到以 [" + labelPrefix + "] 开头的行:\n" + report));
    }

    @Test
    @DisplayName("估算口径：总字符数 723 → 290 tokens；历史中的 system 消息不重复计入")
    void estimateShouldCountEachPartOnceAndIgnoreSystemMessageInHistory() {
        int expected = TokenEstimator.fromChars(TOTAL_CHARS);

        assertThat(expected).isEqualTo(290);
        assertThat(ContextReport.estimateRequestTokens(prompt(), TOOLS_CHARS, history())).isEqualTo(expected);

        // 去掉历史里的 system 消息，结果必须完全一样——证明它根本没被计入
        List<ModelMessage> withoutSystem = history().subList(1, 4);
        assertThat(ContextReport.estimateRequestTokens(prompt(), TOOLS_CHARS, withoutSystem)).isEqualTo(expected);
    }

    @Test
    @DisplayName("assistant 只带 tool_calls、正文为空时，工具名和参数仍要计入")
    void assistantToolCallsShouldBeCounted() {
        ModelMessage assistant = new ModelMessage();
        assistant.setRole("assistant");
        assistant.setContent("");
        Function function = new Function();
        function.setName("run_command");      // 11 字符
        function.setArguments("{\"c\":1}");   // 7 字符
        ToolCall call = new ToolCall();
        call.setFunction(function);
        assistant.setToolCalls(List.of(call));

        String report = ContextReport.render(null, 0, 0, List.of(assistant), null);

        // 18 字符 → ⌈18 ÷ 2.5⌉ = 8 tokens
        assertThat(lineOf(report, "history:assistant (1)")).contains("18").contains("8");
    }

    @Test
    @DisplayName("报告逐行数字正确：各部分字符数、token、占比")
    void reportShouldShowCorrectNumbersPerLine() {
        String report = ContextReport.render(prompt(), 16, TOOLS_CHARS, history(), null);

        assertThat(lineOf(report, "system ")).contains("102");
        assertThat(lineOf(report, "identity")).contains("60");
        assertThat(lineOf(report, "memory-snapshot")).contains("40");
        assertThat(lineOf(report, "(separators)")).contains("2");
        assertThat(lineOf(report, "tools (16)")).contains("500");
        // 50 字符 → ⌈50 ÷ 2.5⌉ = 20 tokens；占比 50 ÷ 723 = 6.9%
        assertThat(lineOf(report, "history:user (1)")).contains("50").contains("20").contains("6.9%");
        assertThat(lineOf(report, "history:assistant (1)")).contains("41");
        assertThat(lineOf(report, "history:tool (1)")).contains("30");
        // 合计行：总字符 723、290 tokens、100%
        assertThat(lineOf(report, "total")).contains("723").contains("290").contains("100.0%");
    }

    @Test
    @DisplayName("未注入的段连同原因一起展示，注入的段不出现在这个区块")
    void skippedSegmentsShouldBeListedWithReason() {
        String report = ContextReport.render(prompt(), 0, 0, List.of(), null);

        assertThat(report).contains("未注入的提示词段").contains("rag：requires 工具集 [rag] 未注册");
        assertThat(report).doesNotContain("identity：");
    }

    @Test
    @DisplayName("API 对账：估算偏高显示正偏差，偏低显示负偏差")
    void apiLineShouldShowSignedDeviation() {
        String over = ContextReport.render(prompt(), 0, 0, List.of(),
                new ContextReport.ApiSample(1100, 1000, 800));
        assertThat(over).contains("prompt_tokens=1000").contains("缓存命中 800")
                .contains("估算 ~1100").contains("+10.0%");

        String under = ContextReport.render(prompt(), 0, 0, List.of(),
                new ContextReport.ApiSample(900, 1000, 0));
        assertThat(under).contains("-10.0%");
    }

    @Test
    @DisplayName("没有 API 样本时明确提示，而不是显示 0 或抛异常")
    void noSampleShouldSayNoApiDataYet() {
        String report = ContextReport.render(prompt(), 0, 0, List.of(), null);

        assertThat(report).contains("尚无 API 实测数据");
        assertThat(report).doesNotContain("偏差");
    }

    @Test
    @DisplayName("边界：提示词未装配、历史为空、总量为 0 时不除零、不抛异常")
    void emptyInputsShouldNotBreak() {
        String report = ContextReport.render(null, 0, 0, null, null);

        assertThat(report).contains("system (未装配)").contains("0.0%");
        assertThat(lineOf(report, "total")).contains("0");
        assertThat(ContextReport.estimateRequestTokens(null, 0, null)).isZero();
    }

    @Test
    @DisplayName("小数点固定为英文句点，不受系统语言影响（德语系统默认是逗号）")
    void decimalSeparatorShouldNotDependOnDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);

            String report = ContextReport.render(prompt(), 16, TOOLS_CHARS, history(),
                    new ContextReport.ApiSample(1100, 1000, 0));

            assertThat(report).contains("6.9%").contains("+10.0%")
                    .doesNotContain("6,9%").doesNotContain("+10,0%");
        } finally {
            // 全局状态必须还原，否则会影响同一进程里后面的其他测试
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("报告是纯文本：不含 Markdown 语法（终端不渲染）")
    void reportShouldBePlainText() {
        String report = ContextReport.render(prompt(), 16, TOOLS_CHARS, history(),
                new ContextReport.ApiSample(1100, 1000, 0));

        assertThat(report).doesNotContain("```").doesNotContain("**").doesNotContain("## ")
                .doesNotContain("\r");
    }
}
