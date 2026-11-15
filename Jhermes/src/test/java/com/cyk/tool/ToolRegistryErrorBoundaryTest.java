package com.cyk.tool;

import com.cyk.bean.ToolEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <h1>工具分发的异常边界（JH-SPEC-001 P0-5 R1 / AC2）</h1>
 *
 * <p>改造前 {@code ToolRegistry.dispatch} 没有任何异常边界（附录 A2）：工具处理器内部
 * 一个 {@code ClassCastException}、{@code NullPointerException} 就会穿透 dispatch，
 * 冒泡到 {@code Agent.run()}（那里只捕 IOException）而让整个进程退出，会话不落盘。</p>
 *
 * <p>现在除 {@link OutOfMemoryError} 外一律捕获 {@code Throwable}，降级为 error JSON
 * 回传给模型——工具失败属于「本轮任务受阻」，不是「会话终止」。</p>
 */
class ToolRegistryErrorBoundaryTest {

    private static final ToolRegistry REGISTRY = ToolRegistry.getInstance();

    /** 本用例注册的探针工具，结束后必须清理，避免污染全局单例。 */
    private static final String PROBE = "boundary_probe_tool";

    @AfterEach
    void cleanup() {
        REGISTRY.unregister(PROBE);
    }

    private static void registerProbe(RuntimeException toThrow) {
        REGISTRY.register(new ToolEntry.Builder()
                .name(PROBE)
                .toolset("test")
                .schema(Map.of("description", "边界测试探针",
                        "parameters", Map.of("type", "object", "properties", Map.of())))
                .handler(args -> {
                    throw toThrow;
                })
                .build());
    }

    private static void registerProbe(Error toThrow) {
        REGISTRY.register(new ToolEntry.Builder()
                .name(PROBE)
                .toolset("test")
                .schema(Map.of("description", "边界测试探针",
                        "parameters", Map.of("type", "object", "properties", Map.of())))
                .handler(args -> {
                    throw toThrow;
                })
                .build());
    }

    @Test
    @DisplayName("处理器抛 ClassCastException：返回 error JSON 而不上抛（AC2）")
    void classCastExceptionBecomesErrorJson() {
        registerProbe(new ClassCastException("java.lang.String cannot be cast to java.lang.Number"));

        String result = REGISTRY.dispatch(PROBE, Map.of());

        assertThat(result).contains("\"error\"").contains("工具执行异常")
                .contains("ClassCastException");
    }

    @Test
    @DisplayName("处理器抛 NullPointerException（无 message）：仍能降级并给出类型名")
    void nullPointerExceptionWithoutMessageBecomesErrorJson() {
        registerProbe(new NullPointerException());

        String result = REGISTRY.dispatch(PROBE, Map.of());

        assertThat(result).contains("工具执行异常").contains("NullPointerException");
    }

    @Test
    @DisplayName("处理器抛 Error（非 OOM）：同样被 Throwable 边界收住")
    void nonOomErrorIsContained() {
        registerProbe(new StackOverflowError("probe stack overflow"));

        assertThatCode(() -> {
            String result = REGISTRY.dispatch(PROBE, Map.of());
            assertThat(result).contains("工具执行异常").contains("StackOverflowError");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("OutOfMemoryError 必须原样上抛：JVM 级不可恢复，吞掉只会带病运行")
    void outOfMemoryErrorPropagates() {
        registerProbe(new OutOfMemoryError("probe oom"));

        assertThatThrownBy(() -> REGISTRY.dispatch(PROBE, Map.of()))
                .isInstanceOf(OutOfMemoryError.class)
                .hasMessage("probe oom");
    }

    @Test
    @DisplayName("未注册的工具名：返回可读错误而非 NPE")
    void unknownToolReturnsErrorJson() {
        String result = REGISTRY.dispatch("definitely_not_registered_tool", Map.of());

        assertThat(result).contains("\"error\"").contains("未找到工具");
    }

    @Test
    @DisplayName("处理器返回 null：统一转成错误提示，不把 null 写进对话历史")
    void nullResultBecomesErrorJson() {
        REGISTRY.register(new ToolEntry.Builder()
                .name(PROBE)
                .toolset("test")
                .schema(Map.of("description", "返回 null 的探针",
                        "parameters", Map.of("type", "object", "properties", Map.of())))
                .handler(args -> null)
                .build());

        assertThat(REGISTRY.dispatch(PROBE, Map.of())).contains("工具执行无返回结果");
    }

    @Test
    @DisplayName("异常描述截断到上限：超长 message 不得整段进对话历史")
    void describeErrorTruncatesLongMessages() {
        String longMessage = "x".repeat(ToolRegistry.MAX_ERROR_MESSAGE_CHARS + 500);

        String described = ToolRegistry.describeError(new RuntimeException(longMessage));

        assertThat(described).startsWith("RuntimeException: ");
        assertThat(described).endsWith("...");
        // 类名前缀 + 截断后的 message + 省略号
        assertThat(described.length())
                .isLessThan("RuntimeException: ".length() + ToolRegistry.MAX_ERROR_MESSAGE_CHARS + 8);
    }

    @Test
    @DisplayName("异常无 message 时只回类名")
    void describeErrorUsesClassNameWhenMessageMissing() {
        assertThat(ToolRegistry.describeError(new IllegalStateException()))
                .isEqualTo("IllegalStateException");
    }
}
