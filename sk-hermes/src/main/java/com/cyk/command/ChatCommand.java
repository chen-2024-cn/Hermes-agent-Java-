package com.cyk.command;

import com.cyk.HermesAgent;
import com.cyk.agent.Agent;
import com.cyk.config.HermesConfig;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "chat", description = "chat with the bot")
public class ChatCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    private HermesAgent parent;

    @CommandLine.Option(names = {"-m", "--model"}, description = "模型名称", defaultValue = "deepseek-v4-pro")
    private String model;

    @CommandLine.Option(names = {"-t", "--temperature"}, description = "温度参数")
    private double temperature = 0.7;

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
            System.out.println("模型: " + (model != null ? model : "默认"));
            System.out.println("温度: " + temperature);
            //配置
            HermesConfig config = HermesConfig.load();
            Agent agent = new Agent(config);
            agent.run();
            return 0;
        } catch (Exception e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }

}
