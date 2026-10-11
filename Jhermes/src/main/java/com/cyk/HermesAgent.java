package com.cyk;

import com.cyk.command.AuditCommand;
import com.cyk.command.ChatCommand;
import com.cyk.command.ChatRunner;
import com.cyk.command.ResumeCommand;
import com.cyk.command.SessionsCommand;
import com.cyk.config.HermesConfig;
import com.cyk.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "Jhermes", mixinStandardHelpOptions = true,
        versionProvider = HermesAgent.JhermesVersionProvider.class,
        description = "可自我进化的ai agent",
        subcommands = {
                ChatCommand.class,
                SessionsCommand.class,
                ResumeCommand.class,
                AuditCommand.class,
                com.cyk.command.SecurityCommand.class
        })
public class HermesAgent implements Callable<Integer> {

    /**
     * picocli 版本输出提供者（JH-SPEC-001 P0-1 R2）。
     *
     * <p>{@code @Command(version=...)} 注解属性要求编译期常量，而
     * {@link Constants#VERSION} 已改为运行期从构建产物 version.properties
     * 读取（单一事实源 = pom），故必须改走 IVersionProvider。</p>
     */
    static class JhermesVersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{Constants.CLI_NAME + " " + Constants.VERSION};
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(HermesAgent.class);

    @CommandLine.Option(names = {"-c", "--config"}, description = "配置文件路径")
    private String configPath;

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

    /**
     * P0-2 R8：跳过 WRITE 类命令确认。<b>仅命令行参数</b>，不写配置、不读环境变量；
     * 不跳过 DANGEROUS 强确认、不影响 BLOCKED 硬拦。
     */
    @CommandLine.Option(names = {"--yolo"}, description = "跳过写入类命令确认（高危/灾难命令仍需确认或拦截）")
    private boolean yolo;

    /** P0-2 风险缓解：{@code --policy=audit} 只分类记录不拦截（排查误杀）；同样仅本次运行有效。 */
    @CommandLine.Option(names = {"--policy"}, description = "命令策略模式：audit=只记录不拦截（排查误杀）；缺省 enforce")
    private String policyMode;

    public String getConfigPath() {
        return configPath;
    }

    @Override
    public Integer call() throws Exception {
        // Claude Code 式体验：不带任何子命令时直接进入交互对话（等价于 `Jhermes chat`）。
        // 查看用法请用 `Jhermes --help`（由 mixinStandardHelpOptions 提供），不再依赖根命令打印帮助。

        HermesConfig config = HermesConfig.load();
        ChatRunner.applySecurityFlags(config, yolo, policyMode);
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
