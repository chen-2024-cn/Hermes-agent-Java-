package com.cyk.rag.embedding;

import java.util.List;

/**
 * 文本向量化客户端接口。
 * 支持单条和批量 embedding，实现可替换（API / 本地模型）。
 */
public interface EmbeddingClient {
    /**
     * 将单条文本转为向量。
     */
    float[] embed(String text);

    /**
     * 批量向量化，提升吞吐。
     * 默认实现回退到逐条调用。
     */
    default List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /**
     * 返回向量维度。
     */
    int dimension();
}
