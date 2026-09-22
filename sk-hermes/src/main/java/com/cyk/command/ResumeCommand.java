package com.cyk.command;

import com.cyk.config.HermesConfig;
import com.cyk.constant.Constants;
import com.cyk.manager.SessionManager;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * 恢复指定历史会话继续对话（{@code sk-hermes resume <会话ID>}）。
 *
 * <p>不带参数时恢复最近一次会话，等价于 {@code resume --last}。</p>
 */
@CommandLine.Command(name = "resume", description = "恢复历史会话继续对话")
public class ResumeCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-m", "--model"}, description = "模型别名（config.yaml 的 models 段）或模型名")
    private String model;

    @CommandLine.Option(names = {"-t", "--temperature"}, description = "温度参数（覆盖配置）")
    private Double temperature;

    /** 开启流式输出。 */
    @CommandLine.Option(names = {"-s", "--stream"}, description = "启用流式输出")
    private boolean stream;

    /** 显式关闭流式输出。 */
    @CommandLine.Option(names = {"--no-stream"}, description = "禁用流式输出")
    private boolean noStream;

    @CommandLine.Option(names = {"-l", "--last"}, description = "恢复最近一次会话（等价于不带参数）")
    private boolean last;

    @CommandLine.Parameters(index = "0", arity = "0..1",
            description = "要恢复的会话ID（来自 `sk-hermes sessions`）；省略则恢复最近一次会话")
    private String sessionId;

    @Override
    public Integer call() {
        try {
            SessionManager manager = new SessionManager(Constants.getHermesHome());

            // 确定要恢复的会话 id：显式传入 > --last/省略（取最近一次）
            String target = sessionId;
            if ((target == null || target.isBlank()) || last) {
                var sessions = manager.listSessions();
                if (sessions.isEmpty()) {
                    System.out.println("暂无可恢复的历史会话。运行 `sk-hermes chat` 开始对话。");
                    return 0;
                }
                target = sessions.get(0).id; // listSessions 已按最近活跃降序
            }

            if (!manager.sessionExists(target)) {
                System.err.println("会话不存在：" + target);
                System.err.println("运行 `sk-hermes sessions` 查看全部可用会话。");
                return 1;
            }

            System.out.println("恢复会话：" + target);
            HermesConfig config = HermesConfig.load();
            // resume 复用 chat 的启动逻辑，仅多传一个待恢复的 sessionId
            return ChatRunner.start(config, model, temperature, ChatRunner.resolveStream(stream, noStream), target);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }
}
