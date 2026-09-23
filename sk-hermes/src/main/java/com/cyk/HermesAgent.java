package com.cyk;

import com.cyk.command.ChatCommand;
import com.cyk.command.ChatRunner;
import com.cyk.command.ResumeCommand;
import com.cyk.command.SessionsCommand;
import com.cyk.config.HermesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "Jhermes", mixinStandardHelpOptions = true, version = "2.0.1", description = "可自我进化的ai agent",
        subcommands = {
                ChatCommand.class,
                SessionsCommand.class,
                ResumeCommand.class
        })
public class HermesAgent implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(HermesAgent.class);

    @CommandLine.Option(names = {"-c", "--config"}, description = "配置文件路径")
    private String configPath;

    @CommandLine.Option(names = {"-v", "--verbose"}, description = "详细输出")
    private boolean verbose;

    // ---- 以下选项与 chat 子命令保持一致：根命令默认进入对话，需支持透传 ----

    /** 模型别名或真实模型名（同 chat -m）。null = 不覆盖配置。 */
    @CommandLine.Option(names = {"-m", "--model"}, description = "模型别名（models 段）或模型名")
    private String model;

    /** 温度覆盖（同 chat -t）。Double 包装类型：null = 未传入。 */
    @CommandLine.Option(names = {"-t", "--temperature"}, description = "温度参数（覆盖配置）")
    private Double temperature;

    /** 开启流式输出（同 chat -s）。 */
    @CommandLine.Option(names = {"-s", "--stream"}, description = "启用流式输出")
    private boolean stream;

    /** 显式关闭流式输出（同 chat --no-stream）。 */
    @CommandLine.Option(names = {"--no-stream"}, description = "禁用流式输出")
    private boolean noStream;

    public String getConfigPath() {
        return configPath;
    }

    public boolean isVerbose() {
        return verbose;
    }

    @Override
    public Integer call() throws Exception {
        // Claude Code 式体验：不带任何子命令时直接进入交互对话（等价于 `Jhermes chat`）。
        // 查看用法请用 `Jhermes --help`（由 mixinStandardHelpOptions 提供），不再依赖根命令打印帮助。
        if (verbose) {
            System.out.println("自定义配置: " + configPath);
        }
        HermesConfig config = HermesConfig.load();
        return ChatRunner.start(config, model, temperature,
                ChatRunner.resolveStream(stream, noStream), null);
    }

    public static void main(String[] args) throws IOException {
        int execute = new CommandLine(new HermesAgent())
                .setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
                    System.err.println("执行异常" + ex.getMessage());
                    return 1;
                })
                .execute(args);

        logger.debug("已退出");
        System.exit(execute);

    }
}
