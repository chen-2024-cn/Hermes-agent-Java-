package com.cyk.capability;

import com.cyk.bean.ToolEntry;
import com.cyk.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CapabilityRegistry（真实能力单点真相）单元测试（JH-SPEC-001 P0-3 R7）。
 *
 * <p>全部用独立的 {@code new ToolRegistry()} 实例派生，不触碰全局单例——
 * 这正是 {@code from(ToolRegistry)} 接受实例而非读单例的设计目的。</p>
 */
class CapabilityRegistryTest {

    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
        registerTool("write_file", "file_operations");
        registerTool("read_file", "file_operations");
        registerTool("run_command", "terminal");
        registerTool("web_search", "web");
        registerTool("fetch_page", "web");
    }

    private void registerTool(String name, String toolset) {
        toolRegistry.register(new ToolEntry.Builder()
                .name(name)
                .toolset(toolset)
                .schema(Map.of("description", name,
                        "parameters", Map.of("type", "object", "properties", Map.of())))
                .handler(args -> "ok")
                .build());
    }

    @Test
    @DisplayName("按工具集分组：集合名与集内工具名均按字典序")
    void shouldGroupByToolsetWithStableOrdering() {
        CapabilityRegistry capabilities = CapabilityRegistry.from(toolRegistry);

        // 工具集字典序：file_operations < terminal < web
        assertThat(capabilities.toolsets())
                .containsExactly("file_operations", "terminal", "web");
        // 集内工具名字典序：注册顺序是 write_file 在前，输出仍须排序
        assertThat(capabilities.toolNames("file_operations"))
                .containsExactly("read_file", "write_file");
        assertThat(capabilities.toolNames("web"))
                .containsExactly("fetch_page", "web_search");
    }

    @Test
    @DisplayName("存在性判定：hasToolset/hasTool 如实反映注册实况")
    void shouldAnswerPresenceQueries() {
        CapabilityRegistry capabilities = CapabilityRegistry.from(toolRegistry);

        assertThat(capabilities.hasToolset("terminal")).isTrue();
        assertThat(capabilities.hasToolset("rag")).isFalse();
        assertThat(capabilities.hasTool("run_command")).isTrue();
        assertThat(capabilities.hasTool("rag_search")).isFalse();
        // 空注册表派生：一切存在性判定为 false（RAG 关闭时的典型形态）
        CapabilityRegistry empty = CapabilityRegistry.from(new ToolRegistry());
        assertThat(empty.hasToolset("file_operations")).isFalse();
        assertThat(empty.toolCount()).isZero();
    }

    @Test
    @DisplayName("toolCount 与 allToolNames 全量一致")
    void shouldCountToolsConsistently() {
        CapabilityRegistry capabilities = CapabilityRegistry.from(toolRegistry);

        assertThat(capabilities.toolCount()).isEqualTo(5);
        assertThat(capabilities.allToolNames()).hasSize(5)
                .contains("read_file", "write_file", "run_command", "web_search", "fetch_page");
    }

    @Test
    @DisplayName("asSummary 是 /help 与 capabilities.md 的同源骨架：内容对、不可变")
    void summaryShouldBeCompleteAndImmutable() {
        CapabilityRegistry capabilities = CapabilityRegistry.from(toolRegistry);

        Map<String, List<String>> summary = capabilities.asSummary();
        assertThat(summary).containsOnlyKeys("file_operations", "terminal", "web");
        assertThat(summary.get("terminal")).containsExactly("run_command");

        // 消费方（/help 渲染、提示词装配）误改摘要不得反向污染能力注册表
        assertThatThrownBy(() -> summary.put("hacked", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> summary.get("web").add("evil_tool"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("entries 对不存在的工具集返回空列表而非 null")
    void entriesOfUnknownToolsetShouldBeEmptyList() {
        CapabilityRegistry capabilities = CapabilityRegistry.from(toolRegistry);

        assertThat(capabilities.entries("rag")).isEmpty();
        assertThat(capabilities.toolNames("rag")).isEmpty();
    }

    @Test
    @DisplayName("快照语义：派生后注册表再变化不影响已派生视图")
    void snapshotShouldNotTrackLaterRegistrations() {
        CapabilityRegistry snapshot = CapabilityRegistry.from(toolRegistry);

        // 派生之后再注册（模拟运行期动态加载技能工具），旧快照不应看到
        registerTool("rag_search", "rag");

        assertThat(snapshot.hasToolset("rag")).isFalse();
        assertThat(CapabilityRegistry.from(toolRegistry).hasToolset("rag")).isTrue();
    }

    @Test
    @DisplayName("防御：from(null) 快速失败")
    void fromNullShouldThrow() {
        assertThatThrownBy(() -> CapabilityRegistry.from(null))
                .isInstanceOf(NullPointerException.class);
    }
}
