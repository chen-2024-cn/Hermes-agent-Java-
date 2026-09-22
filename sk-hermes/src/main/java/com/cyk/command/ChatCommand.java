package com.cyk.command;

import com.cyk.HermesAgent;
import com.cyk.config.HermesConfig;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "chat", description = "与模型对话")
public class ChatCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private HermesAgent parent;

    /**
     * 模型选择：可以是 config.yaml 中 {@code models:} 段定义的别名（如 fast），
     * 也可以是真实模型名（如 deepseek-chat）。
     * <p>不设 defaultValue：null 表示用户未显式指定，应完全沿用配置文件顶层值。
     * 若给了默认值，将无法区分「用户没传」与「用户传了默认值」，导致总覆盖配置。</p>
     */
    @CommandLine.Option(names = {"-m", "--model"}, description = "模型别名（models 段）或模型名")
    private String model;

    /**
     * 温度覆盖。用包装类型 Double：null 表示未传入（沿用配置），
     * 避免基本类型 double 的默认值 0.0 被误当成用户显式设置。
     */
    @CommandLine.Option(names = {"-t", "--temperature"}, description = "温度参数（覆盖配置）")
    private Double temperature;

    /** 开启流式输出（逐 token 实时打印）。 */
    @CommandLine.Option(names = {"-s", "--stream"}, description = "启用流式输出")
    private boolean stream;

    /** 显式关闭流式输出（优先级高于配置文件 agent.stream）。 */
    @CommandLine.Option(names = {"--no-stream"}, description = "禁用流式输出")
    private boolean noStream;

    @Override
    public Integer call() throws Exception {
        try {
            // 父命令的全局选项
            if (parent != null && parent.isVerbose()) {
                System.out.println("自定义配置: " + parent.getConfigPath());
            }
            if (parent != null && parent.getConfigPath() != null) {
                System.out.println("使用配置: " + parent.getConfigPath());
            }

            HermesConfig config = HermesConfig.load();
            // chat 命令启动新会话（resumeSessionId 传 null）
            return ChatRunner.start(config, model, temperature, ChatRunner.resolveStream(stream, noStream), null);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }

}
