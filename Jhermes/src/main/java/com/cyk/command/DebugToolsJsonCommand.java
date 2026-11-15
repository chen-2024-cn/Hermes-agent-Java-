package com.cyk.command;

import com.cyk.config.HermesConfig;
import com.cyk.http.ModelClient;
import com.cyk.tool.ToolRegistry;
import com.cyk.util.TokenEstimator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import picocli.CommandLine;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * <h1>{@code Jhermes debug tools-json} —— 查看将要发给模型的工具定义（JH-SPEC-001 P0-4）</h1>
 *
 * <h2>它解决什么问题？</h2>
 * <p>每次请求，Jhermes 都会把所有工具的"说明书"（名称、描述、参数结构）以 JSON 形式发给模型，
 * 这部分是输入 token 的大头。想优化它，得先<b>看清楚</b>它有多大、每个工具占多少。
 * 以前要看只能抓网络包或者加临时打印，现在一条命令搞定，而且<b>不连模型、不花 token</b>。</p>
 *
 * <h2>两种输出</h2>
 * <ul>
 *   <li>默认：汇总表——每个工具的字符数、估算 token 数、描述部分和参数部分各占多少，
 *       最后附一个 SHA-256 指纹。</li>
 *   <li>{@code --json}：只输出完整的工具 JSON（一个字节都不多），可以重定向到文件。</li>
 * </ul>
 *
 * <h2>怎么用它验证"请求前缀是否稳定"？</h2>
 * <p>分别启动两次这个命令（是两个独立的 JVM 进程），对比输出的 SHA-256 指纹：
 * 一样就说明工具定义每次启动字节都相同，供应商的前缀缓存才有机会命中；
 * 不一样说明序列化顺序还在漂移（这正是 C-08 要修的问题）。</p>
 *
 * <p>输出的 JSON 和真实请求用的是同一个方法 {@link ModelClient#buildToolsArray}，
 * 所以这里看到的就是模型真正收到的，不会有偏差。</p>
 */
@CommandLine.Command(name = "tools-json",
        description = "查看工具定义和大小(不调用模型)",
        footer = "示例: Jhermes debug tools-json --json")
public class DebugToolsJsonCommand implements Callable<Integer> {
    @CommandLine.Spec private CommandLine.Model.CommandSpec spec;
    @CommandLine.Mixin private CommandHelp help;
    @CommandLine.Option(names = "--verbose", description = "显示参数占用和定义指纹")
    private boolean verbose;

    @CommandLine.Option(names = {"--json"},
            description = "仅输出JSON数组")
    private boolean fullJson;

    @Override
    public Integer call() throws Exception {
        if (fullJson && verbose) {
            spec.commandLine().getErr().println("--json 与 --verbose 不能同时使用。");
            return 2;
        }
        try (var diagnostics = new DiagnosticOutput()) {
            HermesConfig config = HermesConfig.loadReadOnly();
            ToolRegistry.initialize(config.copyForToolInspection());
            ToolRegistry registry = ToolRegistry.getInstance();
            if (Boolean.TRUE.equals(config.getFromYml("rag.enabled", false)) && !config.getDisabledToolsets().contains("rag")) {
                com.cyk.tool.RagTool.registerSchemasForInspection(registry);
            }
            List<Map<String, Object>> definitions = registry.getDefinitions(new HashSet<>(registry.getAllToolName()));
            render(definitions, fullJson, verbose, spec.commandLine().getOut());
            return 0;
        }
    }

    /**
     * 把工具定义渲染到指定输出流。
     *
     * <p>抽成独立的静态方法，是为了能在单元测试里直接传入假数据和内存输出流来验证，
     * 而不必真的去加载配置文件、注册工具。</p>
     *
     * @param definitions 工具定义列表（与发给模型的 tools 数组同源）
     * @param fullJson    true 只输出完整 JSON；false 输出汇总表
     * @param out         输出目标
     */
    static void render(List<Map<String, Object>> definitions, boolean fullJson, PrintStream out)
            throws JsonProcessingException {
        render(definitions, fullJson, true, new java.io.PrintWriter(out, true));
    }

    public static void render(List<Map<String, Object>> definitions, boolean fullJson, boolean verbose,
                               java.io.PrintWriter out) throws JsonProcessingException {
        // 必须用"输出顺序稳定"的序列化器，否则这里打印的字节和真实请求就可能不一样
        ObjectMapper mapper = ModelClient.newStableObjectMapper();
        ArrayNode toolsArray = ModelClient.buildToolsArray(mapper, definitions);
        String json = mapper.writeValueAsString(toolsArray);

        if (fullJson) {
            // print 而不是 println：保证输出的字节和请求体里的 tools 数组完全相同，末尾不多一个换行
            out.print(json);
            out.flush();
            return;
        }

        if (!verbose) {
            out.println("工具定义预览(按配置, 未连接数据库):");
            for (JsonNode tool : toolsArray) {
                out.printf("%-24s %6d字符 ~%d tokens%n", tool.path("function").path("name").asText(),
                        mapper.writeValueAsString(tool).length(), TokenEstimator.fromChars(mapper.writeValueAsString(tool).length()));
            }
            out.printf("工具数量: %d | tools JSON 总字符: %d | ~%d tokens%n", toolsArray.size(), json.length(), TokenEstimator.fromChars(json.length()));
            out.flush();
            return;
        }
        out.println("=== 工具定义预览(按配置, 未连接数据库) ===");
        out.printf("%-28s %7s %9s %7s %7s%n", "tool", "chars", "~tokens", "desc", "params");

        int descTotal = 0;
        int paramsTotal = 0;
        for (JsonNode tool : toolsArray) {
            JsonNode function = tool.path("function");
            String name = function.path("name").asText("?");
            // description 是给模型看的文字说明；parameters 是参数结构（模型调用工具的"填表格式"）
            int descChars = function.path("description").asText("").length();
            int paramsChars = mapper.writeValueAsString(function.path("parameters")).length();
            int totalChars = mapper.writeValueAsString(tool).length();

            descTotal += descChars;
            paramsTotal += paramsChars;
            out.printf("%-28s %7d %9d %7d %7d%n",
                    name, totalChars, TokenEstimator.fromChars(totalChars), descChars, paramsChars);
        }

        out.println();
        out.println("工具数量: " + toolsArray.size());
        out.println("tools JSON 总字符: " + json.length()
                + "   估算 tokens: " + TokenEstimator.fromChars(json.length()) + "（估算值，非精确）");
        out.println("其中 description 合计: " + descTotal + "   parameters 合计: " + paramsTotal);
        // 上面两项没有算 JSON 的骨架（花括号、键名、引号），所以加起来不等于总字符数，不能用它们推算"还能压多少"。
        // 真正的下限要把所有 description 清空后重新序列化才知道：它就是"说明文字删光也省不掉"的部分。
        int floor = structuralFloorChars(mapper, toolsArray);
        out.println("删光全部 description 后的结构下限: " + floor + " 字符"
                + "（说明文字最多能再省 " + (json.length() - floor) + " 字符）");
        out.println("SHA-256: " + sha256Hex(json));
        out.println("未调用模型; 相同SHA-256表示工具定义一致。");
        out.flush();
    }

    /**
     * 把工具定义里所有 {@code description} 文字清空后，重新序列化得到的字符数。
     *
     * <p>这是"只改文字所能达到的最小体积"：键名、类型、必填列表、枚举值、花括号这些结构性内容，
     * 不管说明写得多短都必须存在，模型靠它们才知道怎么填参数。所以工具定义的大小不可能低于它。</p>
     *
     * <p>只清空<b>文字类型</b>的 description：如果某个参数恰好就叫 description，
     * 它的值是一个对象（{@code {"type":"string",...}}），不会被误清空。
     * 在 JSON 树的深拷贝上操作，不影响传入的原数组。</p>
     */
    static int structuralFloorChars(ObjectMapper mapper, ArrayNode toolsArray) throws JsonProcessingException {
        JsonNode copy = toolsArray.deepCopy();
        blankDescriptions(copy);
        return mapper.writeValueAsString(copy).length();
    }

    private static void blankDescriptions(JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            // 先把键名收集到列表里再逐个处理，避免一边遍历一边修改同一个对象
            java.util.List<String> keys = new java.util.ArrayList<>();
            object.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode value = object.get(key);
                if ("description".equals(key) && value.isTextual()) {
                    object.put(key, "");
                } else {
                    blankDescriptions(value);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                blankDescriptions(child);
            }
        }
    }

    /** 计算字符串（按 UTF-8 编码）的 SHA-256，输出 64 位十六进制小写字符串。 */
    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                // & 0xff：把有符号的 byte 当成 0~255 的无符号数；%02x：不足两位前面补 0
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // 所有 Java 实现都必须支持 SHA-256，走到这里属于不可能的情况
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
