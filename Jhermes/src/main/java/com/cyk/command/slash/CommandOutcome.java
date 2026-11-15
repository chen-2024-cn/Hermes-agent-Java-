package com.cyk.command.slash;

/**
 * 斜杠命令的分发结果（{@code SlashCommandRegistry#dispatch} 的唯一返回类型）。
 *
 * <p>三态而非 boolean：REPL 主循环必须区分「这行输入根本不是命令」与
 * 「是命令且已处理完」——前者要交给模型作答，后者直接回到提示符。
 * 用 boolean 会把这两种语义挤在一起，日后加命令时必然出错。</p>
 */
public enum CommandOutcome {

    /** 输入不是已注册命令：调用方应把它当普通提问交给模型。 */
    NOT_A_COMMAND,

    /** 命令已执行完毕（输出已由 handler 打印）：继续读下一行输入。 */
    HANDLED,

    /** 命令要求结束会话（exit）：调用方应跳出 REPL 并走正常收尾（持久化 + 知识提取）。 */
    EXIT
}
