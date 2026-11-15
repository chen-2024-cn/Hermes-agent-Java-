package com.cyk.http;

import com.cyk.bean.ChatCompletionResponse;
import com.cyk.bean.Usage;
import com.cyk.config.HermesConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * usage 解析测试：重点验证缓存命中字段 {@code cachedTokens} 的兼容读取（JH-SPEC-001 P0-4 R3）。
 *
 * <p>不同的模型服务商，把"缓存命中了多少 token"放在不同的 JSON 位置：</p>
 * <ul>
 *   <li>OpenAI 风格：{@code usage.prompt_tokens_details.cached_tokens}</li>
 *   <li>DeepSeek 风格：{@code usage.prompt_cache_hit_tokens}</li>
 *   <li>不支持缓存的服务商：什么都没有</li>
 * </ul>
 * <p>要求三种情况都能正确解析，而且字段缺失时<b>不能抛异常</b>（用量只是辅助信息，不能搞崩对话）。</p>
 */
class ModelClientUsageParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelClient newClient() {
        Map<String, Object> model = new HashMap<>();
        model.put("model", "test-model");
        model.put("base_url", "https://example.com");
        model.put("api_key", "sk-test");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("model", model);
        return new ModelClient(new HermesConfig(null, cfg));
    }

    private static Usage parse(String usageJson) throws Exception {
        JsonNode node = MAPPER.readTree(usageJson);
        return newClient().parseUsage(node);
    }

    @Test
    void shouldReadCachedTokens_fromOpenAiStyleNestedField() throws Exception {
        Usage usage = parse("""
                {"prompt_tokens":1000,"completion_tokens":50,"total_tokens":1050,
                 "prompt_tokens_details":{"cached_tokens":800}}
                """);

        assertThat(usage.getPromptTokens()).isEqualTo(1000);
        assertThat(usage.getCompletionTokens()).isEqualTo(50);
        assertThat(usage.getTotalTokens()).isEqualTo(1050);
        assertThat(usage.getCachedTokens()).isEqualTo(800);
    }

    @Test
    void shouldReadCachedTokens_fromDeepSeekStyleTopLevelField() throws Exception {
        Usage usage = parse("""
                {"prompt_tokens":1000,"completion_tokens":50,"total_tokens":1050,
                 "prompt_cache_hit_tokens":640,"prompt_cache_miss_tokens":360}
                """);

        assertThat(usage.getCachedTokens()).isEqualTo(640);
    }

    @Test
    void shouldDefaultToZero_whenProviderReturnsNoCacheField() throws Exception {
        Usage usage = parse("""
                {"prompt_tokens":1000,"completion_tokens":50,"total_tokens":1050}
                """);

        // 字段缺失 → 0，且不抛异常
        assertThat(usage.getCachedTokens()).isZero();
        assertThat(usage.getPromptTokens()).isEqualTo(1000);
    }

    @Test
    void shouldNotThrow_whenDetailsExistButCachedTokensMissingOrNull() throws Exception {
        // details 存在但里面没有 cached_tokens
        assertThat(parse("""
                {"prompt_tokens":10,"prompt_tokens_details":{"audio_tokens":0}}
                """).getCachedTokens()).isZero();

        // details 本身是 null
        assertThat(parse("""
                {"prompt_tokens":10,"prompt_tokens_details":null}
                """).getCachedTokens()).isZero();

        // cached_tokens 显式为 null
        assertThat(parse("""
                {"prompt_tokens":10,"prompt_tokens_details":{"cached_tokens":null}}
                """).getCachedTokens()).isZero();
    }

    @Test
    void shouldPreferNestedField_whenBothStylesPresent() throws Exception {
        Usage usage = parse("""
                {"prompt_tokens":1000,
                 "prompt_tokens_details":{"cached_tokens":500},
                 "prompt_cache_hit_tokens":999}
                """);

        // 两种写法同时出现时，约定以 OpenAI 嵌套字段为准
        assertThat(usage.getCachedTokens()).isEqualTo(500);
    }

    @Test
    void streamingPath_shouldAlsoCarryCachedTokens() throws Exception {
        // 流式响应的 usage 在最后一个 chunk 里，也走同一个 parseUsage，缓存字段应该一并带出
        String sse = """
                data: {"choices":[{"delta":{"content":"ok"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":200,"completion_tokens":3,"total_tokens":203,"prompt_tokens_details":{"cached_tokens":128}}}

                data: [DONE]
                """;

        ChatCompletionResponse resp = newClient().assembleStream(new BufferedReader(new StringReader(sse)), null);

        assertThat(resp.getUsage()).isNotNull();
        assertThat(resp.getUsage().getCachedTokens()).isEqualTo(128);
    }
}
