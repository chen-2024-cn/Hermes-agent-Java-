package com.cyk.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * HermesConfig 的模型别名解析、CLI 覆盖优先级与类型安全读取测试。
 *
 * <p>覆盖本轮修复 #2：{@code -m/-t} 通过 {@code models:} 别名段真正生效。</p>
 */
class HermesConfigTest {

    /** 构造一份纯内存配置，避免测试落盘。 */
    private static HermesConfig newConfig(Map<String, Object> data) {
        return new HermesConfig(null, data);
    }

    /** 构造带顶层 model + models 别名段 + agent 段的基线配置。 */
    private static Map<String, Object> baseData() {
        Map<String, Object> cfg = new HashMap<>();

        Map<String, Object> model = new HashMap<>();
        model.put("model", "top-model");
        model.put("base_url", "https://top.example.com");
        model.put("api_key", "sk-top");
        cfg.put("model", model);

        Map<String, Object> models = new HashMap<>();
        // fast: 只写了 model 和 temperature，base_url 应继承顶层
        Map<String, Object> fast = new HashMap<>();
        fast.put("model", "fast-model");
        fast.put("temperature", 0.3);
        models.put("fast", fast);
        // pro: 写了自定义 base_url 和 max_tokens
        Map<String, Object> pro = new HashMap<>();
        pro.put("model", "pro-model");
        pro.put("base_url", "https://pro.example.com");
        pro.put("temperature", 0.9);
        pro.put("max_tokens", 8192);
        models.put("pro", pro);
        cfg.put("models", models);

        Map<String, Object> agent = new HashMap<>();
        agent.put("max_turns", 30);
        agent.put("temperature", 0.7);
        agent.put("max_tokens", 4096);
        cfg.put("agent", agent);

        return cfg;
    }

    @Test
    void shouldUseTopLevelModelWhenNoAliasApplied() {
        HermesConfig config = newConfig(baseData());

        assertThat(config.getCurrentModel()).isEqualTo("top-model");
        assertThat(config.getBaseUrl()).isEqualTo("https://top.example.com");
        assertThat(config.getTemperature()).isEqualTo(0.7);
    }

    @Test
    void applyModelAliasShouldOverrideModelAndTemperature() {
        HermesConfig config = newConfig(baseData());

        boolean applied = config.applyModelAlias("fast");

        assertThat(applied).isTrue();
        assertThat(config.getCurrentModel()).isEqualTo("fast-model");
        // 别名段声明了 temperature 0.3，应覆盖 agent.temperature
        assertThat(config.getTemperature()).isEqualTo(0.3);
        assertThat(config.getActiveProfile()).isNotNull();
        assertThat(config.getActiveProfile().alias()).isEqualTo("fast");
    }

    @Test
    void aliasShouldInheritBaseUrlWhenNotDeclared() {
        HermesConfig config = newConfig(baseData());

        config.applyModelAlias("fast");

        // fast 别名未声明 base_url，应回落到顶层 model.base_url
        assertThat(config.getBaseUrl()).isEqualTo("https://top.example.com");
    }

    @Test
    void aliasShouldUseItsOwnBaseUrlAndMaxTokensWhenDeclared() {
        HermesConfig config = newConfig(baseData());

        config.applyModelAlias("pro");

        assertThat(config.getBaseUrl()).isEqualTo("https://pro.example.com");
        assertThat(config.getMaxTokens()).isEqualTo(8192);
    }

    @Test
    void apiKeyShouldNotChangeWithAlias() {
        HermesConfig config = newConfig(baseData());

        config.applyModelAlias("fast");

        // API Key 不参与别名切换，始终用顶层 model.api_key
        assertThat(config.getApiKey()).isEqualTo("sk-top");
    }

    @Test
    void unknownAliasShouldReturnFalseAndKeepCurrentValues() {
        HermesConfig config = newConfig(baseData());

        boolean applied = config.applyModelAlias("nonexistent");

        assertThat(applied).isFalse();
        // 激活失败后仍沿用顶层配置
        assertThat(config.getCurrentModel()).isEqualTo("top-model");
        assertThat(config.getActiveProfile()).isNull();
    }

    @Test
    void modelProfileForAliasMissingModelFieldShouldBeNull() {
        Map<String, Object> data = baseData();
        @SuppressWarnings("unchecked")
        Map<String, Object> models = (Map<String, Object>) data.get("models");
        // bad 别名缺少 model 字段，应视为无效
        models.put("bad", new HashMap<>(Map.of("temperature", 0.5)));

        HermesConfig config = newConfig(data);

        assertThat(config.getModelProfile("bad")).isNull();
        assertThat(config.applyModelAlias("bad")).isFalse();
    }

    @Test
    void listModelAliasesShouldReturnAllAliases() {
        HermesConfig config = newConfig(baseData());

        assertThat(config.listModelAliases()).containsExactlyInAnyOrder("fast", "pro");
    }

    @Test
    void cliModelOverrideShouldWinOverAlias() {
        HermesConfig config = newConfig(baseData());

        config.applyModelAlias("fast");
        // CLI 直接传真实模型名，优先级应高于别名
        config.setModelName("cli-explicit-model");

        assertThat(config.getCurrentModel()).isEqualTo("cli-explicit-model");
        // 别名的其他字段（temperature）仍生效
        assertThat(config.getTemperature()).isEqualTo(0.3);
    }

    @Test
    void cliTemperatureOverrideShouldWinOverAlias() {
        HermesConfig config = newConfig(baseData());

        config.applyModelAlias("fast"); // 别名 temperature=0.3
        config.setTemperature(0.1);     // CLI 显式覆盖

        assertThat(config.getTemperature()).isEqualTo(0.1);
    }

    @Test
    void setModelNameWithBlankShouldBeIgnored() {
        HermesConfig config = newConfig(baseData());

        config.setModelName("   ");
        config.setModelName(null);

        assertThat(config.getCurrentModel()).isEqualTo("top-model");
    }

    @Test
    void shouldParseStringNumberInYaml() {
        Map<String, Object> data = baseData();
        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) data.get("agent");
        // 模拟用户把数字写成带引号的字符串：max_turns: "42"
        agent.put("max_turns", "42");

        HermesConfig config = newConfig(data);

        // 不应抛 ClassCastException，应安全转换为 int
        assertThat(config.getMaxTurns()).isEqualTo(42);
    }

    @Test
    void invalidStringNumberShouldFallBackToDefault() {
        Map<String, Object> data = baseData();
        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) data.get("agent");
        agent.put("max_turns", "not-a-number");

        HermesConfig config = newConfig(data);

        // 无法解析时回落到 Constants 默认值，不抛异常
        assertThat(config.getMaxTurns())
                .isEqualTo(com.cyk.constant.Constants.DEFAULT_MAX_ITERATIONS);
    }

    @Test
    void shouldReadExtractConfig() {
        Map<String, Object> data = baseData();
        Map<String, Object> extract = new HashMap<>();
        extract.put("enabled", false);
        extract.put("max_insights", 3);
        extract.put("min_messages", 2);
        data.put("extract", extract);

        HermesConfig config = newConfig(data);

        assertThat(config.isExtractEnabled()).isFalse();
        assertThat(config.getMaxInsightsPerSession()).isEqualTo(3);
        assertThat(config.getMinMessagesForExtraction()).isEqualTo(2);
    }

    @Test
    void extractConfigShouldUseDefaultsWhenAbsent() {
        HermesConfig config = newConfig(baseData()); // 无 extract 段

        assertThat(config.isExtractEnabled()).isTrue();
        assertThat(config.getMaxInsightsPerSession()).isEqualTo(5);
        assertThat(config.getMinMessagesForExtraction()).isEqualTo(1);
    }

    @Test
    void shouldTolerateBooleanAsString() {
        Map<String, Object> data = baseData();
        data.put("extract", new HashMap<>(Map.of("enabled", "false")));

        HermesConfig config = newConfig(data);

        assertThat(config.isExtractEnabled()).isFalse();
    }

    // =========================================================================
    // base_url 规范化（防 405：用户误填完整端点）
    // =========================================================================

    @Test
    void baseUrlWithFullEndpointShouldBeStrippedToPrefix() {
        // 用户常见误填：把完整端点填进 base_url，拼接后路径重复 → 服务端 405
        assertThat(HermesConfig.normalizeBaseUrl("https://gw.example.com/v1/chat/completions"))
                .isEqualTo("https://gw.example.com/v1");
    }

    @Test
    void baseUrlShouldStripTrailingSlashesAndWhitespace() {
        assertThat(HermesConfig.normalizeBaseUrl("  https://api.example.com/v1//  "))
                .isEqualTo("https://api.example.com/v1");
    }

    @Test
    void baseUrlWithEndpointAndTrailingSlashShouldBeStripped() {
        // 斜杠与后缀可能交替出现，需循环剥离
        assertThat(HermesConfig.normalizeBaseUrl("https://gw.example.com/v1/chat/completions/"))
                .isEqualTo("https://gw.example.com/v1");
    }

    @Test
    void normalizeBaseUrlShouldKeepValidPrefixUntouched() {
        assertThat(HermesConfig.normalizeBaseUrl("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(HermesConfig.normalizeBaseUrl("https://api.deepseek.com/v1"))
                .isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    void normalizeBaseUrlShouldHandleNullAndBlank() {
        assertThat(HermesConfig.normalizeBaseUrl(null)).isNull();
        assertThat(HermesConfig.normalizeBaseUrl("   ")).isEmpty();
    }

    @Test
    void getBaseUrlShouldReturnNormalizedPrefix() {
        Map<String, Object> data = baseData();
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) data.get("model");
        model.put("base_url", "https://gw.example.com/v1/chat/completions");

        HermesConfig config = newConfig(data);

        // getBaseUrl() 拿到的是可直接拼 "/chat/completions" 的前缀
        assertThat(config.getBaseUrl() + "/chat/completions")
                .isEqualTo("https://gw.example.com/v1/chat/completions");
    }

    @Test
    void aliasBaseUrlShouldBeNormalizedToo() {
        Map<String, Object> data = baseData();
        @SuppressWarnings("unchecked")
        Map<String, Object> models = (Map<String, Object>) data.get("models");
        @SuppressWarnings("unchecked")
        Map<String, Object> pro = (Map<String, Object>) models.get("pro");
        pro.put("base_url", "https://pro.example.com/v1/chat/completions");

        HermesConfig config = newConfig(data);
        config.applyModelAlias("pro");

        assertThat(config.getBaseUrl()).isEqualTo("https://pro.example.com/v1");
    }

    // =========================================================================
    // tools.disabled_toolsets 解析（P0-3 R7）
    // =========================================================================

    /** 在基线配置上追加 tools 段。 */
    private static Map<String, Object> withTools(Object toolsSection) {
        Map<String, Object> data = baseData();
        data.put("tools", toolsSection);
        return data;
    }

    @Test
    void disabledToolsetsShouldParseFromStringList() {
        Map<String, Object> tools = new HashMap<>();
        tools.put("disabled_toolsets", List.of("web", "rag"));

        HermesConfig config = newConfig(withTools(tools));

        assertThat(config.getDisabledToolsets()).containsExactly("web", "rag");
    }

    @Test
    void disabledToolsetsShouldTrimAndSkipBlankEntries() {
        Map<String, Object> tools = new HashMap<>();
        tools.put("disabled_toolsets", Arrays.asList("  web ", "", null, "   "));

        HermesConfig config = newConfig(withTools(tools));

        assertThat(config.getDisabledToolsets()).containsExactly("web");
    }

    @Test
    void disabledToolsetsShouldKeepUnknownNamesWithWarnOnly() {
        // 拼写错误（file_operation 少 s）：WARN 提示但原样返回——
        // 硬拦会阻断「新版本工具集名 + 旧版本程序」的向前兼容
        Map<String, Object> tools = new HashMap<>();
        tools.put("disabled_toolsets", List.of("file_operation", "future_set"));

        HermesConfig config = newConfig(withTools(tools));

        assertThat(config.getDisabledToolsets()).containsExactly("file_operation", "future_set");
    }

    @Test
    void disabledToolsetsShouldBeEmptyWhenAbsent() {
        HermesConfig config = newConfig(baseData()); // 无 tools 段

        assertThat(config.getDisabledToolsets()).isEmpty();
        assertThat(config.isTerminalToolEnabled()).isTrue(); // 缺省仍全开，行为不变
    }

    @Test
    void disabledToolsetsShouldDegradeGracefullyOnWrongType() {
        // 配置写错（字符串而非列表）不得让启动失败：降级空集 + WARN
        HermesConfig config = newConfig(withTools(
                new HashMap<>(Map.of("disabled_toolsets", "web"))));

        assertThat(config.getDisabledToolsets()).isEmpty();
    }

    @Test
    void disabledToolsetsResultShouldBeImmutable() {
        Map<String, Object> tools = new HashMap<>();
        tools.put("disabled_toolsets", List.of("web"));
        HermesConfig config = newConfig(withTools(tools));

        assertThatThrownBy(() -> config.getDisabledToolsets().add("rag"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // =========================================================================
    // 默认配置形态（AC5：tools.enabled 死配置不得复活）
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void defaultConfigToolsSectionShouldUseDisabledToolsetsNotEnabled() {
        Map<String, Object> cfg = HermesConfig.createDefaultConfig();
        Map<String, Object> tools = (Map<String, Object>) cfg.get("tools");

        // 旧键 tools.enabled 从未被读取（附录 A15），不得再出现在新用户默认配置里
        assertThat(tools).doesNotContainKey("enabled");
        // 新键 disabled_toolsets 缺省为空列表（全部工具可用，行为与升级前一致）
        assertThat(tools.get("disabled_toolsets")).isInstanceOf(List.class);
        assertThat((List<Object>) tools.get("disabled_toolsets")).isEmpty();
        // terminal_enabled 保留（旧开关兼容，P0-2 已交付行为不变）
        assertThat(tools.get("terminal_enabled")).isEqualTo(true);
    }
}
