package com.cyk.rag.store;

import com.cyk.rag.chunking.Chunk;
import com.cyk.rag.search.ScoredChunk;
import java.util.List;

/**
 * 向量存储接口。支持 pgvector / LanceDB / Milvus 等后端替换。
 */
public interface VectorStore {

    /** 初始化表结构，幂等（CREATE TABLE IF NOT EXISTS） */
    void initSchema();

    /** 批量插入文档块及其向量 */
    void insertBatch(List<Chunk> chunks, List<float[]> embeddings);

    /** 向量相似度检索（cosine distance） */
    List<ScoredChunk> searchByVector(float[] queryVec, int limit);

    /** 关键词全文检索 */
    List<ScoredChunk> searchByKeyword(String query, int limit);

    /** 按源文件路径删除所有块（用于重建索引前的清理） */
    void deleteByPath(String sourcePath);
}
