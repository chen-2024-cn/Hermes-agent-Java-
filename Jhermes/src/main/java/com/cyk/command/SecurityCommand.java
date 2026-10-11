package com.cyk.command;

import picocli.CommandLine;

/**
 * <h1>{@code Jhermes security} —— 命令安全自检命令组（JH-SPEC-001 P0-2）</h1>
 *
 * <p>提供不启动模型、不消耗 token、<b>不执行任何命令</b>的离线安全能力查看与验证：</p>
 * <ul>
 *   <li>{@code security check} —— 对一批命令做纯分类（分级 / 命中规则 / 决策），
 *       用于 AC9 人工验证与 {@code --policy=audit} 式误杀排查。</li>
 * </ul>
 *
 * <p>刻意与对话主流程解耦：它是给<b>人</b>看的诊断工具，输出走 stdout（可重定向到文件），
 * 绝不触及 LLM 或真实命令执行，因此在任何环境（含一次性沙盒）都可安全反复运行。</p>
 */
@CommandLine.Command(name = "security", description = "命令安全自检（纯分类，不执行、不联网、零 token）",
        subcommands = {SecurityCheckCommand.class})
public class SecurityCommand implements Runnable {

    @Override
    public void run() {
        // picocli 在无子命令时调用：打印用法
        new CommandLine(this).usage(System.out);
    }
}
