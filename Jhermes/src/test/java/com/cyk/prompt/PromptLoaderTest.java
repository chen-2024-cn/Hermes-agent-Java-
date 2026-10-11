package com.cyk.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词外置的期 2 验证（JH-SPEC-001 P0-3 R1）：分段文件加载、manifest 解析、
 * 用户覆盖与容错回退。
 *
 * <p>三类关键断言：</p>
 * <ol>
 *   <li><b>迁移保真</b>：逐字节锚点测试已于期 2 验证 md 与旧 Constants 字符串相等并锁档；
 *       期 3 删除 Constants 后退役为关键内容断言，继续防「段文本被意外改动」；</li>
 *   <li><b>条件工具名零泄漏</b>：恒注入段（identity/execution/terminal-output）
 *       不得出现任何按 requires 条件注入的工具名（rag_ 前缀、run_command、web_search 等），
 *       否则 RAG 或终端关闭时 AC2「装配结果不含未注册工具引用」必破；</li>
 *   <li><b>容错铁律</b>：覆盖文件损坏、manifest 写坏、路径穿越、文件缺失——
 *       任何输入都不抛异常、不阻断加载。</li>
 * </ol>
 */
class PromptLoaderTest {

    /** manifest 声明的全部段 id（顺序即前缀顺序，P0-4 R8）。 */
    private static final List<String> EXPECTED_IDS = List.of(
            "identity", "working-dir", "execution", "terminal-output", "memory",
            "terminal-tool", "web-search", "rag", "skills", "capabilities", "skills-index");

    private static List<PromptSegment> load() {
        return new PromptLoader(null).load(); // 纯 classpath：不受本机 ~/.jhermes/prompts 定制影响
    }

    private static Map<String, PromptSegment> byId() {
        return load().stream().collect(Collectors.toMap(PromptSegment::id, Function.identity()));
    }

    // =========================================================================
    // 加载与 manifest 解析
    // =========================================================================

    @Test
    @DisplayName("11 个分段按 manifest 声明顺序全部加载，正文非空")
    void shouldLoadAllSegmentsInManifestOrder() {
        List<PromptSegment> segments = load();

        assertThat(segments).extracting(PromptSegment::id).containsExactlyElementsOf(EXPECTED_IDS);
        assertThat(segments).allSatisfy(s -> {
            assertThat(s.content()).as("段 %s 正文非空", s.id()).isNotBlank();
            assertThat(s.file()).endsWith(".md");
            assertThat(s.charCount()).isEqualTo(s.content().length());
        });
    }

    @Test
    @DisplayName("requires 解析：恒注入段与条件段的判据逐项正确")
    void requiresShouldBeParsedFromManifest() {
        Map<String, PromptSegment> segments = byId();

        // 恒注入段（manifest 未写 requires）
        assertThat(segments.get("identity").requires().isAlways()).isTrue();
        for (String id : List.of("working-dir", "execution", "terminal-output", "capabilities")) {
            assertThat(segments.get(id).requires().isAlways())
                    .as("段 %s 应恒注入", id).isTrue();
        }
        // 工具集判据
        assertThat(segments.get("memory").requires().toolsets()).containsExactly("memory");
        assertThat(segments.get("web-search").requires().toolsets()).containsExactly("web");
        assertThat(segments.get("rag").requires().toolsets()).containsExactly("rag");
        // 工具名判据（比工具集更精确：run_command 可被单独禁用）
        assertThat(segments.get("terminal-tool").requires().tools()).containsExactly("run_command");
        assertThat(segments.get("skills").requires().tools()).containsExactly("skill_manage");
        // 复合判据：技能索引还要求本机技能数 > 0
        PromptRequirements indexRequires = segments.get("skills-index").requires();
        assertThat(indexRequires.tools()).containsExactly("skill_manage");
        assertThat(indexRequires.requireSkills()).isTrue();
    }

    @Test
    @DisplayName("缓存语义：重复 load 返回同一不可变实例")
    void repeatedLoadShouldReturnCachedInstance() {
        PromptLoader loader = new PromptLoader(null);

        List<PromptSegment> first = loader.load();
        List<PromptSegment> second = loader.load();

        assertThat(second).isSameAs(first);
    }

    // =========================================================================
    // 迁移保真锚点（已于期 2 完成使命：当时逐字节验证了 md 与 Constants 旧串相等；
    // 期 3 删除 Constants 后锚点退役，改由下方关键内容断言把守段文本不被意外改动）
    // =========================================================================

    @Test
    @DisplayName("原样迁移段保留关键内容（identity/memory/terminal-tool）")
    void verbatimSegmentsShouldKeepKeyContent() {
        Map<String, PromptSegment> segments = byId();

        assertThat(segments.get("identity").content())
                .startsWith("你是Jhermes智能体")
                .contains("不冗余啰嗦");
        assertThat(segments.get("memory").content())
                .startsWith("你拥有跨会话持久记忆功能")
                .contains("精简凝练");
        assertThat(segments.get("terminal-tool").content())
                .startsWith("终端命令执行（run_command）")
                .contains("【确认由宿主执行")
                .contains("【被拒绝时如实转告并停止重试】")
                .contains("timeout_seconds");
    }

    @Test
    @DisplayName("rag 段保留三段式结构且不引用 run_command（期 2 已验证的唯一有意偏差）")
    void ragSegmentShouldKeepStructureWithoutRunCommandReference() {
        // 旧 RAG_GUIDANCE 写死「严禁用 run_command 搜索磁盘」——terminal 关闭而
        // rag 开启时该引用即指向未注册工具（AC1 违例），已在迁移时改为泛称
        // 「严禁用终端命令搜索磁盘」（替换式等值锚点测试已在期 2 锁档验证）。
        String rag = byId().get("rag").content();

        assertThat(rag).contains("【先扫清单原则（最重要）】")
                .contains("【工具选择】")
                .contains("【安全红线】")
                .contains("严禁用终端命令搜索磁盘")
                .contains("rag_list", "rag_search", "rag_index", "rag_delete");
        assertThat(rag).doesNotContain("run_command");
    }

    // =========================================================================
    // 内容纪律：条件工具名零泄漏 + 重写段方向正确
    // =========================================================================

    @Test
    @DisplayName("恒注入段不得引用任何条件注入的工具名（AC2 前置纪律）")
    void alwaysOnSegmentsMustNotReferenceConditionalToolNames() {
        // 恒注入段无论能力如何都在提示词里，故其中<b>任何</b> snake_case 工具名都是隐患：
        // 该工具被禁用（disabled_toolsets）或初始化失败（RAG 连不上库）时，
        // AC2「装配结果不含未注册工具引用」即被打破。
        // 判据用一致性自检器而非人工枚举黑名单：传空工具名集合，
        // 凡形似工具名且不在白名单者一律违规——新增段位自动受同一约束，无需维护清单。
        for (String id : List.of("identity", "working-dir", "execution",
                "terminal-output", "capabilities")) {
            String content = byId().get(id).content();
            assertThat(PromptConsistencyChecker.checkText(content, java.util.Set.of()))
                    .as("恒注入段 %s 不得出现任何工具名形态的词", id)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("skills 段重写方向：教模型用 skill_manage，不再误导用记忆工具存技能")
    void skillsSegmentShouldPointToSkillManageNotMemoryTools() {
        String content = byId().get("skills").content();

        assertThat(content).contains("skill_manage");
        assertThat(content).contains("save").contains("get");
        // 旧 SKILLS_GUIDANCE 的错误表述（A19 相关）不得残留
        assertThat(content).doesNotContain("记忆保存工具").doesNotContain("记忆替换工具");
    }

    @Test
    @DisplayName("合并去重后的 execution 段保留两侧的关键行为约束")
    void executionSegmentShouldKeepKeyRulesFromBothSources() {
        String content = byId().get("execution").content();

        // 来自 TOOL_USE_ENFORCEMENT 的核心：每轮要么工具调用要么交付结果
        assertThat(content).contains("两种之一").contains("立即").contains("不被允许");
        // 来自 EXECUTION_DISCIPLINE 的核心：熔断、强制工具、直接执行、核验、禁猜测
        assertThat(content).contains("2 次").contains("sha256sum").contains("date")
                .contains("禁止猜测编造").contains("明确标注").contains("核验");
    }

    @Test
    @DisplayName("静态段字符预算（T1 ≤ 基线 80% 的分段级防线）")
    void staticSegmentsShouldStayWithinCharBudgets() {
        Map<String, PromptSegment> segments = byId();

        // 报备测算的目标值加适度余量：超限即视为文案悄悄膨胀，期 6 预算守卫前就地拦截
        assertThat(segments.get("identity").charCount()).isLessThanOrEqualTo(200);
        assertThat(segments.get("execution").charCount())
                .as("合并去重目标 ~900（旧两段合计 1556）").isLessThanOrEqualTo(1_050);
        assertThat(segments.get("terminal-output").charCount())
                .as("合并目标 ~300（旧两段合计 511）").isLessThanOrEqualTo(400);
        assertThat(segments.get("web-search").charCount())
                .as("精简目标 ~170（旧 239）").isLessThanOrEqualTo(250);
        assertThat(segments.get("skills").charCount()).isLessThanOrEqualTo(250);
        assertThat(segments.get("capabilities").charCount()).isLessThanOrEqualTo(300);
        assertThat(segments.get("skills-index").charCount()).isLessThanOrEqualTo(150);
    }

    // =========================================================================
    // 模板变量清单（期 3 composer 的替换契约）
    // =========================================================================

    @Test
    @DisplayName("模板变量只出现在 3 个模板段，且清单与 composer 约定一致")
    void templateVariablesShouldBeExactlyAsContracted() {
        Map<String, PromptSegment> segments = byId();

        assertThat(segments.get("working-dir").templateVariables())
                .containsExactly("cwd", "os", "home", "entries");
        assertThat(segments.get("capabilities").templateVariables())
                .containsExactly("capabilities");
        assertThat(segments.get("skills-index").templateVariables())
                .containsExactly("skills_index");

        // 其余段全部无模板变量——出现即为「误把示例写成了占位符」
        for (PromptSegment segment : segments.values()) {
            if (!List.of("working-dir", "capabilities", "skills-index").contains(segment.id())) {
                assertThat(segment.templateVariables())
                        .as("段 %s 不应含模板变量", segment.id()).isEmpty();
            }
        }
    }

    // =========================================================================
    // 用户覆盖（D-3）与容错回退
    // =========================================================================

    @Test
    @DisplayName("用户覆盖目录中的同名段文件优先生效，其余段仍走内置版")
    void userOverrideSegmentShouldWinOverClasspath(@TempDir Path overrideDir) throws IOException {
        Files.writeString(overrideDir.resolve("identity.md"), "自定义身份提示词。",
                StandardCharsets.UTF_8);

        List<PromptSegment> segments = new PromptLoader(overrideDir).load();

        Map<String, PromptSegment> byId = segments.stream()
                .collect(Collectors.toMap(PromptSegment::id, Function.identity()));
        assertThat(byId).containsKeys(EXPECTED_IDS.toArray(String[]::new));
        assertThat(byId.get("identity").content()).isEqualTo("自定义身份提示词。");
        // 未被覆盖的段仍与 classpath 内置版一致（自引用比较，不依赖已退役的 Constants）
        String builtinMemory = byIdBuiltIn("memory").content();
        assertThat(byId.get("memory").content()).isEqualTo(builtinMemory);
    }

    /** 加载 classpath 内置版某段（无覆盖），供覆盖类测试做基准比较。 */
    private static PromptSegment byIdBuiltIn(String id) {
        return new PromptLoader(null).load().stream()
                .filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("BOM 与首尾空白在加载时规范化（Windows 编辑器常见）")
    void userOverrideShouldStripBomAndSurroundingWhitespace(@TempDir Path overrideDir) throws IOException {
        Files.writeString(overrideDir.resolve("identity.md"),
                "\uFEFF\n  带 BOM 的覆盖内容  \n\n", StandardCharsets.UTF_8);

        PromptSegment segment = new PromptLoader(overrideDir).load().stream()
                .filter(s -> s.id().equals("identity")).findFirst().orElseThrow();

        assertThat(segment.content()).isEqualTo("带 BOM 的覆盖内容");
    }

    @Test
    @DisplayName("用户覆盖的 manifest 写坏 YAML：回退内置清单，11 段照常加载")
    void corruptUserManifestShouldFallBackToClasspath(@TempDir Path overrideDir) throws IOException {
        Files.writeString(overrideDir.resolve("manifest.yaml"),
                "segments: [[[ 这不是合法YAML :::", StandardCharsets.UTF_8);

        List<PromptSegment> segments = new PromptLoader(overrideDir).load();

        assertThat(segments).extracting(PromptSegment::id).containsExactlyElementsOf(EXPECTED_IDS);
    }

    @Test
    @DisplayName("manifest 条目引用的文件缺失：仅跳过该段，其余照常")
    void missingSegmentFileShouldBeSkippedWithOthersIntact(@TempDir Path overrideDir) throws IOException {
        Files.writeString(overrideDir.resolve("manifest.yaml"), """
                version: 1
                segments:
                  - id: identity
                    file: identity.md
                  - id: ghost
                    file: not-exist.md
                """, StandardCharsets.UTF_8);

        List<PromptSegment> segments = new PromptLoader(overrideDir).load();

        assertThat(segments).extracting(PromptSegment::id).containsExactly("identity");
    }

    @Test
    @DisplayName("manifest 的 file 字段路径穿越被封堵：只取文件名，读不到目录外内容")
    void pathTraversalInManifestFileFieldShouldBeContained(@TempDir Path root) throws IOException {
        Path overrideDir = root.resolve("prompts");
        Files.createDirectories(overrideDir);
        // 覆盖目录之外放一个「不该被读到」的文件
        Files.writeString(root.resolve("evil.md"), "目录外的秘密内容", StandardCharsets.UTF_8);
        Files.writeString(overrideDir.resolve("manifest.yaml"), """
                version: 1
                segments:
                  - id: escape
                    file: ../evil.md
                """, StandardCharsets.UTF_8);

        List<PromptSegment> segments = new PromptLoader(overrideDir).load();

        // ../evil.md 被归约为文件名 evil.md，在覆盖目录与 classpath 均不存在 → 跳过，
        // 目录外内容绝无可能进入提示词
        assertThat(segments).isEmpty();
    }

    @Test
    @DisplayName("requires 里的空白项被滤除；id 重复仅首个生效——畸形输入不阻断加载")
    void malformedManifestEntriesShouldDegradeGracefully(@TempDir Path overrideDir) throws IOException {
        Files.writeString(overrideDir.resolve("manifest.yaml"), """
                version: 1
                segments:
                  - id: identity
                    file: identity.md
                    requires:
                      tools: ["run_command", "  ", ""]
                  - id: identity
                    file: memory.md
                  - id: ""
                    file: rag.md
                  - file: web-search.md
                """, StandardCharsets.UTF_8);

        List<PromptSegment> segments = new PromptLoader(overrideDir).load();

        assertThat(segments).extracting(PromptSegment::id).containsExactly("identity");
        // 空白项滤除后 tools 仅剩有效值；file 字段与内容按首条 identity.md 生效
        assertThat(segments.get(0).requires().tools()).containsExactly("run_command");
        assertThat(segments.get(0).content()).isEqualTo(byIdBuiltIn("identity").content());
    }

    @Test
    @DisplayName("normalize 单元语义：BOM 剥离 + 行尾归一 LF + strip")
    void normalizeShouldStripBomAndTrim() {
        assertThat(PromptLoader.normalize("\uFEFF  x \n ")).isEqualTo("x");
        assertThat(PromptLoader.normalize("无BOM\n")).isEqualTo("无BOM");
        // Windows 链路（工具写盘/编辑器保存/git autocrlf）产出的 CRLF 必须归一为 LF：
        // \r 混进提示词会污染 token 计数并破坏 P0-4 R8 的前缀字节稳定性
        assertThat(PromptLoader.normalize("行一\r\n行二\r\n")).isEqualTo("行一\n行二");
        assertThat(PromptLoader.normalize("孤立CR\r续行")).isEqualTo("孤立CR\n续行");
        // 归一后与 Constants 的 LF 拼接字符串可直接等值比较（期 3 替换的前提）
        assertThat(PromptLoader.normalize("a\r\nb")).isEqualTo("a\nb");
    }
}
