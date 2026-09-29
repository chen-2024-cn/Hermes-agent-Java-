package com.cyk.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * ConsoleUi 终端渲染器测试：Markdown 清理、空白规范化、流式行缓冲的正确性。
 *
 * <p>核心验证两类用户痛点修复：</p>
 * <ol>
 *   <li>「回答很多换行且不美观」→ stripMarkdown / StreamingRenderer 的清理与压缩行为</li>
 *   <li>流式跨 token 安全 → 同一文本无论按整段还是按单字符碎片喂给渲染器，
 *       最终输出必须完全一致（这是行缓冲方案的关键不变量）</li>
 * </ol>
 */
class ConsoleUiTest {

    // =========================================================================
    // stripMarkdown（非流式路径）
    // =========================================================================

    @Test
    void stripMarkdownShouldRemoveHeadingMarkers() {
        assertThat(ConsoleUi.stripMarkdown("## 删除方案\n### 方案一：psql"))
                .isEqualTo("删除方案\n方案一：psql");
    }

    @Test
    void stripMarkdownShouldRemoveBoldAndItalic() {
        assertThat(ConsoleUi.stripMarkdown("**PostgreSQL 数据库** `hermes_rag`"))
                .isEqualTo("PostgreSQL 数据库 hermes_rag");
    }

    @Test
    void stripMarkdownShouldStripFenceMarkersButKeepCode() {
        String md = "```sql\nDROP DATABASE hermes_rag;\n```";
        assertThat(ConsoleUi.stripMarkdown(md))
                .isEqualTo("DROP DATABASE hermes_rag;");
    }

    @Test
    void stripMarkdownShouldNotTouchCodeInsideFence() {
        // 代码块内的 # 注释与 * 乘法必须原样保留（不能误伤）
        String md = "```\n#include <stdio.h>\nint a = b * c;\n```";
        assertThat(ConsoleUi.stripMarkdown(md))
                .contains("#include <stdio.h>")
                .contains("int a = b * c;");
    }

    @Test
    void stripMarkdownShouldPreserveHashWhenNotHeading() {
        // 行中/无空格的 # 不是标题标记（如 #include、C#、#1 问题），必须保留
        assertThat(ConsoleUi.stripMarkdown("C# 语言和 #include 指令"))
                .isEqualTo("C# 语言和 #include 指令");
    }

    @Test
    void stripMarkdownShouldCollapseConsecutiveBlankLines() {
        // 用户痛点：模型动辄连续 3+ 个换行
        assertThat(ConsoleUi.stripMarkdown("第一段\n\n\n\n第二段"))
                .isEqualTo("第一段\n\n第二段");
    }

    @Test
    void stripMarkdownShouldTrimLeadingAndTrailingBlankLines() {
        assertThat(ConsoleUi.stripMarkdown("\n\n正文\n\n\n"))
                .isEqualTo("正文");
    }

    @Test
    void stripMarkdownShouldNormalizeListMarkers() {
        assertThat(ConsoleUi.stripMarkdown("* 第一项\n+ 第二项\n- 第三项\n1. 有序项"))
                .isEqualTo("- 第一项\n- 第二项\n- 第三项\n1. 有序项");
    }

    @Test
    void stripMarkdownShouldRemoveQuoteMarker() {
        assertThat(ConsoleUi.stripMarkdown("> 引用内容")).isEqualTo("引用内容");
    }

    @Test
    void stripMarkdownShouldHandleNullAndEmpty() {
        assertThat(ConsoleUi.stripMarkdown(null)).isEmpty();
        assertThat(ConsoleUi.stripMarkdown("")).isEmpty();
        assertThat(ConsoleUi.stripMarkdown("   \n\n  ")).isEmpty();
    }

    @Test
    void stripMarkdownShouldPreservePlainText() {
        String text = "知识库的索引数据存储在 PostgreSQL 数据库 hermes_rag 中。";
        assertThat(ConsoleUi.stripMarkdown(text)).isEqualTo(text);
    }

    @Test
    void stripMarkdownShouldPreserveUnderscoresInIdentifiers() {
        // snake_case 与 __init__ 不能被当斜体处理
        assertThat(ConsoleUi.stripMarkdown("调用 __init__ 与 my_var_name 说明"))
                .isEqualTo("调用 __init__ 与 my_var_name 说明");
    }

    // =========================================================================
    // StreamingRenderer（流式路径，行缓冲）
    // =========================================================================

    /** 把完整文本一次性喂给渲染器，返回收集到的全部输出 */
    private static String renderWhole(String text) {
        StringBuilder collected = new StringBuilder();
        ConsoleUi.StreamingRenderer r = ConsoleUi.newStreamingRenderer(collected::append);
        r.accept(text);
        r.finish();
        return collected.toString();
    }

    /** 把文本按单字符逐个喂给渲染器（模拟最碎的 SSE 切分），返回收集到的全部输出 */
    private static String renderCharByChar(String text) {
        StringBuilder collected = new StringBuilder();
        ConsoleUi.StreamingRenderer r = ConsoleUi.newStreamingRenderer(collected::append);
        for (int i = 0; i < text.length(); i++) {
            r.accept(String.valueOf(text.charAt(i)));
        }
        r.finish();
        return collected.toString();
    }

    @Test
    void streamingShouldCleanMarkdownLikeNonStreaming() {
        String md = "## 标题\n\n**重点** 内容\n\n\n\n结尾";
        assertThat(renderWhole(md)).isEqualTo(ConsoleUi.stripMarkdown(md));
    }

    @Test
    void streamingOutputMustBeTokenBoundarySafe() {
        // 关键不变量：整段喂入 与 逐字符喂入 的最终输出必须一致
        String md = "### 方案一\n```bash\npsql -c \"DROP DATABASE hermes_rag;\"\n```\n**注意**：不可逆\n";
        assertThat(renderCharByChar(md))
                .isEqualTo(renderWhole(md))
                .isEqualTo(ConsoleUi.stripMarkdown(md));
    }

    @Test
    void streamingShouldSuppressLeadingAndTrailingBlankLines() {
        assertThat(renderWhole("\n\n\n正文行\n\n")).isEqualTo("正文行");
    }

    @Test
    void streamingShouldCollapseBlankRunsToOne() {
        assertThat(renderWhole("A\n\n\n\nB")).isEqualTo("A\n\nB");
    }

    @Test
    void streamingShouldKeepCodeBlockContentVerbatim() {
        StringBuilder collected = new StringBuilder();
        ConsoleUi.StreamingRenderer r = ConsoleUi.newStreamingRenderer(collected::append);
        r.accept("```\n# comment stays\nx = a * b\n```\n");
        r.finish();
        assertThat(collected.toString())
                .contains("# comment stays")
                .contains("x = a * b")
                .doesNotContain("```");
    }

    @Test
    void streamingShouldFlushLastLineWithoutNewline() {
        // 流结束时最后一行没有 \n，finish() 必须把它输出
        StringBuilder collected = new StringBuilder();
        ConsoleUi.StreamingRenderer r = ConsoleUi.newStreamingRenderer(collected::append);
        r.accept("第一行\n第二行没有换行结尾");
        boolean emitted = r.finish();
        assertThat(emitted).isTrue();
        assertThat(collected.toString()).isEqualTo("第一行\n第二行没有换行结尾");
    }

    @Test
    void streamingFinishShouldReportFalseForWhitespaceOnlyStream() {
        StringBuilder collected = new StringBuilder();
        ConsoleUi.StreamingRenderer r = ConsoleUi.newStreamingRenderer(collected::append);
        r.accept("\n\n   \n");
        assertThat(r.finish()).isFalse();
        assertThat(collected.toString()).isEmpty();
    }

    // =========================================================================
    // 用户提示符与助手前缀（问题2：醒目标记）
    // =========================================================================

    @Test
    void userPromptShouldContainVisibleMarker() {
        String prompt = ConsoleUi.userPrompt(false);
        // 无论颜色是否启用，符号锚点必须存在（老终端降级场景）
        assertThat(prompt)
                .contains("▶")
                .contains("你:");
    }

    @Test
    void assistantPrefixShouldContainVisibleMarker() {
        assertThat(ConsoleUi.assistantPrefix())
                .contains("◆")
                .contains("Hermes:");
    }

    @Test
    void userPromptWithDividerShouldStartWithNewlineAndDividerChar() {
        String prompt = ConsoleUi.userPrompt(true);
        assertThat(prompt).startsWith("\n");
        assertThat(prompt).contains("─");
    }
}
