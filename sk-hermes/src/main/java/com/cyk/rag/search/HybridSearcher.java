package com.cyk.rag.search;

import com.cyk.rag.embedding.EmbeddingClient;
import com.cyk.rag.store.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

public class HybridSearcher {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearcher.class);
    private static final double RRF_K = 60.0;

    private final VectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final double vectorWeight;
    private final double keywordWeight;

    public HybridSearcher(VectorStore vectorStore, EmbeddingClient embeddingClient,
                          double vectorWeight, double keywordWeight) {
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.vectorWeight = vectorWeight;
        this.keywordWeight = keywordWeight;
    }

    public List<SearchResult> search(String query, int topK, int expandFactor) {
        int expandLimit = topK * expandFactor;

        float[] queryVec;
        try {
            queryVec = embeddingClient.embed(query);
        } catch (Exception e) {
            logger.warn("Embedding failed, falling back to keyword-only search: {}", e.getMessage());
            return keywordOnly(query, topK);
        }

        List<ScoredChunk> vectorResults = safeSearch(() -> vectorStore.searchByVector(queryVec, expandLimit));
        List<ScoredChunk> keywordResults = safeSearch(() -> vectorStore.searchByKeyword(query, expandLimit));

        Map<String, ScoredChunk> chunkById = new HashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        accumulateRrf(rrfScores, chunkById, vectorResults, vectorWeight);
        accumulateRrf(rrfScores, chunkById, keywordResults, keywordWeight);

        return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> {
                ScoredChunk sc = chunkById.get(e.getKey());
                return new SearchResult(
                    sc.content(), sc.sourcePath(), 0, e.getValue(), sc.metadata()
                );
            })
            .toList();
    }

    public List<SearchResult> search(String query, int topK) {
        return search(query, topK, 4);
    }

    private void accumulateRrf(Map<String, Double> scores, Map<String, ScoredChunk> chunkById,
                               List<ScoredChunk> ranked, double weight) {
        for (int i = 0; i < ranked.size(); i++) {
            ScoredChunk sc = ranked.get(i);
            String id = sc.chunkId();
            chunkById.putIfAbsent(id, sc);
            double rrf = weight / (RRF_K + i + 1);
            scores.merge(id, rrf, Double::sum);
        }
    }

    private List<ScoredChunk> safeSearch(Supplier<List<ScoredChunk>> searchFn) {
        try {
            return searchFn.get();
        } catch (Exception e) {
            logger.warn("Search branch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<SearchResult> keywordOnly(String query, int topK) {
        try {
            List<ScoredChunk> results = vectorStore.searchByKeyword(query, topK);
            return results.stream()
                .map(sc -> new SearchResult(sc.content(), sc.sourcePath(), 0, sc.score(), sc.metadata()))
                .toList();
        } catch (Exception e) {
            logger.error("Keyword search also failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<SearchResult> convert(List<ScoredChunk> chunks) {
        return chunks.stream()
            .map(sc -> new SearchResult(sc.content(), sc.sourcePath(), 0, sc.score(), sc.metadata()))
            .toList();
    }
}
