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

    /**
     * 按源文件路径删除所有块（用于重建索引前的清理），返回删除的块数。
     *
     * <p>返回删除数是「操作可见性」的关键：上层（工具/用户）需要知道
     * 究竟删了多少内容，0 行被删往往意味着路径写错或文档从未被索引。</p>
     */
    int deleteByPath(String sourcePath);

    /**
     * 列出知识库中已索引的所有源文档及其块数。
     *
     * <p>解决「模型不知道知识库里有什么、存在哪里」的问题——没有这个能力，
     * 模型面对“删除知识库”只能用终端命令满盘搜索，徒耗大量 token。
     * 返回按源路径分组的统计（路径 → 块数）。</p>
     *
     * @return 源路径到块数的有序映射；空知识库返回空 Map
     */
    java.util.LinkedHashMap<String, Integer> listSources();

    /**
     * 清空知识库全部内容（保留表结构），返回删除的块数。
     *
     * <p>「删除知识库」的正规实现：一条 DELETE 语句完成，替代危险的
     * DROP DATABASE（后者会连库带角色一起毁掉，且影响其他表）。</p>
     */
    int deleteAll();
}
