package com.cyk.security;

import com.cyk.constant.Constants;
import com.cyk.config.HermesConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <h1>命令策略规则集（JH-SPEC-001 P0-2 R3）</h1>
 *
 * <p>规则外置到 YAML，加载顺序：</p>
 * <ol>
 *   <li><b>内置默认</b>：classpath 的 {@code security/command-rules.yaml}（随 jar 分发）；</li>
 *   <li><b>用户覆盖</b>：{@code ~/.jhermes/security/rules.yaml}（可选），
 *       与默认表按 {@code id} 合并——同 id 用用户版本替换，其余追加。</li>
 * </ol>
 *
 * <h2>失败即回退，绝不放宽（R3 硬约束）</h2>
 * <p>用户规则文件损坏/非法时：记 WARN，仅用内置默认表。这是安全设计的关键——
 * 一份写坏的用户配置<b>绝不能</b>让拦截退化成「无规则=全放行」。同理，内置表本身
 * 加载失败会抛出 {@link IllegalStateException} 阻断启动，而不是带着空规则集运行。</p>
 *
 * <p>用户规则文件被纳入 {@link com.cyk.util.SensitivePathGuard} 写保护（R11），
 * 防止模型改写自己的规则来给自己开权限。</p>
 */
public final class SecurityRuleSet {

    private static final Logger logger = LoggerFactory.getLogger(SecurityRuleSet.class);

    /** classpath 内置规则资源路径。 */
    private static final String DEFAULT_RULES_RESOURCE = "/security/command-rules.yaml";

    /** 用户覆盖规则相对 HERMES_HOME 的路径。 */
    static final String USER_RULES_RELATIVE = "security/rules.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final List<CommandRule> rules;
    private final String currentPlatform;

    private SecurityRuleSet(List<CommandRule> rules) {
        this.rules = List.copyOf(rules);
        this.currentPlatform = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "windows" : "unix";
    }

    /**
     * 加载默认 + 用户覆盖规则集（生产入口）。
     *
     * @param config 用于定位 HERMES_HOME；null 时只加载内置默认
     */
    public static SecurityRuleSet load(HermesConfig config) {
        List<CommandRule> defaults = loadDefault();
        if (config == null) {
            return new SecurityRuleSet(defaults);
        }
        Path userFile = Constants.getHermesHome().resolve(USER_RULES_RELATIVE);
        List<CommandRule> merged = mergeWithUserOverlay(defaults, userFile);
        return new SecurityRuleSet(merged);
    }

    /**
     * 仅加载内置默认（测试与用户覆盖不可用时使用）。
     */
    public static SecurityRuleSet loadDefaultOnly() {
        return new SecurityRuleSet(loadDefault());
    }

    /**
     * 从内置资源加载规则。失败即抛异常——空规则集等于「全放行」，绝不允许。
     */
    private static List<CommandRule> loadDefault() {
        try (InputStream in = SecurityRuleSet.class.getResourceAsStream(DEFAULT_RULES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("内置命令规则资源缺失: " + DEFAULT_RULES_RESOURCE);
            }
            List<CommandRule> rules = parse(in, "classpath:" + DEFAULT_RULES_RESOURCE);
            if (rules.isEmpty()) {
                throw new IllegalStateException("内置命令规则为空，拒绝以无防护状态启动");
            }
            logger.debug("已加载内置命令规则 {} 条", rules.size());
            return rules;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取内置命令规则失败", e);
        }
    }

    /**
     * 合并用户覆盖规则。用户文件不存在=只用默认；损坏=告警并用默认（不放宽）。
     *
     * @return 供单测断言：是否成功加载了用户覆盖
     */
    static List<CommandRule> mergeWithUserOverlay(List<CommandRule> defaults, Path userFile) {
        if (userFile == null || !Files.isRegularFile(userFile)) {
            return defaults;
        }
        try (InputStream in = Files.newInputStream(userFile)) {
            List<CommandRule> userRules = parse(in, userFile.toString());
            logger.info("已加载用户命令规则覆盖 {} 条: {}", userRules.size(), userFile);
            // 用 LinkedHashMap 按 id 合并：用户规则覆盖同 id 的默认规则，其余追加
            Map<String, CommandRule> byId = new LinkedHashMap<>();
            for (CommandRule r : defaults) {
                byId.put(r.id(), r);
            }
            for (CommandRule r : userRules) {
                byId.put(r.id(), r);
            }
            return new ArrayList<>(byId.values());
        } catch (Exception e) {
            // 关键安全设计：用户配置写坏绝不能导致「无规则=全放行」
            logger.warn("用户命令规则加载失败，已回退内置默认表（不放宽防护）: {} -> {}",
                    userFile, e.toString());
            return defaults;
        }
    }

    private static List<CommandRule> parse(InputStream in, String origin) throws java.io.IOException {
        RulesFile file = YAML.readValue(in, RulesFile.class);
        List<CommandRule> rules = new ArrayList<>();
        if (file != null && file.rules != null) {
            for (RuleYaml ry : file.rules) {
                CommandRule rule = toRule(ry, origin);
                if (rule != null) {
                    rules.add(rule);
                }
            }
        }
        return rules;
    }

    private static CommandRule toRule(RuleYaml ry, String origin) {
        if (ry == null || ry.id == null || ry.id.isBlank()) {
            logger.warn("忽略无 id 的规则（来源 {}）", origin);
            return null;
        }
        CommandLevel level = CommandLevel.parse(ry.level);
        if (level == null) {
            // 单条规则 level 非法 → 跳过该条（无法应用的规则强行猜级别反而危险）。
            // 「整表解析失败 → 回退默认表」的放宽风险由 mergeWithUserOverlay 统一兜底。
            logger.warn("规则 {} 的 level 非法（'{}'），已忽略该条", ry.id, ry.level);
            return null;
        }
        MatchYaml m = ry.match != null ? ry.match : new MatchYaml();
        return new CommandRule(
                ry.id.trim(),
                emptyIfNull(ry.platform),
                emptyIfNull(m.exe),
                emptyIfNull(m.sub),
                emptyIfNull(m.flagsAny),
                emptyIfNull(m.flagsNone),
                emptyIfNull(m.argsAny),
                level,
                ry.reason != null ? ry.reason : "");
    }

    private static List<String> emptyIfNull(List<String> list) {
        return list != null ? list : List.of();
    }

    /** @return 当前平台（windows / unix），供规则适用性判定与审计 */
    public String currentPlatform() {
        return currentPlatform;
    }

    /**
     * 返回与给定段匹配的所有规则。
     *
     * <p>调用方（{@link CommandPolicy}）取其中最严一档作为该段的级别。</p>
     */
    public List<CommandRule> matching(CommandSegment seg) {
        List<CommandRule> matched = new ArrayList<>();
        for (CommandRule rule : rules) {
            if (rule.matches(seg, currentPlatform)) {
                matched.add(rule);
            }
        }
        return matched;
    }

    /** @return 全部规则（含默认与覆盖），供 {@code security check} 自检与测试 */
    public List<CommandRule> allRules() {
        return rules;
    }

    /** 规则条数。 */
    public int size() {
        return rules.size();
    }

    // =========================================================================
    // YAML DTO（映射 command-rules.yaml 结构，见附录 B.1）
    // =========================================================================

    /** YAML 顶层：version + rules。 */
    public static final class RulesFile {
        @JsonProperty("version")
        public Integer version;
        @JsonProperty("rules")
        public List<RuleYaml> rules;
    }

    /** 单条规则的 YAML 结构。 */
    public static final class RuleYaml {
        @JsonProperty("id")
        public String id;
        @JsonProperty("platform")
        public List<String> platform;
        @JsonProperty("level")
        public String level;
        @JsonProperty("reason")
        public String reason;
        @JsonProperty("match")
        public MatchYaml match;
    }

    /** 规则匹配条件。字段名对齐 YAML 的 snake_case。 */
    public static final class MatchYaml {
        @JsonProperty("exe")
        public List<String> exe;
        @JsonProperty("sub")
        public List<String> sub;
        @JsonProperty("flags_any")
        public List<String> flagsAny;
        @JsonProperty("flags_none")
        public List<String> flagsNone;
        @JsonProperty("args_any")
        public List<String> argsAny;
    }
}
