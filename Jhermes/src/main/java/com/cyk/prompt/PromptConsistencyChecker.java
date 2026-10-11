package com.cyk.prompt;

import com.cyk.capability.CapabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h1>提示词一致性自检（JH-SPEC-001 P0-3 R6）</h1>
 *
 * <h2>要防的真实故障</h2>
 * <p>提示词文本里写了一个<b>并不存在的工具名</b>——可能是 md 文件里的拼写错误
 * （{@code rag_serach}）、可能是重命名后忘了改文档、也可能是某段的 requires 判据写漏
 * 导致「工具没注册但指引注入了」。模型读到后会照着调用，得到
 * {@code {"error":"未找到工具：xxx"}}，于是反复重试或编造结果——用户看到的表现为
 * 「智能体瞎折腾」，而根因是提示词与实现漂移。这类缺陷<b>编译期与单测都发现不了</b>，
 * 因为提示词是纯文本。</p>
 *
 * <h2>机制</h2>
 * <p>提示词是自然语言，无法穷举合法词表，但工具名有极强的形态特征：
 * {@code [a-z]+(_[a-z]+)+}（全小写 + 下划线分词，如 {@code web_search}）——
 * 这在本项目全部中文提示词里是极低频形态（实测全量 md 仅 12 个匹配）。
 * 于是自检 = 扫出全部候选 → 减去「已注册名字」（工具名 ∪ <b>工具集名</b>，
 * 见 {@link #knownNames}）→ 减去「显式白名单」→ 剩余即违规。</p>
 *
 * <h2>白名单：显式、逐条注释、测试封顶</h2>
 * <p>候选形态会误伤三类合法词：工具<b>参数名</b>（{@code timeout_seconds}）、
 * 工具<b>结果字段名</b>（{@code source_path}）、<b>模板变量</b>（{@code skills_index}）。
 * 白名单不是「随手加一行让红灯变绿」的地方：</p>
 * <ul>
 *   <li>每条必须写明理由（{@link #NON_TOOL_NAMES} 的 value）；</li>
 *   <li>{@code PromptConsistencyCheckerTest} 封顶白名单条数（防无节制膨胀）；</li>
 *   <li>并断言<b>白名单中无一与已注册工具重名</b>——否则白名单会替真实工具遮蔽漏注册。</li>
 * </ul>
 *
 * <h2>两种消费</h2>
 * <ul>
 *   <li><b>运行期</b>：{@link #warnIfInconsistent} 在提示词装配后调用，命中只 WARN
 *       （不阻断会话——自检自身出 bug 不该让用户用不了程序），日志即排查线索；</li>
 *   <li><b>测试期</b>：{@link #checkText} 对每个段文件（无视 requires）扫一遍，
 *       任何违规令 CI 红灯。这是把「文本漂移」纳入编译期纪律的唯一手段。</li>
 * </ul>
 */
public final class PromptConsistencyChecker {

    private static final Logger logger = LoggerFactory.getLogger(PromptConsistencyChecker.class);

    /**
     * 工具名形态：全小写字母 + 至少一个下划线分词（{@code a_b}、{@code a_b_c}）。
     *
     * <p>刻意不含数字与连字符：本项目工具名规范是纯 snake_case，收窄形态能显著降低误报
     * （如英文连字符短语、版本号 {@code v1_2} 都不会被扫进来）。</p>
     */
    static final Pattern TOOL_NAME_SHAPE = Pattern.compile("[a-z]+(?:_[a-z]+)+");

    /** 模板变量占位语法：扫描前先剔除，避免把变量名当成工具名。 */
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\{\\{[^}]*}}");

    /** 证据行最大长度（WARN 日志与测试失败信息用，防单行超长刷屏）。 */
    private static final int MAX_EVIDENCE_CHARS = 160;

    /**
     * 形似工具名但确实不是工具名的合法词（逐条注明理由）。
     *
     * <p>⚠️ 新增前请确认：该词确非工具名，且与任何已注册工具名都不冲突
     *（{@code PromptConsistencyCheckerTest} 会断言）。用 LinkedHashMap 保证
     * 测试与日志输出顺序稳定。</p>
     */
    public static final Map<String, String> NON_TOOL_NAMES = new LinkedHashMap<>();

    static {
        // run_command 的参数名（terminal-tool.md：「长耗时命令请设置更大的 timeout_seconds」）
        NON_TOOL_NAMES.put("timeout_seconds", "run_command 的参数名，非工具名");
        // rag_search 返回结果的字段名（rag.md：「命中时引用 source_path 增强可信度」）
        NON_TOOL_NAMES.put("source_path", "知识库检索结果的字段名，非工具名");
        // 模板变量名（skills-index.md 的 {{skills_index}}）。扫描前已剔除占位符，
        // 此处仍登记以防用户覆盖文件写出不带花括号的裸变量名造成误报
        NON_TOOL_NAMES.put("skills_index", "提示词模板变量名，非工具名");
    }

    private PromptConsistencyChecker() {
    }

    /**
     * 一次违规：疑似工具名的词 + 它所在行的片段（定位用）。
     *
     * @param token        命中的候选词
     * @param evidenceLine 该词所在行（已截断），供 WARN 与测试失败信息使用
     */
    public record Violation(String token, String evidenceLine) {
    }

    /**
     * 校验一次装配结果（运行期入口的数据来源）。
     *
     * <p>只扫<b>静态创作段前缀</b>（{@link ComposedPrompt#authoredText()}）而非全文：
     * 记忆等动态尾段属用户数据，用户在自己的 MEMORY.md 里提到某个工具名完全正当，
     * 那不属于「我们写的指引与实现漂移」（本机真实 MEMORY.md 就含 rag_search，
     * 纳入扫描会每次启动误报）。</p>
     *
     * @param composed     装配结果
     * @param capabilities 注册实况
     * @return 违规列表（空 = 一致）
     */
    public static List<Violation> check(ComposedPrompt composed, CapabilityRegistry capabilities) {
        if (composed == null || capabilities == null) {
            return List.of();
        }
        return checkText(composed.authoredText(), knownNames(capabilities));
    }

    /**
     * 从注册实况导出「已知合法名字」集合 = 工具名 ∪ <b>工具集名</b>。
     *
     * <p>为何必须含工具集名（期 4 实测缺陷）：{@code capabilities.md} 由
     * {@code CapabilityRegistry.asSummary()} 渲染为 {@code - file_operations: grep_files, …}，
     * 而 {@code file_operations} 完全符合工具名形态（snake_case）却<b>不是工具名</b>。
     * 只拿工具名当已知集合 → 每次装配都误报。修法是把注册实况里的工具集名
     * 一并纳入，而<b>不是</b>往白名单塞 {@code file_operations}：两者的差别在于
     * 前者依旧能捕获拼错的工具集名（如 {@code file_operation}），
     * 后者会把工具集名永久豁免，自检对该类漂移直接失效。</p>
     */
    static Set<String> knownNames(CapabilityRegistry capabilities) {
        Set<String> known = new LinkedHashSet<>(capabilities.allToolNames());
        known.addAll(capabilities.toolsets());
        return known;
    }

    /**
     * 校验任意提示词文本（测试期对单个段文件调用的入口）。
     *
     * @param text               待扫文本；null/空白直接返回空列表
     * @param registeredToolNames 已注册工具名集合
     * @return 违规列表（按出现顺序，同一词重复出现只报首次）
     */
    public static List<Violation> checkText(String text, Set<String> registeredToolNames) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> known = registeredToolNames == null ? Set.of() : registeredToolNames;
        String scanTarget = TEMPLATE_PLACEHOLDER.matcher(text).replaceAll(" ");

        List<Violation> violations = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        Matcher matcher = TOOL_NAME_SHAPE.matcher(scanTarget);
        while (matcher.find()) {
            String token = matcher.group();
            if (known.contains(token) || NON_TOOL_NAMES.containsKey(token) || !reported.add(token)) {
                continue;
            }
            violations.add(new Violation(token, evidenceOf(scanTarget, matcher.start())));
        }
        return List.copyOf(violations);
    }

    /**
     * 运行期自检：命中只 WARN，<b>绝不抛异常</b>。
     *
     * <p>不阻断的理由与 {@code PromptLoader} 同一容错铁律——自检组件自身的判定缺陷
     * （例如白名单漏登记一个合法参数名）不该让用户用不了程序；WARN 已足够把线索
     * 交到排查者手上。</p>
     *
     * @return 违规列表（便于调用方决定是否额外提示；{@code Agent} 目前只用 WARN）
     */
    public static List<Violation> warnIfInconsistent(ComposedPrompt composed,
                                                     CapabilityRegistry capabilities) {
        List<Violation> violations = check(composed, capabilities);
        for (Violation v : violations) {
            logger.warn("提示词一致性自检：文本中出现疑似工具名 '{}' 但该工具未注册，"
                            + "模型可能据此发起无效调用。若非工具名请加入 NON_TOOL_NAMES 白名单；"
                            + "若是拼写错误或 requires 判据不当请修正提示词分段。证据行：{}",
                    v.token(), v.evidenceLine());
        }
        return violations;
    }

    /** 取 token 所在行并截断，作为定位证据。 */
    private static String evidenceOf(String text, int tokenStart) {
        int lineStart = text.lastIndexOf('\n', tokenStart) + 1;
        int lineEnd = text.indexOf('\n', tokenStart);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        String line = text.substring(lineStart, lineEnd).strip();
        if (line.length() > MAX_EVIDENCE_CHARS) {
            line = line.substring(0, MAX_EVIDENCE_CHARS) + "…";
        }
        return line;
    }
}
