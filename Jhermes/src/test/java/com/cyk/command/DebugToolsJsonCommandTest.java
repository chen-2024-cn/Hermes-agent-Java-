package com.cyk.command;

import com.cyk.HermesAgent;
import com.cyk.http.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code Jhermes debug tools-json} 诊断命令测试（JH-SPEC-001 P0-4）。
 *
 * <p>测试思路：不真的加载配置、注册工具，而是直接把"假的工具定义"交给静态方法
 * {@link DebugToolsJsonCommand#render}，用一个内存输出流（ByteArrayOutputStream）接住输出，
 * 再检查内容。这样测试快、不依赖磁盘上的 config.yaml。</p>
 */
class DebugToolsJsonCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 造一个工具定义：{"type":"function","function":{name, description, parameters}}。 */
    private static Map<String, Object> tool(String name, String description) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", Map.of("type", "object", "properties", Map.of()));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    /** 调用 render 并把输出收集成字符串。 */
    private static String run(List<Map<String, Object>> defs, boolean fullJson) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            DebugToolsJsonCommand.render(defs, fullJson, out);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void sha256Hex_shouldMatchKnownVector() {
        // SHA-256("abc") 是密码学里的标准测试向量，答案固定：
        assertThat(DebugToolsJsonCommand.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void fullJsonMode_shouldOutputExactlyTheRequestToolsArray() throws Exception {
        List<Map<String, Object>> defs = List.of(tool("read_file", "读取文件"), tool("write_file", "写入文件"));

        String output = run(defs, true);

        // 1) 是合法 JSON 数组，且元素个数正确
        assertThat(MAPPER.readTree(output).isArray()).isTrue();
        assertThat(MAPPER.readTree(output)).hasSize(2);

        // 2) 和真实请求使用的 buildToolsArray 输出逐字节相同（诊断工具"所见即所发"）
        var mapper = ModelClient.newStableObjectMapper();
        String expected = mapper.writeValueAsString(ModelClient.buildToolsArray(mapper, defs));
        assertThat(output).isEqualTo(expected);

        // 3) 末尾没有多余换行
        assertThat(output).doesNotEndWith("\n");
    }

    @Test
    void summaryMode_shouldListEveryToolAndTotals() throws Exception {
        List<Map<String, Object>> defs = List.of(tool("read_file", "读取文件"), tool("run_command", "执行命令"));

        String output = run(defs, false);

        assertThat(output)
                .contains("read_file")
                .contains("run_command")
                .contains("工具数量: 2")
                .contains("tools JSON 总字符")
                .contains("SHA-256: ")
                .contains("未调用模型");
    }

    @Test
    void sha256_shouldBeStable_whenOnlyMapInsertionOrderDiffers() throws Exception {
        // 模拟"两次独立启动"：内容一样，只是 key 的插入顺序不同
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("function", tool("read_file", "读取文件").get("function"));
        reordered.put("type", "function");

        String a = run(List.of(tool("read_file", "读取文件")), false);
        String b = run(List.of(reordered), false);

        // 取出各自的 SHA-256 行比较
        assertThat(lineStartingWith(a, "SHA-256: ")).isEqualTo(lineStartingWith(b, "SHA-256: "));
    }

    @Test
    void shouldBeRegisteredUnderRootCommand() {
        // 验证子命令确实挂到了根命令上：Jhermes debug tools-json 能被 picocli 找到
        CommandLine root = new CommandLine(new HermesAgent());

        assertThat(root.getSubcommands()).containsKey("debug");
        assertThat(root.getSubcommands().get("debug").getSubcommands()).containsKey("tools-json");
    }

    private static String lineStartingWith(String text, String prefix) {
        return text.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow();
    }
}
