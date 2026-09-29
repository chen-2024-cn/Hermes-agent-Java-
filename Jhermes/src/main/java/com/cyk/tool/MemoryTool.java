package com.cyk.tool;

import com.cyk.bean.ToolEntry;
import com.cyk.manager.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;


public class MemoryTool {

    private static final Logger logger = LoggerFactory.getLogger(MemoryTool.class);
    public static final MemoryManager memory = MemoryManager.getInstance();
    /**
     * 添加记忆
     */
    public static String saveMemory(Map<String, Object> args){
        String category = ((String) args.get("category"));
        String content = ((String) args.get("content"));

        boolean success;
        if ("user".equals(category)) {
            success = memory.addUser(content);
        } else {
            success = memory.addMemory(content);
        }

        if (!success) {
            return ToolRegistry.toolError("添加记忆失败");
        }

        return ToolRegistry.toolResult(Map.of(
                "success",true,
                    "category", category,
                "content_preview", content.substring(0, Math.min(content.length(), 100))
        ));
        
    }

    /**
     * 注册工具
     */
    public static void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
                .name("memory_save")
                .toolset("memory")
                .schema(Map.of(
                        "description", "Save a durable fact to memory. Use category='memory' for environment/learned info, 'user' for user preferences.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "category", Map.of(
                                                "type", "string",
                                                "enum", List.of("memory", "user"),
                                                "description", "Memory category: 'memory' for environment/learned info, 'user' for user preferences"
                                        ),
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "The fact to remember"
                                        )
                                ),
                                "required", List.of("category", "content")
                        )
                ))
                .handler(MemoryTool::saveMemory)
                .emoji("🧠")
                .build());

        // memory_get - get memories by category
        registry.register(new ToolEntry.Builder()
                .name("memory_get")
                .toolset("memory")
                .schema(Map.of(
                        "description", "Get memories by category",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "category", Map.of(
                                                "type", "string",
                                                "enum", List.of("memory", "user"),
                                                "description", "Category to retrieve"
                                        ),
                                        "limit", Map.of(
                                                "type", "integer",
                                                "description", "Max results",
                                                "default", 10
                                        )
                                ),
                                "required", List.of("category")
                        )
                ))
                .handler(MemoryTool::getMemory)
                .emoji("📋")
                .build());

        // memory_delete - delete by substring match
        registry.register(new ToolEntry.Builder()
                .name("memory_delete")
                .toolset("memory")
                .schema(Map.of(
                        "description", "Delete a memory entry by substring match",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "category", Map.of(
                                                "type", "string",
                                                "enum", List.of("memory", "user"),
                                                "description", "Category to delete from"
                                        ),
                                        "substring", Map.of(
                                                "type", "string",
                                                "description", "Substring to match for deletion"
                                        )
                                ),
                                "required", List.of("category", "substring")
                        )
                ))
                .handler(MemoryTool::deleteMemory)
                .emoji("🗑️")
                .build());

        // memory_replace - replace by substring match
        registry.register(new ToolEntry.Builder()
                .name("memory_replace")
                .toolset("memory")
                .schema(Map.of(
                        "description", "Replace a memory entry by substring match",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "category", Map.of(
                                                "type", "string",
                                                "enum", List.of("memory", "user"),
                                                "description", "Category to replace in"
                                        ),
                                        "old_substring", Map.of(
                                                "type", "string",
                                                "description", "Substring to match for replacement"
                                        ),
                                        "new_content", Map.of(
                                                "type", "string",
                                                "description", "New content to replace with"
                                        )
                                ),
                                "required", List.of("category", "old_substring", "new_content")
                        )
                ))
                .handler(MemoryTool::replaceMemory)
                .emoji("✏️")
                .build());
    }

    /**
     * 替换旧记忆
     * @param args
     * @return
     */
    private static String replaceMemory(Map<String, Object> args) {
        Object catObj = args.get("category");
        Object oldObj = args.get("old_substring");
        Object newObj = args.get("new_content");
        if (catObj == null || catObj.toString().trim().isEmpty()) {
            return ToolRegistry.toolError("Category is required");
        }

        if (oldObj == null || oldObj.toString().trim().isEmpty()) {
            return ToolRegistry.toolError("Old substring is required");
        }

        if (newObj == null || newObj.toString().trim().isEmpty()) {
            return ToolRegistry.toolError("New content is required");
        }

        String category = catObj.toString();
        String oldSubstring = oldObj.toString();
        String newContent = newObj.toString();

        try {
            boolean replaced = memory.replace(category, oldSubstring, newContent);

            return ToolRegistry.toolResult(Map.of(
                    "success", replaced,
                    "category", category,
                    "old_substring", oldSubstring
            ));

        } catch (Exception e) {
            logger.error("Failed to replace memory: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Replace failed: " + e.getMessage());
        }

    }

    /**
     * 删除记忆
     * @param args
     * @return
     */
    private static String deleteMemory(Map<String, Object> args) {
        Object catObj = args.get("category");
        Object subObj = args.get("substring");

        if (catObj == null || catObj.toString().trim().isEmpty()) {
            return ToolRegistry.toolError("Category is required");
        }

        if (subObj == null || subObj.toString().trim().isEmpty()) {
            return ToolRegistry.toolError("Substring is required");
        }

        String category = catObj.toString();
        String substring = subObj.toString();

        try{

            boolean isDelete = memory.delete(category, substring);//真正的删除逻辑
            return ToolRegistry.toolResult(Map.of(
                    "success", isDelete,
                    "category", category,
                        "substring", substring
            ));
        } catch (Exception e) {
            logger.error("Failed to delete memory: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Delete failed: " + e.getMessage());
        }
    }

    /**
     * 获取memory
     * @param args
     * @return
     */
    private static String getMemory(Map<String, Object> args) {
        Object catObj = args.get("category");
        if (catObj == null || catObj.toString().trim().isEmpty()) {
            return ToolRegistry.toolError("Category is required");
        }
        String category = catObj.toString();
        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 10;

        try{
            List<String>  res = memory.getByCategory(category, limit);
            return ToolRegistry.toolResult(Map.of(
                    "category", category,
                    "results", res,
                    "count", res.size()
            ));

        } catch (Exception e) {
            logger.error("Failed to get memory: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Get failed:" + e.getMessage());
        }
    }


}
