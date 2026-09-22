package com.cyk.config;

import com.cyk.constant.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HermesConfig {
    private static final Logger logger = LoggerFactory.getLogger(HermesConfig.class);

    //YAML映射器
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    //配置文件路径
    private final Path configPath;


    //配置文件数据
    Map<String, Object> config;

    /**
     * 当前激活的模型别名档案。
     * <p>由 {@link #applyModelAlias(String)} 设置；为 null 表示未启用别名，全部走顶层配置。</p>
     */
    private ModelProfile activeProfile;

    /** CLI 直接指定的模型名覆盖值（优先级最高，仅内存生效不落盘）。 */
    private String modelNameOverride;

    /** CLI 直接指定的温度覆盖值（优先级最高，仅内存生效不落盘）。 */
    private Double temperatureOverride;

    /**
     * CLI 是否显式指定流式输出。
     * <p>用 Boolean 表达三态：null=未指定（沿用配置），true/false=显式覆盖。</p>
     */
    private Boolean streamOverride;

    public HermesConfig(Path configPath) {
        this.configPath = configPath;
        this.config = new HashMap<>();
    }

    /**
     * 便于单元测试与「先构造后注入」的场景：直接持有外部传入的配置数据。
     *
     * @param configPath 配置文件路径（可为 null，表示纯内存配置，不落盘）
     * @param config     已解析好的配置 Map
     */
    public HermesConfig(Path configPath, Map<String, Object> config) {
        this.configPath = configPath;
        this.config = config != null ? config : new HashMap<>();
    }

    /**
     * 模型别名档案 —— 对应 config.yaml 中 {@code models:} 段的一个条目。
     *
     * <p>设计要点：别名段只描述「与顶层不同的差异字段」。</p>
     * <ul>
     *   <li>{@code apiKey} 不纳入别名 —— 统一继承顶层 {@code model.api_key}，避免用户在多处粘贴密钥</li>
     *   <li>{@code baseUrl} / {@code temperature} / {@code maxTokens} 为 null 时同样继承顶层配置</li>
     * </ul>
     *
     * @param alias      别名，如 fast / pro
     * @param model      模型名，如 deepseek-chat
     * @param baseUrl    模型服务地址，null 表示继承顶层
     * @param temperature 温度，null 表示继承顶层
     * @param maxTokens  最大输出 token，null 表示继承顶层
     */
    public record ModelProfile(String alias, String model, String baseUrl,
                               Double temperature, Integer maxTokens) {}


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

        // 配置模型别名档案：CLI 用 -m <alias> 快速切换，字段缺省则继承顶层 model/agent 配置
        Map<String, Object> models = new LinkedHashMap<>();
        Map<String, Object> fastProfile = new LinkedHashMap<>();
        fastProfile.put("model", "deepseek-chat");
        fastProfile.put("temperature", 0.3);
        models.put("fast", fastProfile);
        Map<String, Object> proProfile = new LinkedHashMap<>();
        proProfile.put("model", "deepseek-v4-pro");
        proProfile.put("temperature", 0.7);
        models.put("pro", proProfile);
        cfg.put("models", models);

        // 知识提取配置（会话结束时的自我进化行为）
        Map<String, Object> extract = new HashMap<>();
        extract.put("enabled", true);
        extract.put("max_insights", 5);
        extract.put("min_messages", 1);
        cfg.put("extract", extract);

        // 配置工具
        Map<String, Object> tools = new HashMap<>();
        tools.put("enabled", Arrays.asList("terminal", "file_operations"));
        cfg.put("tools", tools);

        // RAG 配置（默认开启，需用户主动启用）
        Map<String, Object> rag = new HashMap<>();
        rag.put("enabled", true);
        Map<String, Object> pgvector = new HashMap<>();
        pgvector.put("url", "jdbc:postgresql://localhost:5432/hermes_rag");
        pgvector.put("username", "hermes");
        pgvector.put("password", "");
        rag.put("pgvector", pgvector);
        Map<String, Object> ragEmbedding = new HashMap<>();
        ragEmbedding.put("model", "BAAI/bge-m3");
        ragEmbedding.put("base_url", "https://api.siliconflow.cn");
        ragEmbedding.put("api_key", "");
        ragEmbedding.put("dimension", 1024);
        ragEmbedding.put("batch_size", 20);
        rag.put("embedding", ragEmbedding);
        Map<String, Object> chunking = new HashMap<>();
        chunking.put("size", 512);
        chunking.put("overlap", 64);
        rag.put("chunking", chunking);
        Map<String, Object> search = new HashMap<>();
        search.put("vector_weight", 0.7);
        search.put("keyword_weight", 0.3);
        search.put("default_top_k", 5);
        rag.put("search", search);
        cfg.put("rag", rag);

        return cfg;

    }

    /**
     * 获取路径
     */
    public static Path getConfigPath() {
        return Constants.getHermesHome().resolve(Constants.DEFAULT_CONFIG_FILE);
    }

    // =========================================================================
    // 模型别名（models: 段）解析
    // =========================================================================

    /**
     * 列出 config.yaml 中 {@code models:} 段定义的全部别名（按文件书写顺序）。
     *
     * @return 别名列表；未定义该段时返回空列表
     */
    public List<String> listModelAliases() {
        Map<String, Object> models = getMap("models");
        return models.isEmpty() ? List.of() : new ArrayList<>(models.keySet());
    }

    /**
     * 按别名取出模型档案。
     *
     * @param alias 别名，如 "fast"
     * @return 档案对象；别名不存在或格式非法时返回 null
     */
    public ModelProfile getModelProfile(String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        Map<String, Object> models = getMap("models");
        Object raw = models.get(alias);
        if (!(raw instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) raw;
        String model = asString(profile.get("model"));
        if (model == null || model.isBlank()) {
            // 别名段里没写 model 视为无效配置：无法确定要调哪个模型
            logger.warn("models.{} 缺少 model 字段，已忽略该别名", alias);
            return null;
        }
        return new ModelProfile(alias, model,
                asString(profile.get("base_url")),
                asDouble(profile.get("temperature")),
                asInt(profile.get("max_tokens")));
    }

    /**
     * 激活某个模型别名（仅影响本次运行的内存配置，不写回 config.yaml）。
     *
     * <p>激活后 {@link #getCurrentModel()} / {@link #getBaseUrl()} /
     * {@link #getTemperature()} / {@link #getMaxTokens()} 会优先返回别名段的值；
     * 别名段未声明的字段自动回落顶层配置。API Key 始终使用顶层
     * {@code model.api_key}，不随别名切换。</p>
     *
     * @param alias 别名；null/空白或别名不存在时不做任何变更
     * @return 是否成功激活
     */
    public boolean applyModelAlias(String alias) {
        ModelProfile profile = getModelProfile(alias);
        if (profile == null) {
            if (alias != null && !alias.isBlank()) {
                logger.warn("未找到模型别名 '{}'，可用别名：{}；将沿用顶层 model 配置", alias, listModelAliases());
            }
            return false;
        }
        this.activeProfile = profile;
        logger.info("已激活模型别名 '{}' → model={}", profile.alias(), profile.model());
        return true;
    }

    /**
     * 运行期直接覆盖模型名（供 CLI 传入真实模型名而非别名时使用）。
     *
     * @param modelName 模型名；null/空白时忽略
     */
    public void setModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return;
        }
        this.modelNameOverride = modelName;
    }

    /**
     * 运行期直接覆盖温度。
     *
     * @param temperature 温度值；null 时忽略
     */
    public void setTemperature(Double temperature) {
        if (temperature == null) {
            return;
        }
        this.temperatureOverride = temperature;
    }

    /**
     * @return 当前生效的模型别名档案；未激活别名时为 null
     */
    public ModelProfile getActiveProfile() {
        return activeProfile;
    }

    // =========================================================================
    // 生效值读取（优先级：CLI 覆盖 > 别名段 > 顶层配置 > 代码默认值）
    // =========================================================================

    public int getMaxTurns() {
        return readInt("agent.max_turns", Constants.DEFAULT_MAX_ITERATIONS);
    }

    public String getBaseUrl() {
        if (activeProfile != null && activeProfile.baseUrl() != null) {
            return activeProfile.baseUrl();
        }
        return readString("model.base_url", null);
    }

    public String getApiKey() {
        // API Key 不参与别名切换：统一从顶层 model.api_key 读取
        return readString("model.api_key", null);
    }

    /**
     * @return 当前生效的模型名
     */
    public String getCurrentModel() {
        if (modelNameOverride != null) {
            return modelNameOverride;
        }
        if (activeProfile != null) {
            return activeProfile.model();
        }
        return readString("model.model", null);
    }

    public double getTemperature() {
        if (temperatureOverride != null) {
            return temperatureOverride;
        }
        if (activeProfile != null && activeProfile.temperature() != null) {
            return activeProfile.temperature();
        }
        return readDouble("agent.temperature", 0.7);
    }

    public int getMaxTokens() {
        if (activeProfile != null && activeProfile.maxTokens() != null) {
            return activeProfile.maxTokens();
        }
        return readInt("agent.max_tokens", 4096);
    }

    /**
     * 是否启用流式输出（SSE）。
     *
     * <p>优先级：CLI 覆盖（{@code -s/--stream} 或 {@code --no-stream}）&gt; 配置文件
     * {@code agent.stream} &gt; 默认关闭。</p>
     *
     * @return true 表示逐 token 流式打印
     */
    public boolean isStreamEnabled() {
        if (streamOverride != null) {
            return streamOverride;
        }
        return readBoolean("agent.stream", false);
    }

    /**
     * 运行期覆盖流式开关（仅本次运行生效，不写回配置）。
     *
     * @param enabled true 开启流式；null 表示不覆盖、沿用配置
     */
    public void setStreamEnabled(Boolean enabled) {
        this.streamOverride = enabled;
    }

    // =========================================================================
    // 知识提取配置（extract: 段）
    // =========================================================================

    /** 会话结束时是否自动提取知识存入记忆。 */
    public boolean isExtractEnabled() {
        return readBoolean("extract.enabled", true);
    }

    /** 单次会话最多提取多少条 insight。 */
    public int getMaxInsightsPerSession() {
        return readInt("extract.max_insights", 5);
    }

    /** 触发知识提取所需的最少消息数。 */
    public int getMinMessagesForExtraction() {
        return readInt("extract.min_messages", 1);
    }

    // =========================================================================
    // 底层读取：类型安全的取值 + 数值强转
    // =========================================================================

    /**
     * 取出一级 Map 段（如 models / rag）。
     *
     * @param key 顶层键名
     * @return 对应 Map；不存在或类型不符时返回空 Map（不返回 null，简化调用方判空）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(String key) {
        Object value = config.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /**
     * 读取字符串配置。
     *
     * @param path         点分隔路径，如 "model.api_key"
     * @param defaultValue 缺省值
     */
    private String readString(String path, String defaultValue) {
        return asString(getFromYml(path, null), defaultValue);
    }

    /**
     * 读取 int 配置。
     *
     * <p>YAML 中 {@code max_turns: "30"} 这类被引号包裹的写法会被解析成 String，
     * 直接强转会抛 ClassCastException；这里统一走 {@link #asInt} 做兼容转换。</p>
     */
    private int readInt(String path, int defaultValue) {
        Integer value = asInt(getFromYml(path, null));
        return value != null ? value : defaultValue;
    }

    /**
     * 读取 double 配置，兼容 String / Number 两种 YAML 写法。
     */
    private double readDouble(String path, double defaultValue) {
        Double value = asDouble(getFromYml(path, null));
        return value != null ? value : defaultValue;
    }

    /**
     * 读取 boolean 配置，兼容 String / Boolean 两种 YAML 写法。
     */
    private boolean readBoolean(String path, boolean defaultValue) {
        Object value = getFromYml(path, null);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return defaultValue;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String asString(Object value, String defaultValue) {
        String s = asString(value);
        return s != null ? s : defaultValue;
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException e) {
                logger.warn("配置项无法解析为整数：'{}'，将使用默认值", s);
            }
        }
        return null;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.valueOf(s.trim());
            } catch (NumberFormatException e) {
                logger.warn("配置项无法解析为浮点数：'{}'，将使用默认值", s);
            }
        }
        return null;
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
