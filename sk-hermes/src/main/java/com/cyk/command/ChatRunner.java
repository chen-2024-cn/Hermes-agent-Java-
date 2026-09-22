package com.cyk.command;

import com.cyk.agent.Agent;
import com.cyk.config.HermesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对话启动的公共逻辑。
 *
 * <p>{@code chat} 与 {@code resume} 两个命令都需要「应用 CLI 覆盖项 → 构建 Agent → 运行」，
 * 抽到此处避免重复。CLI 覆盖项仅影响本次运行的内存配置，不写回 config.yaml。</p>
 */
public final class ChatRunner {

    private static final Logger logger = LoggerFactory.getLogger(ChatRunner.class);

    private ChatRunner() {}

    /**
     * 应用命令行覆盖项并启动对话。
     *
     * @param config          已加载的配置
     * @param modelOpt        {@code -m/--model} 的值：既可能是 models 段的别名（如 fast），
     *                        也可能是真实模型名（如 deepseek-chat）；null/空白表示不覆盖
     * @param temperatureOpt  {@code -t/--temperature} 的值；null 表示不覆盖
     * @param streamOpt       流式开关：{@code TRUE} 强制开、{@code FALSE} 强制关、
     *                        {@code null} 表示两个标志都未传（沿用配置 agent.stream）
     * @param resumeSessionId 要恢复的历史会话 id；null 表示新建随机会话
     * @return 进程退出码
     */
    public static int start(HermesConfig config, String modelOpt, Double temperatureOpt,
                            Boolean streamOpt, String resumeSessionId) {
        // 模型：先按别名匹配，命中则激活别名档案；否则当作真实模型名直接覆盖
        if (modelOpt != null && !modelOpt.isBlank()) {
            if (config.getModelProfile(modelOpt) != null) {
                config.applyModelAlias(modelOpt);
            } else {
                config.setModelName(modelOpt);
                logger.info("使用命令行指定模型：{}", modelOpt);
            }
        }

        // 温度：仅在 CLI 显式传入时覆盖
        if (temperatureOpt != null) {
            config.setTemperature(temperatureOpt);
        }

        // 流式：仅在 --stream / --no-stream 显式传入时覆盖
        config.setStreamEnabled(streamOpt);

        // 前置校验：api_key 未配置时 fail-fast，给出人话指引，
        // 避免把请求发出去后才报难懂的 HTTP 401
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("尚未配置 API Key，无法请求模型。");
            System.err.println("请编辑配置文件: " + HermesConfig.getConfigPath());
            System.err.println("将 model.api_key 填入你的密钥（如 sk-xxx），保存后重新运行 hermes 即可。");
            return 1;
        }

        System.out.println("模型: " + config.getCurrentModel());
        System.out.println("温度: " + config.getTemperature());
        System.out.println("流式: " + (config.isStreamEnabled() ? "开启" : "关闭"));

        Agent agent = new Agent(config, resumeSessionId);
        agent.run();
        return 0;
    }

    /**
     * 将互相排斥的 --stream / --no-stream 两个 boolean 标志解析为三态。
     *
     * @param stream   -s/--stream 是否传入
     * @param noStream --no-stream 是否传入
     * @return TRUE / FALSE / null（两者都未传，沿用配置）；若两者同时传入以 --no-stream 为准
     */
    public static Boolean resolveStream(boolean stream, boolean noStream) {
        if (noStream) {
            return Boolean.FALSE;
        }
        if (stream) {
            return Boolean.TRUE;
        }
        return null;
    }
}
