package com.cyk;

import com.cyk.command.ChatCommand;
import com.cyk.config.HermesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "sk-hermes", mixinStandardHelpOptions = true, version = "1.0.0", description = "可自我进化的ai agent",
        subcommands = {
                ChatCommand.class
        })
public class HermesAgent implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(HermesAgent.class);

    @CommandLine.Option(names = {"-c", "--config"}, description = "配置文件路径")
    private String configPath;

    @CommandLine.Option(names = {"-v", "--verbose"}, description = "详细输出")
    private boolean verbose;

    public String getConfigPath() {
        return configPath;
    }

    public boolean isVerbose() {
        return verbose;
    }

    @Override
    public Integer call() throws Exception {
        return new ChatCommand().call();
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
