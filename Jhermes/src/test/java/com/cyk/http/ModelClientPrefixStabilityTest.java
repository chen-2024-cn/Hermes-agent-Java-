package com.cyk.http;

import com.cyk.bean.ModelMessage;
import com.cyk.config.HermesConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求体"字节稳定性"测试（JH-SPEC-001 P0-4 R8 / AC3，修复缺陷 C-08）。
 *
 * <h2>背景回顾</h2>
 * <p>模型服务商的前缀缓存是按字节比对请求开头的。工具定义（tools 数组）在请求里排得很靠前，
 * 如果"同样的内容"每次序列化出来的字节顺序都不一样，缓存就永远命不中。
 * 而代码里的工具定义是用 {@code Map.of(...)} 写的，它的遍历顺序每个 JVM 进程都不同。</p>
 *
 * <h2>这个测试怎么证明修好了？</h2>
 * <p>我们没法在一个测试里启动两个 JVM，但可以用一个等价的办法：
 * 造两个<b>内容完全相同、只是 key 的插入顺序不同</b>的 Map。
 * 对普通的 ObjectMapper，插入顺序不同 → 输出顺序不同（这正是漂移的本质）；
 * 对我们修复后的 ObjectMapper，两者输出必须逐字节相同。</p>
 */
class ModelClientPrefixStabilityTest {

    private static ModelClient newClient() {
        Map<String, Object> model = new HashMap<>();
        model.put("model", "test-model");
        model.put("base_url", "https://example.com");
        model.put("api_key", "sk-test");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("model", model);
        return new ModelClient(new HermesConfig(null, cfg));
    }

    /** 造一个工具定义，key 按传入的顺序插入（LinkedHashMap 会保持插入顺序）。 */
    private static Map<String, Object> toolWithKeyOrder(boolean reversed) {
        Map<String, Object> function = new LinkedHashMap<>();
        if (!reversed) {
            function.put("name", "read_file");
            function.put("description", "读取文件");
            function.put("parameters", Map.of("type", "object"));
        } else {
            function.put("parameters", Map.of("type", "object"));
            function.put("description", "读取文件");
            function.put("name", "read_file");
        }
        Map<String, Object> tool = new LinkedHashMap<>();
        if (!reversed) {
            tool.put("type", "function");
            tool.put("function", function);
        } else {
            tool.put("function", function);
            tool.put("type", "function");
        }
        return tool;
    }

    @Test
    void toolsArray_shouldBeByteIdentical_regardlessOfMapInsertionOrder() throws Exception {
        var mapper = ModelClient.newStableObjectMapper();

        String a = mapper.writeValueAsString(
                ModelClient.buildToolsArray(mapper, List.of(toolWithKeyOrder(false))));
        String b = mapper.writeValueAsString(
                ModelClient.buildToolsArray(mapper, List.of(toolWithKeyOrder(true))));

        // 两份内容相同、插入顺序相反的定义，输出必须逐字节相等
        assertThat(a).isEqualTo(b);
    }

    @Test
    void toolsArray_shouldSortKeysAlphabetically() throws Exception {
        var mapper = ModelClient.newStableObjectMapper();

        String json = mapper.writeValueAsString(
                ModelClient.buildToolsArray(mapper, List.of(toolWithKeyOrder(true))));

        // 字母序：外层 function 在 type 前；function 内 description < name < parameters
        assertThat(json).isEqualTo(
                "[{\"function\":{\"description\":\"读取文件\",\"name\":\"read_file\","
                        + "\"parameters\":{\"type\":\"object\"}},\"type\":\"function\"}]");
    }

    @Test
    void plainObjectMapper_wouldDrift_provingTheFixIsNeeded() throws Exception {
        // 对照组：没有修复的普通 ObjectMapper 会保留插入顺序，于是两份内容相同的定义输出不同。
        // 这个断言证明"插入顺序不同 → 输出不同"确实是问题的根源，也说明上面的测试不是空转。
        var plain = new com.fasterxml.jackson.databind.ObjectMapper();

        String a = plain.writeValueAsString(
                ModelClient.buildToolsArray(plain, List.of(toolWithKeyOrder(false))));
        String b = plain.writeValueAsString(
                ModelClient.buildToolsArray(plain, List.of(toolWithKeyOrder(true))));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void fullRequestBody_shouldBeByteIdentical_forSameContent() throws Exception {
        ModelClient client = newClient();
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(ModelMessage.user("你好"));

        // 两次构建请求体，工具定义的 key 插入顺序相反
        String first = client.buildRequestBody(messages, List.of(toolWithKeyOrder(false)), true, true);
        String second = client.buildRequestBody(messages, List.of(toolWithKeyOrder(true)), true, true);

        assertThat(first).isEqualTo(second);
    }
}
