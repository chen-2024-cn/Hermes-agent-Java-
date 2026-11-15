package com.cyk.capability;

import com.cyk.bean.ToolEntry;
import com.cyk.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <h1>能力注册表（CapabilityRegistry）—— 真实能力的单点真相</h1>
 *
 * <p>JH-SPEC-001 P0-3 R7：由 {@link ToolRegistry} 的<b>注册实况</b>派生的只读视图，
 * 回答「当前进程里模型到底能用哪些工具」这一个问题。三个消费方全部取数于此，
 * 同源故不可能漂移：</p>
 * <ol>
 *   <li>{@code /help} 的能力清单（确定性渲染，<b>不经 LLM</b>，AC3）；</li>
 *   <li>提示词装配的 {@code capabilities.md} 段与各环节 {@code requires} 判定（AC1/AC2）；</li>
 *   <li>启动一致性自检（{@code PromptConsistencyChecker}，R6）。</li>
 * </ol>
 *
 * <p><b>为什么判据必须是注册表而非配置</b>（改造前缺陷 C-01）：旧代码判断
 * 「终端指引是否注入」读的是 {@code tools.terminal_enabled} 配置项，而工具是否
 * 真正可用还受 {@code tools.disabled_toolsets}、RAG 数据库连通性等多个因素影响——
 * 配置与实况可以脱节（配置开着但工具没注册上，提示词却教模型去用）。
 * 本类以「注册成功的工具」为唯一判据，指北针从「用户想要什么」换成「实际有什么」。</p>
 *
 * <p><b>不可变快照</b>：构造时定格分组结果，同一会话内多处消费安全；
 * 注册表变化（重新 {@code initialize}）后需重新 {@link #from}/{@link #current()} 派生。</p>
 */
public final class CapabilityRegistry {

    /** 工具集名 → 该集内工具条目（外层与内层均已按名称字典序排序，不可变）。 */
    private final Map<String, List<ToolEntry>> byToolset;

    private CapabilityRegistry(Map<String, List<ToolEntry>> byToolset) {
        this.byToolset = byToolset;
    }

    /**
     * 从指定注册表实例派生能力快照。
     *
     * <p>接受<b>实例</b>而非读全局单例，是为了可测试性：单测可用独立的
     * {@code new ToolRegistry()} 构造任意工具组合，不触碰全局状态。</p>
     *
     * @param registry 工具注册表实例（通常是 {@link ToolRegistry#getInstance()}）
     */
    public static CapabilityRegistry from(ToolRegistry registry) {
        Objects.requireNonNull(registry, "registry 不能为 null");
        return new CapabilityRegistry(registry.entriesByToolset());
    }

    /** 从全局单例派生当前进程的能力快照（生产入口）。 */
    public static CapabilityRegistry current() {
        return from(ToolRegistry.getInstance());
    }

    /** 已注册的全部工具集名称（按字典序，不可变）。 */
    public Set<String> toolsets() {
        return byToolset.keySet();
    }

    /** 某工具集下的工具条目（按名称字典序，不可变）；工具集不存在时返回空列表。 */
    public List<ToolEntry> entries(String toolset) {
        return byToolset.getOrDefault(toolset, List.of());
    }

    /** 某工具集下的工具名列表（按字典序）；工具集不存在时返回空列表。 */
    public List<String> toolNames(String toolset) {
        return entries(toolset).stream().map(ToolEntry::getName).toList();
    }

    /** 全部已注册工具名（工具集字典序 × 集内工具名字典序，顺序稳定）。 */
    public List<String> allToolNames() {
        List<String> names = new ArrayList<>();
        byToolset.forEach((toolset, entries) ->
                entries.forEach(entry -> names.add(entry.getName())));
        return Collections.unmodifiableList(names);
    }

    /** 某工具集是否至少注册了一个工具（提示词段 requires 判定的主入口）。 */
    public boolean hasToolset(String toolset) {
        List<ToolEntry> entries = byToolset.get(toolset);
        return entries != null && !entries.isEmpty();
    }

    /** 某工具是否已注册（提示词中引用单个工具名时的判定入口）。 */
    public boolean hasTool(String toolName) {
        for (List<ToolEntry> entries : byToolset.values()) {
            for (ToolEntry entry : entries) {
                if (entry.getName().equals(toolName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 已注册工具总数（/help 摘要与能力段渲染用）。 */
    public int toolCount() {
        return byToolset.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 紧凑摘要：工具集名 → 工具名列表（均不可变、已排序）。
     *
     * <p>{@code /help} 与 {@code capabilities.md} 的渲染骨架——两处同源消费，
     * 一致性由构造保证而非纪律保证（AC3 的 HelpConsistencyTest 断言的即此结构）。</p>
     */
    public Map<String, List<String>> asSummary() {
        Map<String, List<String>> summary = new java.util.LinkedHashMap<>();
        byToolset.forEach((toolset, entries) -> summary.put(toolset, toolNames(toolset)));
        return Collections.unmodifiableMap(summary);
    }
}
