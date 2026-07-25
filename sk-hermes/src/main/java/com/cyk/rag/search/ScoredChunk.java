package com.cyk.rag.search;

import java.util.Map;

/**
 * 检索命中的带分块，由 VectorStore 返回的中间结果。
 */
public record ScoredChunk(
    String chunkId,
    String sourcePath,
    String content,
    double score,
    Map<String, Object> metadata
) {}
