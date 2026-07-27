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
    }
}
