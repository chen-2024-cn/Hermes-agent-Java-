package com.cyk.command.slash;

import com.cyk.capability.CapabilityRegistry;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * <h1>斜杠命令执行上下文（handler 与宿主之间唯一的依赖面）</h1>
 *
 * <p><b>为什么要这个对象而不是直接把 Agent 传进去</b>：命令 handler 若直接持有
 * {@code Agent}，就等于拿到整个对话状态机的写权限（能改历史、能触发 LLM 调用），
 * 测试也必须构造一个完整 Agent 才能验证一行帮助文本。收敛为三项最小依赖后：</p>
 * <ul>
 *   <li>handler 只能做它被授权做的事（打印、重试、读能力）；</li>
 *   <li>测试可以三个 lambda 就地构造，无需真实会话与网络；</li>
 *   <li>P0-4 的 {@code /context}、P1-3 的 {@code /model} 追加新依赖时改这一处即可，
 *       不必翻遍所有 handler 的构造点。</li>
 * </ul>
 *
 * @param output        输出通道（生产为 {@code System.out::println}；测试用 List::add 捕获断言）
 * @param capabilities  能力注册实况快照——{@code /help} 的工具表由它渲染，
 *                      与系统提示词的能力段<b>同源</b>（AC3 一致性的构造保证）
 * @param retryLastTurn 宿主的重试动作（{@code /retry} 用）；无可重试回合时的提示
 *                      由宿主实现内部负责，handler 不重复判断
 */
public record SlashCommandContext(Consumer<String> output,
                                  CapabilityRegistry capabilities,
                                  Runnable retryLastTurn) {

    public SlashCommandContext {
        Objects.requireNonNull(output, "output 不能为 null");
        Objects.requireNonNull(capabilities, "capabilities 不能为 null");
        Objects.requireNonNull(retryLastTurn, "retryLastTurn 不能为 null");
    }

    /** 打印一行（null 归一为空行）。异常不在此吞——统一由 {@code dispatch} 的边界处理。 */
    public void println(String line) {
        output.accept(line == null ? "" : line);
    }
}
