package com.cyk.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <h1>规则集加载（JH-SPEC-001 P0-2 R3）</h1>
 *
 * <p>验收要点：内置规则成功加载且非空（空规则集 = 全放行，是禁止的启动状态）；
 * 用户覆盖按 id 合并；<b>用户文件损坏时回退内置默认并且绝不放宽防护</b>。</p>
 */
class SecurityRuleSetTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("内置规则加载成功、非空、含关键规则")
    void builtinRulesLoad() {
        SecurityRuleSet set = SecurityRuleSet.loadDefaultOnly();

        assertThat(set.size()).as("内置规则必须非空，否则等于全放行").isGreaterThan(20);
        List<String> ids = set.allRules().stream().map(CommandRule::id).toList();
        assertThat(ids)
                .contains("blocked-format-disk")   // BLOCKED 磁盘
                .contains("dangerous-git-force-push")
                .contains("readonly-git-query")
                .contains("readonly-system-info");
    }

    @Test
    @DisplayName("BLOCKED 级规则至少覆盖磁盘/关机/删根")
    void blockedRulesCoverCatastrophes() {
        SecurityRuleSet set = SecurityRuleSet.loadDefaultOnly();
        long blockedCount = set.allRules().stream()
                .filter(r -> r.level() == CommandLevel.BLOCKED).count();
        assertThat(blockedCount).as("BLOCKED 档至少要覆盖多类灾难").isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("用户覆盖：同 id 替换、新 id 追加")
    void userOverlayMergesById() throws Exception {
        List<CommandRule> defaults = SecurityRuleSet.loadDefaultOnly().allRules();
        Path userFile = tmp.resolve("rules.yaml");
        // 覆盖内置 blocked-format-disk 的 reason，并追加一条自定义规则
        Files.writeString(userFile, """
                version: 1
                rules:
                  - id: blocked-format-disk
                    level: BLOCKED
                    reason: 用户自定义的格式化拦截理由
                    match: { exe: ["format"] }
                  - id: custom-team-rule
                    level: DANGEROUS
                    reason: 团队内部禁止 deploy 到生产
                    match: { exe: ["deploy-prod"] }
                """, StandardCharsets.UTF_8);

        List<CommandRule> merged = SecurityRuleSet.mergeWithUserOverlay(defaults, userFile);

        // 同 id 被替换：reason 变成用户自定义
        CommandRule format = merged.stream()
                .filter(r -> r.id().equals("blocked-format-disk")).findFirst().orElseThrow();
        assertThat(format.reason()).isEqualTo("用户自定义的格式化拦截理由");
        // 新 id 被追加
        assertThat(merged.stream().anyMatch(r -> r.id().equals("custom-team-rule"))).isTrue();
        // 其余默认规则仍在（未因覆盖而丢失）
        assertThat(merged.stream().anyMatch(r -> r.id().equals("readonly-git-query"))).isTrue();
    }

    @Test
    @DisplayName("用户文件损坏：回退内置默认，不抛异常、不放宽（R3 安全底线）")
    void corruptUserFileFallsBackToDefault() throws Exception {
        List<CommandRule> defaults = SecurityRuleSet.loadDefaultOnly().allRules();
        Path badFile = tmp.resolve("bad-rules.yaml");
        Files.writeString(badFile, "this: is: not: valid: yaml: [unclosed", StandardCharsets.UTF_8);

        List<CommandRule> result = SecurityRuleSet.mergeWithUserOverlay(defaults, badFile);

        assertThat(result).as("损坏用户文件必须回退到内置默认，绝不放宽").isEqualTo(defaults);
    }

    @Test
    @DisplayName("用户文件不存在：直接用默认，不报错")
    void missingUserFileUsesDefault() {
        List<CommandRule> defaults = SecurityRuleSet.loadDefaultOnly().allRules();
        Path absent = tmp.resolve("does-not-exist.yaml");

        assertThatCode(() -> SecurityRuleSet.mergeWithUserOverlay(defaults, absent))
                .doesNotThrowAnyException();
        assertThat(SecurityRuleSet.mergeWithUserOverlay(defaults, absent)).isEqualTo(defaults);
    }

    @Test
    @DisplayName("level 非法的规则被跳过，不影响其它规则加载")
    void invalidLevelRuleSkipped() throws Exception {
        List<CommandRule> defaults = SecurityRuleSet.loadDefaultOnly().allRules();
        Path userFile = tmp.resolve("rules.yaml");
        Files.writeString(userFile, """
                version: 1
                rules:
                  - id: bad-level-rule
                    level: SUPER_DUPER_DANGEROUS
                    reason: 非法级别应被跳过
                    match: { exe: ["whatever"] }
                  - id: good-rule
                    level: WRITE
                    reason: 合法规则应加载
                    match: { exe: ["whatever2"] }
                """, StandardCharsets.UTF_8);

        List<CommandRule> merged = SecurityRuleSet.mergeWithUserOverlay(defaults, userFile);

        assertThat(merged.stream().anyMatch(r -> r.id().equals("bad-level-rule")))
                .as("非法 level 的规则必须被跳过").isFalse();
        assertThat(merged.stream().anyMatch(r -> r.id().equals("good-rule"))).isTrue();
    }

    @Test
    @DisplayName("平台判定：any/空 适用所有平台")
    void platformApplicability() {
        CommandRule anyPlatform = new CommandRule("r", List.of(), List.of("x"), List.of(),
                List.of(), List.of(), List.of(), CommandLevel.WRITE, "");
        assertThat(anyPlatform.appliesToPlatform("windows")).isTrue();
        assertThat(anyPlatform.appliesToPlatform("unix")).isTrue();

        CommandRule winOnly = new CommandRule("r", List.of("windows"), List.of("del"), List.of(),
                List.of(), List.of(), List.of(), CommandLevel.BLOCKED, "");
        assertThat(winOnly.appliesToPlatform("windows")).isTrue();
        assertThat(winOnly.appliesToPlatform("unix")).isFalse();
    }
}
