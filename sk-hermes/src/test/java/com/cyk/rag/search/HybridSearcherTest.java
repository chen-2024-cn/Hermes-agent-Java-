package com.cyk.rag.search;

import com.cyk.rag.chunking.Chunk;
import com.cyk.rag.embedding.EmbeddingClient;
import com.cyk.rag.store.VectorStore;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class HybridSearcherTest {

    @Test
    void shouldFuseVectorAndKeywordResults() {
        EmbeddingClient mockEmbedding = new EmbeddingClient() {
            @Override public float[] embed(String text) { return new float[1536]; }
            @Override public int dimension() { return 1536; }
        };

        VectorStore mockStore = new VectorStore() {
            @Override public void initSchema() {}
            @Override public void insertBatch(List<Chunk> chunks, List<float[]> embeddings) {}
            @Override public List<ScoredChunk> searchByVector(float[] queryVec, int limit) {
                return List.of(
                    new ScoredChunk("id1", "/a.md", "向量结果A", 0, 0.95, Map.of()),
                    new ScoredChunk("id2", "/b.md", "向量结果B", 0, 0.80, Map.of())
                );
            }
            @Override public List<ScoredChunk> searchByKeyword(String query, int limit) {
                return List.of(
                    new ScoredChunk("id2", "/b.md", "关键词结果B", 0, 0.90, Map.of()),
                    new ScoredChunk("id3", "/c.md", "关键词结果C", 0, 0.70, Map.of())
                );
            }
            @Override public void deleteByPath(String sourcePath) {}
        };

        HybridSearcher searcher = new HybridSearcher(mockStore, mockEmbedding, 0.7, 0.3);
        List<SearchResult> results = searcher.search("test query", 3);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).sourcePath()).isEqualTo("/b.md");
    }

    @Test
    void shouldReturnEmptyOnBothBranchesFailed() {
        EmbeddingClient mockEmbedding = new EmbeddingClient() {
            @Override public float[] embed(String text) { throw new RuntimeException("API down"); }
            @Override public int dimension() { return 1536; }
        };

        VectorStore mockStore = new VectorStore() {
            @Override public void initSchema() {}
            @Override public void insertBatch(List<Chunk> chunks, List<float[]> embeddings) {}
            @Override public List<ScoredChunk> searchByVector(float[] queryVec, int limit) {
                throw new RuntimeException("DB down");
            }
            @Override public List<ScoredChunk> searchByKeyword(String query, int limit) {
                throw new RuntimeException("DB down");
            }
            @Override public void deleteByPath(String sourcePath) {}
        };

        HybridSearcher searcher = new HybridSearcher(mockStore, mockEmbedding, 0.7, 0.3);
        List<SearchResult> results = searcher.search("test", 5);

        assertThat(results).isEmpty();
    }
}
