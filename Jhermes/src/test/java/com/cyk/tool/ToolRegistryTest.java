package com.cyk.tool;

import com.cyk.bean.ToolEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * ToolRegistry 中央结果截断护栏测试。
 *
 * <p>token 雪球的最后一道防线：任何工具返回超大结果都会在 dispatch 层被截断，
 * 对话历史因此不会无限膨胀。使用独立的测试 ToolRegistry 实例，不污染全局单例。</p>
 */
class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @AfterEach
    void tearDown() {
        registry = null;
    }

    /** 注册一个返回指定长度字符串的测试工具 */
    private void registerEchoTool(String name, String output) {
        registry.register(new ToolEntry.Builder()
                .name(name)
                .toolset("test")
                .schema(Map.of("description", "test tool",
                        "parameters", Map.of("type", "object", "properties", Map.of())))
                .handler(args -> output)
                .build());
    }

    @Test
    void dispatchShouldReturnSmallResultUntouched() {
        registerEchoTool("small", "{\"ok\":true}");
        assertThat(registry.dispatch("small", Map.of())).isEqualTo("{\"ok\":true}");
    }

    @Test
    void dispatchShouldCapHugeResultAndAttachTruncationNotice() {
        String huge = "x".repeat(ToolRegistry.MAX_TOOL_RESULT_CHARS + 50_000);
        registerEchoTool("huge", huge);

        String result = registry.dispatch("huge", Map.of());
        // 截断后必须显著变短，且带明确的截断提示（模型需要知道自己拿到的不是全部）
        assertThat(result.length()).isLessThan(huge.length());
        assertThat(result).contains("结果过长已截断");
        assertThat(result).startsWith("xxx"); // 头部保留（关键信息通常在头部）
    }

    @Test
    void capResultSizeShouldHonorToolSpecificSmallerLimit() {
        // 工具自身声明更小的 maxResultSizeChars 时，取更严的限制
        registry.register(new ToolEntry.Builder()
                .name("strict")
                .toolset("test")
                .schema(Map.of("description", "strict tool",
                        "parameters", Map.of("type", "object", "properties", Map.of())))
                .handler(args -> "y".repeat(2_000))
                .maxResultSizeChars(500L)
                .build());

        String result = registry.dispatch("strict", Map.of());
        assertThat(result.length()).isLessThan(600); // 500 + 截断提示语
        assertThat(result).contains("结果过长已截断");
    }

    @Test
    void dispatchUnknownToolShouldReturnErrorJson() {
        String result = registry.dispatch("no_such_tool", Map.of());
        assertThat(result).contains("error").contains("未找到工具");
    }

    @Test
    void capResultSizeShouldConvertNullResultToError() {
        // handler 意外返回 null 时不能让 NPE 冲到 Agent 主循环
        String capped = ToolRegistry.capResultSize(null, null);
        assertThat(capped).contains("error");
    }

    // =========================================================================
    // 按工具集分组 API（P0-3：CapabilityRegistry 的数据源）
    // =========================================================================

    /** 注册一个指定工具集的测试工具 */
    private void registerToolInSet(String name, String toolset) {
        registry.register(new ToolEntry.Builder()
                .name(name)
                .toolset(toolset)
                .schema(Map.of("description", name,
                        "parameters", Map.of("type", "object", "properties", Map.of())))
                .handler(args -> "ok")
                .build());
    }

    @Test
    void entriesByToolsetShouldGroupAndSortDeterministically() {
        registerToolInSet("zebra_tool", "alpha_set");
        registerToolInSet("apple_tool", "alpha_set");
        registerToolInSet("single_tool", "beta_set");

        var grouped = registry.entriesByToolset();

        // 外层工具集字典序：alpha_set < beta_set（与注册顺序无关）
        assertThat(grouped.keySet()).containsExactly("alpha_set", "beta_set");
        // 内层工具名字典序：zebra 先注册但 apple 排前——前缀稳定性依赖此排序（P0-4 R8）
        assertThat(grouped.get("alpha_set").stream().map(ToolEntry::getName).toList())
                .containsExactly("apple_tool", "zebra_tool");
        assertThat(grouped.get("beta_set")).hasSize(1);
    }

    @Test
    void entriesByToolsetShouldReturnImmutableStructure() {
        registerToolInSet("some_tool", "some_set");

        var grouped = registry.entriesByToolset();

        // 消费方（CapabilityRegistry / /help 渲染）误改不得反向污染注册表内部状态
        assertThatThrownBy(() -> grouped.put("hacked", java.util.List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> grouped.get("some_set").clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void entriesByToolsetOnEmptyRegistryShouldBeEmptyMap() {
        assertThat(registry.entriesByToolset()).isEmpty();
    }
}