package com.cyk.config;

import com.cyk.constant.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class HermesConfig {
    //YAML映射器
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    //配置文件路径
    private final Path configPath;


    //配置文件数据
    Map<String, Object> config;

    public HermesConfig(Path configPath) {
        this.configPath = configPath;
        this.config = new HashMap<>();
    }


    /**
     * 加载配置文件
     */
    public static HermesConfig load() throws IOException {
        //获取路径
        Path configPath = getConfigPath();
        HermesConfig cfg = new HermesConfig(configPath);

        //判断当前目录下是否存在yml文件
        if (Files.exists(configPath)) {
            cfg.config = yamlMapper.readValue(configPath.toFile(), Map.class);
        } else {
            cfg.config = createDefaultConfig();
            cfg.save();
        }
        return cfg;
    }

    //保存配置文件
    private void save() throws IOException {
        Files.createDirectories(configPath.getParent());
        yamlMapper.writeValue(configPath.toFile(), config);
    }

    private static Map<String, Object> createDefaultConfig() {
        Map<String, Object> cfg = new HashMap<>();

        // 配置模型
        Map<String, Object> model = new HashMap<>();
        model.put("model", "deepseek-v4-pro");
        model.put("base_url", "https://api.deepseek.com");
        model.put("api_key", "");
        cfg.put("model", model);

        // 配置agent
        Map<String, Object> agent = new HashMap<>();
        agent.put("max_turns", Constants.DEFAULT_MAX_ITERATIONS);
        agent.put("gateway_timeout", Constants.DEFAULT_TIMEOUT_SECONDS);
        agent.put("temperature", 0.7);
        agent.put("max_tokens", 4096);
        cfg.put("agent", agent);

        // 配置工具
        Map<String, Object> tools = new HashMap<>();
        tools.put("enabled", Arrays.asList("terminal", "file_operations"));
        cfg.put("tools", tools);


        return cfg;

    }

    /**
     * 获取路径
     */
    public static Path getConfigPath() {
        return Constants.getHermesHome().resolve(Constants.DEFAULT_CONFIG_FILE);
    }

    public int getMaxTurns() {
        return getFromYml("agent.max_turns", Constants.DEFAULT_MAX_ITERATIONS);
    }

    public String getBaseUrl() {
        return getFromYml("model.base_url", null);
    }

    public String getApiKey() {
        return getFromYml("model.api_key", null);
    }

    public String getCurrentModel() {
        return getFromYml("model.model", null);
    }

    public double getTemperature() {
        return getFromYml("agent.temperature", 0.7);
    }

    public int getMaxTokens() {
        return getFromYml("agent.max_tokens", 4096);
    }
    /**
     * 从yml文件中读取数据
     * @param path 点分隔的路径，如 "model.api_key"
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getFromYml(String path, T defaultValue) {
        String[] keys = path.split("\\.");
        Map<String, Object> current = config;
        for (int i = 0; i < keys.length - 1; i++) {
            Object value = current.get(keys[i]);
            if (value instanceof Map) {
                current = (Map<String, Object>) value;
            } else {
                return defaultValue;
            }
        }
        Object result = current.get(keys[keys.length - 1]);
        if (result != null && defaultValue != null) {
            return (T) result;
        }
        return result != null ? (T) result : defaultValue;
    }
}
