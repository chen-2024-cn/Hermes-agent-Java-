package com.cyk.tool;

import com.cyk.rag.RagEngine;
import com.cyk.rag.search.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.*;

public class RagTool {

    private static final Logger logger = LoggerFactory.getLogger(RagTool.class);
    private static volatile RagEngine engine;

    /** Set engine instance (called during ToolRegistry.initialize) */
    public static void setEngine(RagEngine ragEngine) {
        engine = ragEngine;
    }

    /** rag_index handler */
    public static String index(Map<String, Object> args) {
        if (engine == null) {
            return ToolRegistry.toolError("RAG engine is not available (check rag.enabled config and pgvector connection)");
        }
        String pathStr = (String) args.get("path");
        if (pathStr == null || pathStr.isBlank()) {
            return ToolRegistry.toolError("Path parameter is required");
        }
        boolean recursive = !args.containsKey("recursive") || (boolean) args.get("recursive");
        boolean force = args.containsKey("force") && (boolean) args.get("force");
        String fileTypes = (String) args.get("file_types");

        try {
            Path path = Paths.get(pathStr).toAbsolutePath().normalize();
            Map<String, Object> result = engine.index(path, recursive, force, fileTypes);
            return ToolRegistry.toolResult(result);
        } catch (Exception e) {
            logger.error("Indexing failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Indexing failed: " + e.getMessage());
        }
    }

    /**
     * rag_list handler：列出知识库已索引的全部文档及块数。
     *
     * <p>回答“知识库里有什么”的唯一正规途径——模型直接拿到事实清单，
     * 无需（也绝不应该）用终端命令满盘搜索猜测存储位置。</p>
     */
    public static String list(Map<String, Object> args) {
        if (engine == null) {
            return ToolRegistry.toolError("RAG engine is not available (check rag.enabled config and pgvector connection)");
        }
        try {
            java.util.LinkedHashMap<String, Integer> sources = engine.listSources();
            int totalChunks = sources.values().stream().mapToInt(Integer::intValue).sum();
            return ToolRegistry.toolResult(Map.of(
                "documents", sources,
                "document_count", sources.size(),
                "total_chunks", totalChunks));
        } catch (Exception e) {
            logger.error("List sources failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("List failed: " + e.getMessage());
        }
    }

    /**
     * rag_delete handler：删除知识库内容（指定文档或全部）。
     *
     * <p>参数设计：<b>path 与 all 二选一、必须显式二选一</b>。
     * 不设「缺省即全删」——那种隐式危险默认值是事故之源；
     * all=true 还要求 confirm="yes" 双重确认（对齐企业级删除操作的二次确认惯例），
     * 防止轻量模型误解参数时把整个知识库误清。</p>
     */
    public static String delete(Map<String, Object> args) {
        if (engine == null) {
            return ToolRegistry.toolError("RAG engine is not available (check rag.enabled config and pgvector connection)");
        }
        String pathStr = (String) args.get("path");
        boolean all = Boolean.TRUE.equals(args.get("all"));
        String confirm = (String) args.get("confirm");

        boolean hasPath = pathStr != null && !pathStr.isBlank();
        if (hasPath == all) {
            // 两者都给或都不给：拒绝执行，要求模型明确意图
            return ToolRegistry.toolError("参数错误：path（删单个文档）与 all=true（清空全部）必须二选一");
        }
        try {
            if (all) {
                if (!"yes".equalsIgnoreCase(confirm)) {
                    return ToolRegistry.toolError(
                        "清空知识库是不可逆操作：必须先向用户展示将删除的内容（调用 rag_list）并获得明确同意，"
                        + "然后携带 confirm=\"yes\" 重新调用。");
                }
                int deleted = engine.deleteAll();
                return ToolRegistry.toolResult(Map.of(
                    "cleared", true,
                    "deleted_chunks", deleted,
                    "note", "知识库已清空（表结构保留，可重新索引）"));
            }
            // 按路径删：索引时存的是绝对路径，这里同样规范化后传入，
            // 避免相对路径写法导致 0 行被删（用户以为删了实际没删）
            java.nio.file.Path normalized = java.nio.file.Paths.get(pathStr).toAbsolutePath().normalize();
            int deleted = engine.deleteByPath(normalized.toString());
            if (deleted == 0) {
                // 兼容大小写/分隔符差异：严格匹配删不到时列出候选让模型自行比对
                java.util.LinkedHashMap<String, Integer> sources = engine.listSources();
                return ToolRegistry.toolResult(Map.of(
                    "deleted_chunks", 0,
                    "path", normalized.toString(),
                    "indexed_documents", sources.keySet(),
                    "note", "该路径没有被索引。请核对 indexed_documents 中的实际路径后重试。"));
            }
            return ToolRegistry.toolResult(Map.of(
                "deleted_chunks", deleted,
                "path", normalized.toString()));
        } catch (Exception e) {
            logger.error("Delete failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Delete failed: " + e.getMessage());
        }
    }

    /** rag_search handler */
    public static String search(Map<String, Object> args) {
        if (engine == null) {
            return ToolRegistry.toolError("RAG engine is not available (check rag.enabled config and pgvector connection)");
        }
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) {
            return ToolRegistry.toolError("Query parameter is required");
        }
        int topK = args.containsKey("top_k") ? ((Number) args.get("top_k")).intValue() : 5;
        topK = Math.min(topK, 20);

        try {
            List<SearchResult> results = engine.search(query, topK);
            return ToolRegistry.toolResult(results);
        } catch (Exception e) {
            logger.error("Search failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Search failed: " + e.getMessage());
        }
    }

    /** Register rag_index and rag_search tools */
    public static void register(ToolRegistry registry) {
        if (engine == null) {
            logger.info("RAG engine not available, skipping rag tool registration");
            return;
        }

        registry.register(new com.cyk.bean.ToolEntry.Builder()
            .name("rag_index")
            .toolset("rag")
            .schema(Map.of(
                "description", "将文档索引到知识库中，支持 Markdown/TXT/PDF。索引后可通过 rag_search 语义检索。支持增量更新。",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of("type", "string", "description", "文件或目录的绝对路径"),
                        "recursive", Map.of("type", "boolean", "description", "是否递归处理子目录，默认 true"),
                        "force", Map.of("type", "boolean", "description", "是否强制重建索引，默认 false"),
                        "file_types", Map.of("type", "string", "description", "逗号分隔的扩展名，如 'md,pdf,txt'")),
                    "required", List.of("path"))))
            .handler(RagTool::index)
            .emoji("📚")
            .description("Index documents into the RAG knowledge base")
            .build());

        registry.register(new com.cyk.bean.ToolEntry.Builder()
            .name("rag_search")
            .toolset("rag")
            .schema(Map.of(
                "description", "从已索引的知识库中语义搜索相关内容。返回最相关的文档片段及其来源路径。",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "query", Map.of("type", "string", "description", "自然语言搜索查询"),
                        "top_k", Map.of("type", "integer", "description", "返回结果数量，默认 5，最大 20")),
                    "required", List.of("query"))))
            .handler(RagTool::search)
            .emoji("🔍")
            .description("Search the RAG knowledge base semantically")
            .build());

        registry.register(new com.cyk.bean.ToolEntry.Builder()
            .name("rag_list")
            .toolset("rag")
            .schema(Map.of(
                "description", "列出知识库中已索引的全部文档（源路径与块数），成本极低。两类场景必用："
                    + "①用户问“知识库里有什么/在哪个库/占多少空间”时直接调用；"
                    + "②你要做内容创作、提示词/文案/规范/模板改写，或准备给出某种方法论之前，"
                    + "先调用它扫一眼清单，确认库里是否已有对口资料（你无法预知库里存了什么，不扫就可能凭空作答而漏掉用户沉淀的规范）。"
                    + "禁止用终端命令搜索磁盘来探查知识库。",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of())))
            .handler(RagTool::list)
            .emoji("📑")
            .description("List all indexed documents in the RAG knowledge base")
            .build());

        registry.register(new com.cyk.bean.ToolEntry.Builder()
            .name("rag_delete")
            .toolset("rag")
            .schema(Map.of(
                "description", "删除知识库内容。path=删除指定文档的全部块；all=true 且用户已明确同意时清空整个知识库。"
                    + "存储层是 PostgreSQL（pgvector）里的 rag_documents 表，删除/清空请用本工具，"
                    + "绝不需要也不应该用 run_command 去找数据库文件或 DROP DATABASE。",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "path", Map.of("type", "string", "description", "要删除的已索引文档路径（与 all 二选一；可先用 rag_list 查看准确路径）"),
                        "all", Map.of("type", "boolean", "description", "true=清空整个知识库（需同时传 confirm=\"yes\"）"),
                        "confirm", Map.of("type", "string", "description", "仅在 all=true 时必传，值必为 yes，表示用户已明确同意清空")),
                    "required", List.of())))
            .handler(RagTool::delete)
            .emoji("🗑")
            .description("Delete indexed documents or clear the whole RAG knowledge base")
            .build());
    }
}
