package com.cyk.command.slash;

import com.cyk.capability.CapabilityRegistry;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * <h1>斜杠命令执行上下文（handler 与宿主之间唯一的依赖面）</h1>
 *
 * <p><b>为什么要这个对象而不是直接把 Agent 传进去</b>：命令 handler 若直接持有
 * {@code Agent}，就等于拿到整个对话状态机的写权限（能改历史、能触发 LLM 调用），
 * 测试也必须构造一个完整 Agent 才能验证一行帮助文本。收敛为最小依赖后：</p>
 * <ul>
 *   <li>handler 只能做它被授权做的事（打印、重试、读能力、取诊断报告）；</li>
 *   <li>测试可以几个 lambda 就地构造，无需真实会话与网络；</li>
 *   <li>P1-3 的 {@code /model}、{@code /config} 追加新依赖时改这一处即可，
 *       不必翻遍所有 handler 的构造点。</li>
 * </ul>
 *
 * @param output        输出通道（生产为 {@code System.out::println}；测试用 List::add 捕获断言）
 * @param capabilities  能力注册实况快照——{@code /help} 的工具表由它渲染，
 *                      与系统提示词的能力段<b>同源</b>（AC3 一致性的构造保证）
 * @param retryLastTurn 宿主的重试动作（{@code /retry} 用）；无可重试回合时的提示
 *                      由宿主实现内部负责，handler 不重复判断
 * @param contextReport 上下文预算报告的生成器（{@code /context} 用）。
 *                      用 {@link Supplier} 而不是直接传一段文本，是因为报告内容随对话不断变化，
 *                      必须在用户敲下命令的那一刻才现算，而不是创建上下文时就算好。
 */
public record SlashCommandContext(Consumer<String> output,
                                  CapabilityRegistry capabilities,
                                  Runnable retryLastTurn,
                                  Supplier<String> contextReport,
                                  Consumer<String> inputCommand,
                                  Consumer<String> localCommand) {

    public SlashCommandContext(Consumer<String> output, CapabilityRegistry capabilities,
                               Runnable retryLastTurn, Supplier<String> contextReport,
                               Consumer<String> inputCommand) {
        this(output, capabilities, retryLastTurn, contextReport, inputCommand,
                command -> output.accept("当前环境未提供项目指令功能。"));
    }

    public SlashCommandContext {
        Objects.requireNonNull(output, "output 不能为 null");
        Objects.requireNonNull(capabilities, "capabilities 不能为 null");
        Objects.requireNonNull(retryLastTurn, "retryLastTurn 不能为 null");
        Objects.requireNonNull(contextReport, "contextReport 不能为 null");
        Objects.requireNonNull(inputCommand, "inputCommand 不能为 null");
        Objects.requireNonNull(localCommand, "localCommand 不能为 null");
    }

    /**
     * 三参数便利构造：不提供上下文诊断的宿主（主要是只关心 /help、/retry 的测试）使用。
     *
     * <p>此时敲 {@code /context} 会得到一句明确的"未提供"提示，而不是空指针。</p>
     */
    public SlashCommandContext(Consumer<String> output, CapabilityRegistry capabilities, Runnable retryLastTurn) {
        this(output, capabilities, retryLastTurn, () -> "当前环境未提供上下文诊断数据。");
    }

    public SlashCommandContext(Consumer<String> output, CapabilityRegistry capabilities,
                               Runnable retryLastTurn, Supplier<String> contextReport) {
        this(output, capabilities, retryLastTurn, contextReport,
                command -> output.accept("当前环境未提供草稿输入功能。"));
    }

    /** 打印一行（null 归一为空行）。异常不在此吞——统一由 {@code dispatch} 的边界处理。 */
    public void println(String line) {
        output.accept(line == null ? "" : line);
    }
}
