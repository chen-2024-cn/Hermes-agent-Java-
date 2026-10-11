package com.cyk.prompt;

import com.cyk.bean.Skill;
import com.cyk.bean.ToolEntry;
import com.cyk.capability.CapabilityRegistry;
import com.cyk.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 提示词装配系测试的共享脚手架（包级可见，不进生产 API）。
 *
 * <p>装配测试的共同难点是「造一个可控的注册实况」：{@code ToolRegistry} 是全局单例，
 * 直接用它会被 {@code initialize} 的真实注册与用户环境（技能目录、RAG 连通性）污染，
 * 导致矩阵测试在不同机器上结果不同。这里统一用<b>独立实例 + 桩 ToolEntry</b>
 * 构造任意工具组合，既不触碰全局单例，也让「RAG 开/关 × 终端开/关」这类排列可精确表达。</p>
 */
final class PromptComposerTestSupport {

    /** 满配工具集：与生产 {@code ToolRegistry.initialize} 在「全开 + RAG 可用」下等价。 */
    static final Map<String, List<String>> FULL_TOOLSETS = Map.of(
            "file_operations", List.of("grep_files", "read_file", "search_files", "write_file"),
            "memory", List.of("memory_delete", "memory_get", "memory_replace", "memory_save"),
            "skill", List.of("skill_manage"),
            "web", List.of("fetch_page", "web_search"),
            "terminal", List.of("run_command"),
            "rag", List.of("rag_delete", "rag_index", "rag_list", "rag_search"));

    private PromptComposerTestSupport() {
    }

    /**
     * 构造只含指定工具的注册表实例（独立于全局单例）。
     *
     * @param toolsetTools 工具集名 → 该集内工具名列表
     */
    static ToolRegistry registryOf(Map<String, List<String>> toolsetTools) {
        ToolRegistry registry = new ToolRegistry();
        toolsetTools.forEach((toolset, names) -> names.forEach(name -> registry.register(
                new ToolEntry.Builder()
                        .name(name)
                        .toolset(toolset)
                        .schema(Map.of("description", name,
                                "parameters", Map.of("type", "object", "properties", Map.of())))
                        .handler(args -> "ok")
                        .build())));
        return registry;
    }

    /** 满配能力快照。 */
    static CapabilityRegistry fullCapabilities() {
        return CapabilityRegistry.from(registryOf(FULL_TOOLSETS));
    }

    /** 按工具集名到工具名的映射派生能力快照。 */
    static CapabilityRegistry capabilitiesOf(Map<String, List<String>> toolsetTools) {
        return CapabilityRegistry.from(registryOf(toolsetTools));
    }

    /** 固定目录快照（避开测试机的真实 user.dir，保证断言与机器无关）。 */
    static WorkingDirSnapshot workDir() {
        return new WorkingDirSnapshot(Path.of("C:\\proj"), "Windows 11",
                "C:\\Users\\tester", List.of("pom.xml", "src/"));
    }

    /** 造技能 bean（只填索引用到的三字段）。 */
    static Skill skill(String name, String description, String content) {
        Skill skill = new Skill();
        skill.setName(name);
        skill.setDescription(description);
        skill.setContent(content);
        return skill;
    }

    /** 组装装配上下文（固定目录快照）。 */
    static PromptContext contextWith(CapabilityRegistry capabilities, String memory, List<Skill> skills) {
        return new PromptContext(capabilities, workDir(), memory, skills);
    }
}
