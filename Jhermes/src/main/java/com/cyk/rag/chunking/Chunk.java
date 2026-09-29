package com.cyk.rag.chunking;

import java.util.Map;

/**
 * 文档切分后的文本块。
 */
public record Chunk(
    String id,
    String sourcePath,
    int chunkIndex,
    String content,
    int tokenCount,
    Map<String, Object> metadata
) {}
