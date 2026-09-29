package com.cyk.rag.search;

import java.util.Map;

/**
 * rag_search 工具返回给 LLM 的最终结果。
 */
public record SearchResult(
    String content,
    String sourcePath,
    int chunkIndex,
    double score,
    Map<String, Object> metadata
) {}
