package com.cyk.tool;

import com.cyk.config.HermesConfig;
import com.cyk.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tools.disabled_toolsets} 工具集黑名单集成测试（JH-SPEC-001 P0-3 R7）。
 *
 * <p>验证的是真实 {@code ToolRegistry.initialize(config)} 路径：被禁工具集的工具
 * <b>根本不注册</b>——模型感知不到，比提示词约束更彻底（与 terminal_enabled 同哲学）。
 * 同时验证 initialize 的幂等性：同一 JVM 内以不同配置重建不残留上一轮工具
 * （PromptComposer 矩阵测试依赖此性质）。</p>
 *
 * <p>测试纪律（对齐 TerminalToolPolicyIntegrationTest）：</p>
 * <ul>
 *   <li>{@code rag.enabled=false}：不触发数据库连接尝试；</li>
 *   <li>{@code security.audit_enabled=false}：不向真实 {@code ~/.jhermes/logs} 写审计；</li>
 *   <li>用例后 {@code clearForTest()} + {@code resetForTest()} 复位全局状态。</li>
 * </ul>
 */
class ToolRegistryDisabledToolsetsTest {

    @AfterEach
    void tearDown() {
        ToolRegistry.clearForTest();
        SecurityContext.resetForTest();
    }

    /** 构造纯内存配置：仅关心 tools/rag/security 三段，其余走 HermesConfig 缺省值。 */
    private static HermesConfig configWith(List<String> disabledToolsets, boolean terminalEnabled) {
        Map<String, Object> cfg = new HashMap<>();

        Map<String, Object> tools = new HashMap<>();
        tools.put("disabled_toolsets", disabledToolsets);
        tools.put("terminal_enabled", terminalEnabled);
        cfg.put("tools", tools);

        Map<String, Object> rag = new HashMap<>();
        rag.put("enabled", false); // 测试中不尝试连 pgvector
        cfg.put("rag", rag);

        Map<String, Object> security = new HashMap<>();
        security.put("audit_enabled", false); // 不写真实审计文件
        cfg.put("security", security);

        return new HermesConfig(null, cfg);
    }

    private static List<String> registeredNames() {
        return ToolRegistry.getInstance().getAllToolName();
    }

    @Test
    @DisplayName("基线：空黑名单时全部常用工具集都注册齐")
    void baselineWithEmptyBlacklistRegistersAllToolsets() {
        ToolRegistry.initialize(configWith(List.of(), true));

        List<String> names = registeredNames();
        // file_operations / memory / skill / web / terminal 五个工具集的锚点工具
        assertThat(names).contains("read_file", "write_file",
                "memory_save", "memory_get", "skill_manage",
                "web_search", "fetch_page", "run_command");
        // rag.enabled=false → RAG 引擎不建，rag 工具不注册
        assertThat(names).doesNotContain("rag_search", "rag_index");
    }

    @Test
    @DisplayName("disabled_toolsets=[web]：web_search/fetch_page 均不注册")
    void disabledWebToolsetRemovesAllWebTools() {
        ToolRegistry.initialize(configWith(List.of("web"), true));

        assertThat(registeredNames()).doesNotContain("web_search", "fetch_page");
        // 其他工具集不受牵连
        assertThat(registeredNames()).contains("read_file", "skill_manage", "run_command");
    }

    @Test
    @DisplayName("disabled_toolsets=[memory,skill]：多工具集同时禁用")
    void multipleDisabledToolsetsAreAllSkipped() {
        ToolRegistry.initialize(configWith(List.of("memory", "skill"), true));

        assertThat(registeredNames())
                .doesNotContain("memory_save", "memory_get", "memory_delete",
                        "memory_replace", "skill_manage")
                .contains("read_file", "web_search", "run_command");
    }

    @Test
    @DisplayName("黑名单里含 terminal：即便 terminal_enabled=true 也不注册 run_command")
    void disabledTerminalToolsetWinsOverTerminalEnabledFlag() {
        ToolRegistry.initialize(configWith(List.of("terminal"), true));

        assertThat(registeredNames()).doesNotContain("run_command");
        assertThat(registeredNames()).contains("read_file"); // 其余工具正常
    }

    @Test
    @DisplayName("双通道兼容：terminal_enabled=false（旧开关）仍然有效")
    void terminalEnabledFlagStillWorksAlone() {
        ToolRegistry.initialize(configWith(List.of(), false));

        assertThat(registeredNames()).doesNotContain("run_command");
    }

    @Test
    @DisplayName("黑名单含 rag：连 RagEngine.create 都不触发（rag.enabled=true 也跳过）")
    void disabledRagToolsetSkipsEngineCreation() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("tools", Map.of("disabled_toolsets", List.of("rag")));
        cfg.put("rag", Map.of("enabled", true)); // 配置层开着，但黑名单优先
        cfg.put("security", Map.of("audit_enabled", false));

        ToolRegistry.initialize(new HermesConfig(null, cfg));

        assertThat(registeredNames()).doesNotContain("rag_search", "rag_index", "rag_list", "rag_delete");
        assertThat(registeredNames()).contains("read_file", "run_command");
    }

    @Test
    @DisplayName("幂等：换配置重建后不残留上一轮注册的工具")
    void reinitializeWithDifferentConfigLeavesNoResidue() {
        // 第一轮：web 可用
        ToolRegistry.initialize(configWith(List.of(), true));
        assertThat(registeredNames()).contains("web_search");

        // 第二轮：禁用 web —— 若 initialize 不清空，web_search 会残留
        ToolRegistry.initialize(configWith(List.of("web"), true));
        assertThat(registeredNames()).doesNotContain("web_search");

        // 第三轮：恢复空黑名单 —— web 工具应重新注册齐全
        ToolRegistry.initialize(configWith(List.of(), true));
        assertThat(registeredNames()).contains("web_search", "fetch_page");
    }

    @Test
    @DisplayName("未知名黑名单条目无害：只 WARN 不影响其余工具注册")
    void unknownToolsetInBlacklistIsHarmless() {
        ToolRegistry.initialize(configWith(List.of("file_operation"), true)); // 拼写错误（少 s）

        List<String> names = registeredNames();
        // 未知条目不禁用任何东西：file_operations 照常注册
        assertThat(names).contains("read_file", "write_file", "run_command");
    }
}
