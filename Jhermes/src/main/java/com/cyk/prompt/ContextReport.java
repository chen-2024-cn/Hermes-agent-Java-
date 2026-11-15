package com.cyk.prompt;

import com.cyk.bean.ModelMessage;
import com.cyk.bean.ToolCall;
import com.cyk.util.TokenEstimator;

import java.util.List;
import java.util.Locale;

/**
 * <h1>上下文预算报告（JH-SPEC-001 P0-4 R2，{@code /context} 命令的内容生成器）</h1>
 *
 * <h2>它回答什么问题？</h2>
 * <p>每次发给模型的请求，都由三大块组成，它们加起来就是"输入 token"，也是你要付的钱：</p>
 * <ol>
 *   <li><b>system 提示词</b>：身份、规则、能力清单、记忆、技能索引……（由 {@link PromptComposer} 装配）</li>
 *   <li><b>tools 定义</b>：所有工具的"说明书"JSON（名称、描述、参数结构）</li>
 *   <li><b>对话历史</b>：用户说的话、模型的回复、工具返回的结果</li>
 * </ol>
 * <p>想省 token，得先知道钱花在哪一块。本类把这三块分别量出来，按占比列成表格。</p>
 *
 * <h2>为什么是"纯函数"？</h2>
 * <p>本类所有方法都是 static，输入什么就输出什么，不读文件、不联网、不依赖 Agent。
 * 好处是单元测试里随手构造几个假数据就能验证，不需要启动整个程序。
 * Agent 只负责把真实数据递进来。</p>
 *
 * <h2>"估算"和"实测"的区别（很重要）</h2>
 * <ul>
 *   <li><b>估算</b>：我们自己算的，字符数 ÷ 2.5（见 {@link TokenEstimator}）。发请求<b>之前</b>就能知道，但不精确。</li>
 *   <li><b>实测</b>：模型服务商在响应里告诉我们的 {@code prompt_tokens}，是真实计费口径，但只能在请求<b>之后</b>知道。</li>
 * </ul>
 * <p>报告末尾会把两者对比，给出差距百分比。差距大说明估算系数该重新校准了。</p>
 */
public final class ContextReport {

    private ContextReport() {
        // 工具类，不允许 new
    }

    /**
     * 一次真实 API 调用留下的"对账样本"：当时的估算值 vs 服务商返回的实测值。
     *
     * <p>为什么估算值要在<b>发请求前</b>就记下来？因为请求完成后，历史里会多出模型的回复、
     * 工具结果等新消息。如果事后拿"现在的估算"去对比"上一次请求的实测"，两边的内容就不一致了，
     * 差距会虚高。所以必须在发请求的那一刻把估算值存好，和这次响应配对。</p>
     *
     * @param estimatedTokens 该次请求发出前，我们估算的输入 token 数
     * @param promptTokens    服务商返回的实测输入 token 数
     * @param cachedTokens    其中命中前缀缓存的 token 数（服务商不返回时为 0）
     */
    public record ApiSample(int estimatedTokens, int promptTokens, int cachedTokens) {
    }

    /** 各部分的字符数汇总（内部使用）。 */
    private record Measure(int systemChars, int toolsChars,
                           int userChars, int userCount,
                           int assistantChars, int assistantCount,
                           int toolResultChars, int toolResultCount) {

        int totalChars() {
            return systemChars + toolsChars + userChars + assistantChars + toolResultChars;
        }
    }

    // =========================================================================
    // 对外方法一：发请求前估算输入 token（Agent 每次调模型前都会调用）
    // =========================================================================

    /**
     * 估算"如果现在发请求，输入大约多少 token"。
     *
     * @param prompt     系统提示词的装配结果（尚未装配时传 null，按 0 计）
     * @param toolsChars 序列化后的 tools JSON 字符数
     * @param history    当前对话历史（可含 system 消息，会被自动忽略，避免和 prompt 重复计算）
     * @return 估算的输入 token 数
     */
    public static int estimateRequestTokens(ComposedPrompt prompt, int toolsChars, List<ModelMessage> history) {
        return TokenEstimator.fromChars(measure(prompt, toolsChars, history).totalChars());
    }

    // =========================================================================
    // 对外方法二：渲染 /context 的输出文本
    // =========================================================================

    /**
     * 渲染完整的上下文预算报告。
     *
     * @param prompt     系统提示词装配结果；null 表示尚未装配
     * @param toolCount  发给模型的工具个数（只用于显示）
     * @param toolsChars 序列化后的 tools JSON 字符数
     * @param history    当前对话历史
     * @param sample     最近一次 API 对账样本；null 表示本会话还没收到过带用量的响应
     * @return 多行纯文本（终端不渲染 Markdown，所以不用任何 Markdown 语法）
     */
    public static String render(ComposedPrompt prompt, int toolCount, int toolsChars,
                                List<ModelMessage> history, ApiSample sample) {
        return render(prompt, toolCount, toolsChars, history, sample, null);
    }

    /**
     * 同上，另带一行"历史压缩状态"说明（P0-4 R7）。
     *
     * <p>为什么需要它：上面表格量的是<b>真实历史</b>，而压缩后真正发给模型的更小。
     * 不说明的话，用户会以为表格里的数字就是账单上的数字。传 null 表示不显示这一行。</p>
     *
     * @param compressionNote 压缩状态说明；null 不显示
     */
    public static String render(ComposedPrompt prompt, int toolCount, int toolsChars,
                                List<ModelMessage> history, ApiSample sample, String compressionNote) {
        Measure m = measure(prompt, toolsChars, history);
        int total = m.totalChars();

        // 换行统一用 \n 而不是 %n：%n 在 Windows 上会变成 \r\n，
        // 与 /help 等其他输出的 LF 约定不一致，也会让"纯文本无 \r"的测试失败
        StringBuilder sb = new StringBuilder();
        sb.append("◆ 上下文预算明细（token 为估算值：字符数 ÷ ")
                .append(TokenEstimator.CHARS_PER_TOKEN).append("，仅供参考）\n\n");
        sb.append(String.format(Locale.ROOT, "  %-28s %7s %9s %7s\n", "item", "chars", "~tokens", "share"));

        // ---- 第一块：system 提示词，下面缩进列出每一段 ----
        if (prompt == null) {
            sb.append(row("system (未装配)", 0, total));
        } else {
            sb.append(row("system", m.systemChars(), total));
            int injectedSum = 0;
            for (ComposedPrompt.SegmentRender segment : prompt.injectedSegments()) {
                sb.append(row("  " + segment.id(), segment.charCount(), total));
                injectedSum += segment.charCount();
            }
            // 段与段之间用空行分隔，这些分隔符也占字符；单独列出来，才能让各行加起来对得上
            int separators = m.systemChars() - injectedSum;
            if (separators > 0) {
                sb.append(row("  (separators)", separators, total));
            }
        }

        // ---- 第二块：tools 定义 ----
        sb.append(row("tools (" + toolCount + ")", m.toolsChars(), total));

        // ---- 第三块：对话历史，按角色拆开 ----
        sb.append(row("history:user (" + m.userCount() + ")", m.userChars(), total));
        sb.append(row("history:assistant (" + m.assistantCount() + ")", m.assistantChars(), total));
        sb.append(row("history:tool (" + m.toolResultCount() + ")", m.toolResultChars(), total));

        // ---- 合计：按"总字符数"换算，而不是把每行的 token 加起来（每行都向上取整，会越加越偏高）----
        sb.append(String.format(Locale.ROOT, "  %-28s %7d %9d %7s\n",
                "total", total, TokenEstimator.fromChars(total), percent(total, total)));

        // ---- 没有注入的提示词段：告诉用户"为什么某个能力的指引不在里面" ----
        if (prompt != null && !prompt.skippedSegments().isEmpty()) {
            sb.append("\n  未注入的提示词段（条件不满足，不占 token）：\n");
            for (ComposedPrompt.SegmentRender segment : prompt.skippedSegments()) {
                sb.append("    ").append(segment.id()).append("：").append(segment.skipReason()).append('\n');
            }
        }

        // ---- 历史压缩状态（上表量的是真实历史，压缩后实际发出的更小，需要说明）----
        if (compressionNote != null && !compressionNote.isBlank()) {
            sb.append('\n').append("  ").append(compressionNote).append('\n');
        }

        long images = history == null ? 0 : history.stream().mapToLong(ModelMessage::imageCount).sum();
        if (images > 0) {
            sb.append("\n  图片: ").append(images)
                    .append(" 张; 上表仅估算文字, 不含视觉 token, 图片实际计费以服务商 usage 为准。\n");
        }

        // ---- 与 API 实测对账 ----
        sb.append('\n').append(renderApiLine(sample)).append('\n');
        sb.append("  说明：估算不含消息格式的额外开销，也受分词差异影响，所以和实测总会有些差距。\n");
        sb.append("  说明：/context 本身不调用模型，也不会进入对话历史。");
        return sb.toString();
    }

    /** 渲染"与 API 实测对账"这一行；没有样本时给出明确提示，而不是显示一排 0 误导用户。 */
    private static String renderApiLine(ApiSample sample) {
        // promptTokens <= 0 的样本没有对账意义（也会导致除以 0），按"没有样本"处理
        if (sample == null || sample.promptTokens() <= 0) {
            return "  尚无 API 实测数据：本会话还没有收到带用量信息的模型响应（发一句话后再看）。";
        }
        // 偏差 = (估算 - 实测) / 实测。正数表示我们估高了，负数表示估低了
        double deviation = (sample.estimatedTokens() - sample.promptTokens()) * 100.0 / sample.promptTokens();
        return String.format(Locale.ROOT,
                "  最近一次 API 实测：prompt_tokens=%d，缓存命中 %d；该次请求发出前的估算 ~%d，偏差 %+.1f%%（正数表示估算偏高）",
                sample.promptTokens(), sample.cachedTokens(), sample.estimatedTokens(), deviation);
    }

    // =========================================================================
    // 内部：测量与格式化
    // =========================================================================

    /** 把 system / tools / 历史各自的字符数量出来。 */
    private static Measure measure(ComposedPrompt prompt, int toolsChars, List<ModelMessage> history) {
        int systemChars = prompt == null ? 0 : prompt.totalChars();
        int userChars = 0;
        int userCount = 0;
        int assistantChars = 0;
        int assistantCount = 0;
        int toolResultChars = 0;
        int toolResultCount = 0;

        if (history != null) {
            for (ModelMessage message : history) {
                if (message == null || message.getRole() == null) {
                    continue;
                }
                switch (message.getRole()) {
                    case "user" -> {
                        userChars += messageChars(message);
                        userCount++;
                    }
                    case "assistant" -> {
                        assistantChars += messageChars(message);
                        assistantCount++;
                    }
                    case "tool" -> {
                        toolResultChars += messageChars(message);
                        toolResultCount++;
                    }
                    default -> {
                        // 历史里的 system 消息就是 prompt 本身，已经在 systemChars 里算过了，
                        // 这里再算一次会重复计费。所以其他角色一律忽略。
                    }
                }
            }
        }
        return new Measure(systemChars, Math.max(0, toolsChars),
                userChars, userCount, assistantChars, assistantCount, toolResultChars, toolResultCount);
    }

    /**
     * 一条消息的字符数：正文 + 它携带的工具调用（工具名和参数也会发给模型，也要计费）。
     *
     * <p>注意：assistant 消息经常"正文为空，只带 tool_calls"，如果只数 content 会严重低估。</p>
     */
    private static int messageChars(ModelMessage message) {
        int chars = message.getContent() == null ? 0 : message.getContent().length();
        List<ToolCall> calls = message.getToolCalls();
        if (calls != null) {
            for (ToolCall call : calls) {
                if (call == null || call.getFunction() == null) {
                    continue;
                }
                String name = call.getFunction().getName();
                String arguments = call.getFunction().getArguments();
                chars += (name == null ? 0 : name.length()) + (arguments == null ? 0 : arguments.length());
            }
        }
        return chars;
    }

    /** 渲染表格中的一行：名称、字符数、估算 token、占比。 */
    private static String row(String label, int chars, int totalChars) {
        return String.format(Locale.ROOT, "  %-28s %7d %9d %7s\n",
                label, chars, TokenEstimator.fromChars(chars), percent(chars, totalChars));
    }

    /**
     * 计算占比字符串。
     *
     * <p>两点细节：total 为 0 时直接返回 "0.0%"，避免除以 0；
     * 使用 {@link Locale#ROOT} 是为了不受用户电脑语言影响——
     * 德语系统默认的小数点是逗号，会把 "6.9%" 打印成 "6,9%"。</p>
     */
    private static String percent(int part, int total) {
        if (total <= 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", part * 100.0 / total);
    }
}
