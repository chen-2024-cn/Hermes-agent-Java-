package com.cyk.command;

import com.cyk.config.HermesConfig;
import com.cyk.http.ModelClient;
import com.cyk.security.SecurityContext;
import com.cyk.tool.RagTool;
import com.cyk.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具定义（tools JSON）体积的预算守卫（JH-SPEC-001 P0-4 R5 / D-1）。
 *
 * <h2>这个测试守的是什么</h2>
 * <p>D-1 的目标是"tools JSON 不超过瘦身前基线 6,952 字符的 55%（3,823）"。实测发现只改说明文字
 * 达不到：把全部 description 的文字清空（但保留 description 这个键）后，体积仍有 4,224 字符，
 * 已经超过门禁 3,823。所以本期的结论是<b>接受现状（约 6,077 字符），不再继续压文字</b>。</p>
 *
 * <p>补充说明：4,224 里包含 56 个 {@code "description":"",} 空壳（16 个工具 + 40 个参数，每个 17 字符，
 * 共约 952）。如果连这些键也整个删掉，体积约 3,272，数学上低于门禁，但那等于所有工具和参数都没有任何说明，
 * 模型无从得知怎么用，不是可接受的方案。这一段是算术推断，没有用代码单独验证。</p>
 *
 * <h2>棘轮：只许降、不许涨</h2>
 * <p>结论落成"棘轮"：体积只许降、不许涨。以后谁给某个工具加了一大段说明，构建会在这里变红，
 * 提醒他"这些字符每一轮请求都要重复付费"。要有意放宽上限时，改下面的 {@link #CEILING_CHARS}
 * 并在提交说明里写明理由，这样每一次放宽都有人看见。</p>
 *
 * <h2>为什么只统计 16 个内置工具</h2>
 * <p>技能目录里的脚本工具会被动态注册成 {@code <技能名>__<工具名>}，数量因机器而异。
 * 守卫只看固定的内置工具，才能在任何开发者机器和 CI 上得到同样的结果。</p>
 */
class ToolSchemaBudgetTest {

    /** 16 个内置工具。新增或改名工具时，这里和下面的上限都要同步考虑。 */
    private static final Set<String> BUILT_IN = Set.of(
            "fetch_page", "grep_files", "memory_delete", "memory_get", "memory_replace", "memory_save",
            "rag_delete", "rag_index", "rag_list", "rag_search", "read_file", "run_command",
            "search_files", "skill_manage", "web_search", "write_file");

    /** 瘦身前的基线（Phase 0 实测，见 HANDOFF 与 baseline-report）。 */
    private static final int BASELINE_CHARS = 6_952;

    /** D-1 双口径里的硬门禁：基线的 55%。本期实测达不到，保留在这里作为"目标是什么"的记录。 */
    private static final int GATE_CHARS = (int) (BASELINE_CHARS * 0.55);

    /**
     * 用户批准增加 run_command/write_file 的目的和预期结果共4个字段。
     * 实测总长 6,077 -> 6,291 (+214)，结构下限 4,224 -> 4,420 (+196)。
     * 仅给这项功能调整预算；剔除新增字段后的预算仍须满足旧上限。
     */
    private static final int CEILING_CHARS = 6_300;
    private static final int FLOOR_CEILING_CHARS = 4_450;
    private static final int PRE_APPROVAL_CEILING_CHARS = 6_100;
    private static final int PRE_APPROVAL_FLOOR_CEILING_CHARS = 4_300;

    private static List<Map<String, Object>> definitions;
    private static ObjectMapper mapper;
    private static ArrayNode toolsArray;

    @BeforeAll
    static void registerBuiltInTools() {
        // 显式启用固定工具集，仅检查说明书；不连接数据库或写入审计。
        HermesConfig config = new HermesConfig(null, new HashMap<>(Map.of(
                "tools", Map.of("terminal_enabled", true, "disabled_toolsets", List.of()),
                "rag", Map.of("enabled", false),
                "security", Map.of("audit_enabled", false))));
        ToolRegistry.initialize(config);
        ToolRegistry registry = ToolRegistry.getInstance();
        RagTool.registerSchemasForInspection(registry);

        Set<String> builtIn = registry.getAllToolName().stream()
                .filter(BUILT_IN::contains)
                .collect(Collectors.toSet());
        definitions = registry.getDefinitions(builtIn);
        mapper = ModelClient.newStableObjectMapper();
        toolsArray = ModelClient.buildToolsArray(mapper, definitions);
    }

    @AfterAll
    static void resetGlobalState() {
        // ToolRegistry 和 SecurityContext 都是全局单例，用完必须复位，免得影响其他测试类
        ToolRegistry.clearForTest();
        SecurityContext.resetForTest();
    }

    private static int toolsJsonChars() throws Exception {
        return mapper.writeValueAsString(toolsArray).length();
    }

    @Test
    @DisplayName("16 个内置工具都已注册（工具集合变了就要重新评估预算）")
    void allBuiltInToolsAreRegistered() {
        Set<String> actual = definitions.stream()
                .map(d -> String.valueOf(((Map<?, ?>) d.get("function")).get("name")))
                .collect(Collectors.toSet());

        assertThat(actual).containsExactlyInAnyOrderElementsOf(BUILT_IN);
    }

    @Test
    @DisplayName("棘轮：tools JSON 不超过上限，且比瘦身前基线小")
    void toolsJsonStaysWithinCeiling() throws Exception {
        int chars = toolsJsonChars();

        assertThat(chars)
                .as("tools JSON 现在是 %d 字符。上限 %d、瘦身前基线 %d。这些字符每一轮请求都要重复发送并计费；"
                                + "如果是有意增加，请同时修改本测试的 CEILING_CHARS 并在提交说明里写明理由",
                        chars, CEILING_CHARS, BASELINE_CHARS)
                .isLessThanOrEqualTo(CEILING_CHARS)
                .isLessThan(BASELINE_CHARS);
    }

    @Test
    @DisplayName("结构下限棘轮：全部 description 文字清空后的体积不得继续变大")
    void structuralFloorStaysWithinCeiling() throws Exception {
        int floor = DebugToolsJsonCommand.structuralFloorChars(mapper, toolsArray);

        assertThat(floor)
                .as("结构下限现在是 %d 字符，上限 %d。它由参数个数、键名、类型、枚举和必填列表决定，"
                                + "与说明文字无关；给工具新增参数会让它变大，请确认这些参数是真的需要",
                        floor, FLOOR_CEILING_CHARS)
                .isLessThanOrEqualTo(FLOOR_CEILING_CHARS);
    }

    @Test
    @DisplayName("许可说明仅新增4个可选字段，其余定义仍满足原预算")
    void approvalFieldsHaveAnExplicitBudgetWithoutRelaxingOtherTools() throws Exception {
        ArrayNode previousShape = toolsArray.deepCopy();
        int metadataFields = 0;
        for (var tool : previousShape) {
            var function = tool.path("function");
            String name = function.path("name").asText();
            if (name.equals("run_command") || name.equals("write_file")) {
                var parameters = function.path("parameters");
                var properties = (com.fasterxml.jackson.databind.node.ObjectNode) parameters.path("properties");
                assertThat(properties.path("purpose").path("type").asText()).isEqualTo("string");
                assertThat(properties.path("expected_result").path("type").asText()).isEqualTo("string");
                assertThat(parameters.path("required").toString()).doesNotContain("purpose", "expected_result");
                properties.remove("purpose");
                properties.remove("expected_result");
                metadataFields += 2;
            } else {
                assertThat(function.path("parameters").path("properties").has("purpose")).isFalse();
                assertThat(function.path("parameters").path("properties").has("expected_result")).isFalse();
            }
        }
        assertThat(metadataFields).isEqualTo(4);
        assertThat(mapper.writeValueAsString(previousShape).length()).isLessThanOrEqualTo(PRE_APPROVAL_CEILING_CHARS);
        assertThat(DebugToolsJsonCommand.structuralFloorChars(mapper, previousShape))
                .isLessThanOrEqualTo(PRE_APPROVAL_FLOOR_CEILING_CHARS);
    }

    @Test
    @DisplayName("决策记录：结构下限高于 D-1 门禁，所以只改说明文字永远达不到 55%")
    void textOnlyEditsCannotReachTheGate() throws Exception {
        int floor = DebugToolsJsonCommand.structuralFloorChars(mapper, toolsArray);

        // 这条断言记录的是"当前事实"。如果它失败了，通常是好消息：参数结构被精简到了门禁以下，
        // 说明 D-1 的 55% 硬门禁重新有了可能，请回头重新评估"接受 6,077 字符"这个决定。
        assertThat(floor)
                .as("结构下限 %d 已不再高于 D-1 门禁 %d：参数结构缩小了，请重新评估 D-1 的结论"
                        + "（HANDOFF 里记录的是'只改文字达不到门禁'）", floor, GATE_CHARS)
                .isGreaterThan(GATE_CHARS);
    }

    @Test
    @DisplayName("结构下限的算法：恰好等于总长度减去所有 description 文字的长度")
    void structuralFloorEqualsTotalMinusDescriptionText() throws Exception {
        // 用纯 ASCII 的假工具，这样"字符数"和"JSON 里占的字符数"一一对应，不涉及转义
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "demo");
        function.put("description", "12345");
        function.put("parameters", Map.of(
                "type", "object",
                "properties", Map.of("p", Map.of("type", "string", "description", "abc"))));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        ArrayNode array = ModelClient.buildToolsArray(mapper, List.of(tool));

        int total = mapper.writeValueAsString(array).length();
        int floor = DebugToolsJsonCommand.structuralFloorChars(mapper, array);

        assertThat(floor).isEqualTo(total - "12345".length() - "abc".length());
        // 不修改传入的数组（在深拷贝上操作）
        assertThat(mapper.writeValueAsString(array).length()).isEqualTo(total);
    }

    @Test
    @DisplayName("名叫 description 的参数不会被误清空（只清空文字类型的值）")
    void parameterNamedDescriptionIsNotBlanked() throws Exception {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "demo");
        function.put("description", "abc");
        function.put("parameters", Map.of(
                "type", "object",
                "properties", Map.of("description", Map.of("type", "string", "description", "xy"))));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        ArrayNode array = ModelClient.buildToolsArray(mapper, List.of(tool));

        int total = mapper.writeValueAsString(array).length();
        int floor = DebugToolsJsonCommand.structuralFloorChars(mapper, array);

        // 只有两处文字类型的 description（"abc" 和 "xy"）被清空；参数本身的键 "description" 保留
        assertThat(floor).isEqualTo(total - "abc".length() - "xy".length());
    }

    @Test
    @DisplayName("汇总输出里包含结构下限一行")
    void summaryOutputShowsStructuralFloor() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            DebugToolsJsonCommand.render(definitions, false, out);
        }

        assertThat(buffer.toString(StandardCharsets.UTF_8))
                .contains("删光全部 description 后的结构下限")
                .contains("说明文字最多能再省");
    }
}
