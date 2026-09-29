package com.cyk.rag.store;

import com.cyk.rag.chunking.Chunk;
import com.cyk.rag.search.ScoredChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存向量存储（测试用假实现）。
 * 支持简单的余弦相似度检索和关键词检索，无需 PostgreSQL。
 */
public class InMemoryVectorStore implements VectorStore {

    private final Map<String, StoredChunk> chunks = new ConcurrentHashMap<>();

    private record StoredChunk(
            String id, String sourcePath, int chunkIndex,
            String content, int tokenCount, float[] embedding,
            Map<String, Object> metadata) {
    }

    @Override
    public void initSchema() {
        // 内存存储无需建表
    }

    @Override
    public void insertBatch(List<Chunk> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks and embeddings must have same size");
        }
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            float[] vec = embeddings.get(i);
            StoredChunk sc = new StoredChunk(
                    c.id(), c.sourcePath(), c.chunkIndex(),
                    c.content(), c.tokenCount(), vec,
                    new HashMap<>(c.metadata())
            );
            this.chunks.put(c.id(), sc);
        }
    }

    @Override
    public List<ScoredChunk> searchByVector(float[] queryVec, int limit) {
        return chunks.values().stream()
                .filter(sc -> sc.embedding() != null)
                .map(sc -> {
                    double similarity = cosineSimilarity(queryVec, sc.embedding());
                    return new ScoredChunk(
                            sc.id(), sc.sourcePath(), sc.content(),
                            sc.chunkIndex(), similarity, sc.metadata()
                    );
                })
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<ScoredChunk> searchByKeyword(String query, int limit) {
        // 将查询拆分为 token，逐个匹配（中英文混合场景）
        String[] tokens = query.toLowerCase().split("[\\s，。！？、]+");
        return chunks.values().stream()
                .filter(sc -> {
                    String lowerContent = sc.content().toLowerCase();
                    for (String token : tokens) {
                        if (token.length() >= 2 && lowerContent.contains(token)) {
                            return true;
                        }
                    }
                    return false;
                })
                .map(sc -> new ScoredChunk(
                        sc.id(), sc.sourcePath(), sc.content(),
                        sc.chunkIndex(), 0.5, sc.metadata()  // 关键词命中给固定 0.5 分
                ))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public int deleteByPath(String sourcePath) {
        List<String> toRemove = chunks.entrySet().stream()
                .filter(e -> e.getValue().sourcePath().equals(sourcePath))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        toRemove.forEach(chunks::remove);
        return toRemove.size();
    }

    @Override
    public LinkedHashMap<String, Integer> listSources() {
        // 与 PgVectorStore 语义对齐：按源路径分组统计块数，路径升序返回
        Map<String, Integer> counts = new TreeMap<>();
        for (StoredChunk sc : chunks.values()) {
            counts.merge(sc.sourcePath(), 1, Integer::sum);
        }
        // TreeMap 已排序，包一层 LinkedHashMap 固定顺序语义（与接口声明一致）
        return new LinkedHashMap<>(counts);
    }

    @Override
    public int deleteAll() {
        int count = chunks.size();
        chunks.clear();
        return count;
    }

    /** 块数量（测试断言用） */
    public int size() {
        return chunks.size();
    }

    /** 清空所有数据 */
    public void clear() {
        chunks.clear();
    }

    /** 按源路径统计块数 */
    public long countByPath(String sourcePath) {
        return chunks.values().stream()
                .filter(sc -> sc.sourcePath().contains(sourcePath))
                .count();
    }

    // --- 向量工具 ---

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
