package com.cyk.tool;

import com.cyk.bean.ToolEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * <h1>工具注册与调度中心</h1>
 *
 * <p>作为 Hermes 智能体的核心基础设施，该类负责：</p>
 * <ol>
 *   <li><b>注册</b> — 将文件读写、搜索等操作封装成标准化工具存入内存</li>
 *   <li><b>调度</b> — 根据 AI 模型传来的工具名和参数，找到对应工具并执行</li>
 *   <li><b>暴露</b> — 将所有工具的 schema（名称、参数定义）转换为 AI 模型能理解的格式</li>
 *   <li><b>结果序列化</b> — 统一将执行结果或错误信息转换为 JSON 字符串返回</li>
 * </ol>
 *
 * <p>设计上采用<b>单例模式</b>，全局只有一个工具箱实例，确保所有工具注册在同一处。</p>
 *
 * <p>工作流程：</p>
 * <pre>
 *   启动时：各工具类调用 register() 注册
 *      ↓
 *   运行时：AI 模型拿到 getDefinitions() 了解可用工具
 *      ↓
 *   调用时：AI 指定工具名 + 参数 → dispatch() 执行并返回结果
 * </pre>
 */
public class ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

    /** Jackson 序列化器，用于将 Java 对象转为 JSON 字符串 */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 全局唯一的工具箱实例（单例模式） */
    private static final ToolRegistry instance = new ToolRegistry();

    /**
     * 工具存储表
     * key   → 工具名称，如 "read_file"、"write_file"
     * value → ToolEntry 对象，包含工具的 schema、执行函数、元信息等
     */
    private final Map<String, ToolEntry> tools = new HashMap<>();

    public ToolRegistry() {
    }

    /**
     * 获取工具箱单例
     * <p>全局只有一个 ToolRegistry 对象，所有工具都注册到同一个实例中</p>
     */
    public static ToolRegistry getInstance() {
        return instance;
    }

    /**
     * 注册一个工具到工具箱
     *
     * <p>如果同名工具已存在，会打印警告日志，但仍会覆盖（后注册的生效）</p>
     *
     * @param entry 工具条目，包含工具名、参数 schema、执行函数等完整信息
     */
    public void  register(ToolEntry entry) {
        String name = entry.getName();
        ToolEntry existing = tools.get(name);
        if (existing != null) {
            logger.warn("当前工具已经注册: {}", name);
        }
        tools.put(name, entry);
    }

    /**
     * 列出所有已注册的工具名称
     *
     * @return 工具名称列表（不可变），例如 ["read_file", "write_file", "search_files", "grep_files"]
     */
    public List<String> getAllToolName() {
        return tools.keySet().stream().toList();
    }

    /**
     * 将工具执行成功的结果序列化为 JSON 字符串
     *
     * <p>所有工具的执行结果都通过此方法统一输出，保证输出格式一致</p>
     *
     * @param data 可以是 Map、List、String 等任意 Java 对象，Jackson 会自动序列化
     * @return JSON 字符串
     * @throws RuntimeException 如果序列化失败（实际很少发生）
     */
    public static String toolResult(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 将工具执行失败的错误信息序列化为 JSON 字符串
     *
     * <p>统一格式：{"error": "具体错误信息"}，便于 AI 模型识别和展示</p>
     *
     * @param message 错误描述
     * @return JSON 字符串，形如 {"error": "文件路径非法"}
     * @throws RuntimeException 如果序列化失败
     */
    public static String toolError(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 根据工具名分发执行
     *
     * <p>这是 AI 调用工具的<b>唯一入口</b>：</p>
     * <ol>
     *   <li>从 tools 表中按名称查找 ToolEntry</li>
     *   <li>找到 → 调用 ToolEntry 绑定的 handler 函数，传入参数</li>
     *   <li>找不到 → 返回错误 JSON</li>
     * </ol>
     *
     * @param name 工具名称，如 "read_file"
     * @param args 工具参数，键值对形式，如 {"path": "test.txt", "offset": 1, "limit": 10}
     * @return 工具执行结果或错误信息的 JSON 字符串
     */
    public String dispatch(String name, Map<String, Object> args) {
        ToolEntry toolEntry = tools.get(name);
        if (toolEntry == null) {
            return toolError("未找到工具：" + name);
        }
        // handler 是在注册时绑定的函数引用，如 FileTool::readFile
        return toolEntry.getHandler().apply(args);
    }

    /**
     * 获取工具定义列表，暴露给 AI 模型
     *
     * <p>将指定工具集转换为标准的 function-calling 格式：</p>
     * <pre>{@code
     * {
     *   "type": "function",
     *   "function": {
     *     "name": "read_file",
     *     "description": "Read contents of a file",
     *     "parameters": {
     *       "type": "object",
     *       "properties": { "path": {"type": "string"}, ... },
     *       "required": ["path"]
     *     }
     *   }
     * }
     * }</pre>
     *
     * <p>工具名按字典序排序后输出，保证顺序一致。如果传入的工具名在注册表中不存在，则跳过</p>
     *
     * @param toolsName 本次需要暴露的工具名称集合（不是全部，按需选择）
     * @return AI 模型可理解的 function-calling 格式列表
     */
    public List<Map<String, Object>> getDefinitions(Set<String> toolsName) {
        List<Map<String, Object>> result = new ArrayList<>();
        // 按字典序排序，保证每次输出顺序一致
        for (String name : toolsName.stream().sorted().toList()) {
            ToolEntry toolEntry = tools.get(name);
            if (toolEntry == null) {
                continue;
            }
            // 复制 schema，把工具名也塞进去
            Map<Object, Object> schemaWithName = new HashMap<>(toolEntry.getSchema());
            schemaWithName.put("name", name);

            Map<String, Object> definition = new HashMap<>();
            definition.put("type", "function");
            definition.put("function", schemaWithName);

            result.add(definition);
        }
        return result;
    }

    /**
     * 从工具箱中移除一个已注册的工具
     *
     * @param name 工具名称
     * @return 被移除的 ToolEntry，如果工具不存在则返回 null
     */
    public ToolEntry unregister(String name) {
        ToolEntry removed = tools.remove(name);
        if (removed != null) {
            logger.debug("Unregistered tool: {}", name);
        }
        return removed;
    }

    /**
     * 注册要使用的工具
     */
    public static void initialize() {
        FileTool.register(instance);
        MemoryTool.register(instance);
        SkillTool.register(instance);
        WebSearchTool.register(instance);
        FetchPageTool.register(instance);
    }

}
