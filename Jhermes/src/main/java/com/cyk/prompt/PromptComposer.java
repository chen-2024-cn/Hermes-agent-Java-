package com.cyk.prompt;

import com.cyk.capability.CapabilityRegistry;
import com.cyk.prompt.ComposedPrompt.SegmentRender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h1>系统提示词装配器（JH-SPEC-001 P0-3 R1/R5/R7 的运行时核心）</h1>
 *
 * <p>装配流水线（对每个 manifest 段依次执行）：</p>
 * <pre>
 *   PromptLoader 有序段列表
 *      ↓ ① requires 判定（CapabilityRegistry 注册实况 + 技能非空）
 *      ↓    未满足 → 记 SKIP 明细（含原因），跳过
 *      ↓ ② 模板变量替换（{{cwd}}/{{capabilities}}/{{skills_index}}…）
 *      ↓ ③ 顺序拼接（空行分隔）→ text；逐段字符数 → segments 明细
 *   最后追加动态记忆尾段（记忆快照非空才注入）
 *      ↓
 *   ComposedPrompt（文本 + 逐段明细，供 system 消息与 P0-4 /context 消费）
 * </pre>
 *
 * <h2>与旧 Agent#buildSystemPrompt 的本质区别</h2>
 * <ul>
 *   <li><b>判据</b>：旧版唯一条件段（终端指引）判配置项 {@code terminal_enabled}，
 *       其余段（RAG/网页/技能）无条件注入——RAG 关着也教模型用 rag_* 工具（AC2 缺陷）。
 *       新版每段的 requires 判<b>注册实况</b>，指引与能力天然一致（R7/C-01）。</li>
 *   <li><b>重复清除</b>:旧版把全部工具名+描述再打印一遍「## Available Tools」文本清单
 *       （1,548 字符），与 function-calling 的 tools 数组纯重复。新版由
 *       capabilities.md 渲染<b>紧凑清单</b>（工具集→工具名）并附 R5 硬约束：
 *       只可声称清单内工具，缺失能力如实说明。</li>
 *   <li><b>可观测</b>：装配产出逐段明细，预算与诊断不再靠肉眼数字符。</li>
 * </ul>
 *
 * <h2>容错</h2>
 * <ul>
 *   <li>loader 返回空（manifest 打包事故）→ WARN + 仅记忆尾段的最小提示词，会话降级不瘫痪；</li>
 *   <li>模板变量未定义 → 保留原文 + WARN（宁可见残留也不静默丢内容，残留可被测试捕获）。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：无共享可变状态（loader 自带缓存、其余全为局部量），
 * Agent 每回合调用一次，天然线程安全。</p>
 */
public final class PromptComposer {

    private static final Logger logger = LoggerFactory.getLogger(PromptComposer.class);

    /** 动态记忆尾段的固定 id（不在 manifest 中——内容来自 MemoryManager 而非分段文件）。 */
    static final String MEMORY_SNAPSHOT_ID = "memory-snapshot";

    /** 模板变量语法（与 {@link PromptSegment#templateVariables()} 同一模式）。 */
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}");

    private final PromptLoader loader;

    /** 生产入口：classpath 加载器（含默认用户覆盖目录 ~/.jhermes/prompts）。 */
    public PromptComposer() {
        this(new PromptLoader());
    }

    /** 测试/定制入口：注入 loader（覆盖目录、损坏回退等场景）。 */
    public PromptComposer(PromptLoader loader) {
        this.loader = loader;
    }

    /**
     * 装配系统提示词（Agent#buildSystemPrompt 的唯一实现）。
     *
     * @param context 装配上下文（注册实况、目录快照、记忆快照、技能清单）
     * @return 完整提示词 + 逐段装配明细（永不返回 null / 不抛异常）
     */
    public ComposedPrompt compose(PromptContext context) {
        List<PromptSegment> segments = loader.load();
        if (segments.isEmpty()) {
            logger.warn("提示词分段为空（manifest 缺失或损坏），本次装配仅含记忆尾段——请检查打包或用户覆盖目录");
        }

        String skillsIndex = SkillIndexBuilder.build(context.skills());
        Map<String, String> vars = buildVariables(context, skillsIndex);

        List<SegmentRender> details = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        for (PromptSegment segment : segments) {
            String skipReason = evaluate(segment.requires(), context, skillsIndex);
            if (skipReason != null) {
                details.add(SegmentRender.skipped(segment.id(), skipReason));
                continue;
            }
            String body = renderVariables(segment.content(), vars);
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(body);
            details.add(SegmentRender.injected(segment.id(), body.length()));
        }

        // 动态记忆尾段：MEMORY.md/USER.md 预渲染快照，非空才注入（放在段序末尾——
        // 其内容跨会话可变，置于尾部使前面的静态段保持前缀稳定，P0-4 R8）
        String memory = context.memorySnapshot().strip();
        if (!memory.isEmpty()) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(memory);
            details.add(SegmentRender.injected(MEMORY_SNAPSHOT_ID, memory.length()));
        } else {
            details.add(SegmentRender.skipped(MEMORY_SNAPSHOT_ID, "无长期记忆内容"));
        }

        ComposedPrompt composed = new ComposedPrompt(text.toString().strip(), details);
        logger.debug("系统提示词装配完成：{} 字符，注入 {} 段，跳过 {} 段",
                composed.totalChars(), composed.injectedSegments().size(),
                composed.skippedSegments().size());
        return composed;
    }

    // =========================================================================
    // requires 判定（R7：以注册实况为唯一判据）
    // =========================================================================

    /**
     * 判定一段是否注入。
     *
     * @return null 表示注入；非 null 为面向 /context 的跳过原因
     */
    private static String evaluate(PromptRequirements requires, PromptContext context, String skillsIndex) {
        CapabilityRegistry capabilities = context.capabilities();
        for (String toolset : requires.toolsets()) {
            if (!capabilities.hasToolset(toolset)) {
                return "requires 工具集 [" + toolset + "] 未注册";
            }
        }
        for (String tool : requires.tools()) {
            if (!capabilities.hasTool(tool)) {
                return "requires 工具 [" + tool + "] 未注册";
            }
        }
        // requireSkills 的实况判据是「索引文本非空」：与 skills-index 段渲染同源，
        // 判定与内容不可能脱节（技能列表空 → 索引空 → 跳过）
        if (requires.requireSkills() && skillsIndex.isEmpty()) {
            return "本机无技能，索引无内容";
        }
        return null;
    }

    // =========================================================================
    // 模板变量
    // =========================================================================

    /** 汇总全部模板变量的取值（六个变量即三段模板的全部契约，期 2 测试已锁清单）。 */
    private static Map<String, String> buildVariables(PromptContext context, String skillsIndex) {
        WorkingDirSnapshot wd = context.workingDir();
        Map<String, String> vars = new HashMap<>();
        vars.put("cwd", wd.cwd().toString());
        vars.put("os", wd.osName() == null ? "" : wd.osName());
        vars.put("home", wd.home() == null ? "" : wd.home());
        vars.put("entries", wd.entriesBlock());
        vars.put("capabilities", renderCapabilities(context.capabilities()));
        vars.put("skills_index", skillsIndex);
        return vars;
    }

    /**
     * 渲染 {{capabilities}}：紧凑「工具集: 工具名, …」清单。
     *
     * <p>不复制工具描述（描述已在 function-calling 的 tools 数组里，重复即浪费——
     * 这正是旧「Available Tools」清单被删的原因）。工具集/工具名均来自
     * {@link CapabilityRegistry#asSummary()}，与注册实况同源（AC3 的 /help 一致性同理）。</p>
     */
    static String renderCapabilities(CapabilityRegistry capabilities) {
        if (capabilities.toolCount() == 0) {
            return "- （当前无已注册工具：只可依据自身知识回答，不得声称能执行任何操作）";
        }
        StringBuilder sb = new StringBuilder();
        capabilities.asSummary().forEach((toolset, names) -> {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("- ").append(toolset).append(": ").append(String.join(", ", names));
        });
        return sb.toString();
    }

    /**
     * 替换 {{var}} 模板变量。
     *
     * <p>未定义变量保留原文并 WARN：静默替换为空会把「装配 bug」伪装成「文案就长这样」，
     * 保留残留则可被期 2 的「无 {{ 残留」断言与 P0-4 /context 明细立刻暴露。</p>
     *
     * <p>包级可见便于单测独立验证（含未知变量分支）。</p>
     */
    static String renderVariables(String content, Map<String, String> vars) {
        Matcher matcher = TEMPLATE_VAR.matcher(content);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = vars.get(name);
            if (value == null) {
                logger.warn("提示词模板变量 {} 未定义，已原样保留（装配契约被破坏，请检查 manifest/md 文件）",
                        "{{" + name + "}}");
                value = matcher.group(0);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
