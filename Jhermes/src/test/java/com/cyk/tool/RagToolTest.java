package com.cyk.tool;

import com.cyk.rag.RagEngine;
import com.cyk.rag.chunking.ChunkingStrategy;
import com.cyk.rag.chunking.FixedSizeChunking;
import com.cyk.rag.embedding.EmbeddingClient;
import com.cyk.rag.parser.DocumentParser;
import com.cyk.rag.parser.MarkdownParser;
import com.cyk.rag.parser.TextParser;
import com.cyk.rag.search.HybridSearcher;
import com.cyk.rag.store.InMemoryVectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * RagTool 的 rag_list / rag_delete / rag_search / rag_index 测试。
 *
 * <p>通过 {@code RagEngine.createForTesting} 注入内存假实现（固定向量 Embedding +
 * InMemoryVectorStore），完整走「索引 → 列表 → 删除 → 清空」链路，无需真实数据库。
 * 覆盖本轮修复的核心场景：模型对知识库的可见性与删除能力。</p>
 */
class RagToolTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DIM = 4;

    /** 固定维度假 Embedding：内容 hash 决定向量，保证同文本同向量 */
    private static final EmbeddingClient FAKE_EMBEDDING = new EmbeddingClient() {
        @Override
        public float[] embed(String text) {
            float[] vec = new float[DIM];
            int hash = text.hashCode();
            for (int i = 0; i < DIM; i++) {
                vec[i] = ((hash >> (i * 8)) & 0xFF) / 255.0f - 0.5f;
            }
            // 全零向量会让余弦相似度除零，给一个最小扰动
            if (vec[0] == 0 && vec[1] == 0 && vec[2] == 0 && vec[3] == 0) {
                vec[0] = 0.01f;
            }
            return vec;
        }

        @Override
        public int dimension() {
            return DIM;
        }
    };

    private InMemoryVectorStore store;
    private RagEngine engine;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore();
        Map<String, DocumentParser> parsers = new HashMap<>();
        parsers.put("txt", new TextParser());
        parsers.put("md", new MarkdownParser());
        HybridSearcher searcher = new HybridSearcher(store, FAKE_EMBEDDING, 0.7, 0.3);
        ChunkingStrategy chunking = new FixedSizeChunking(64, 8);
        engine = RagEngine.createForTesting(FAKE_EMBEDDING, store, parsers, searcher, chunking, chunking);
        RagTool.setEngine(engine);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) throws Exception {
        return mapper.readValue(json, Map.class);
    }

    /** 生成一个可索引的 txt 测试文件，返回绝对路径字符串 */
    private String newDocFile(String name) throws Exception {
        Files.writeString(tempDir.resolve(name), "向量数据库学习笔记。pgvector 是 PostgreSQL 的扩展。",
                StandardCharsets.UTF_8);
        return tempDir.resolve(name).toAbsolutePath().normalize().toString();
    }

    // =========================================================================
    // 索引 + rag_list：模型的知识库自视图
    // =========================================================================

    @Test
    void indexThenListShouldShowDocumentAndChunkCount() throws Exception {
        String path = newDocFile("kb_demo.txt");

        Map<String, Object> indexResult = parse(RagTool.index(Map.of("path", path)));
        assertThat((Integer) indexResult.get("indexed_files")).isEqualTo(1);
        assertThat(store.size()).isGreaterThan(0);

        Map<String, Object> listResult = parse(RagTool.list(Map.of()));
        assertThat((Integer) listResult.get("document_count")).isEqualTo(1);
        Map<String, Object> documents = (Map<String, Object>) listResult.get("documents");
        assertThat(documents).containsKey(path);
        assertThat((Integer) listResult.get("total_chunks")).isEqualTo(store.size());
    }

    @Test
    void listOnEmptyKnowledgeBaseShouldReturnZeroCounts() throws Exception {
        Map<String, Object> result = parse(RagTool.list(Map.of()));
        assertThat((Integer) result.get("document_count")).isZero();
        assertThat((Integer) result.get("total_chunks")).isZero();
        assertThat((Map<String, Object>) result.get("documents")).isEmpty();
    }

    // =========================================================================
    // rag_delete：按路径删除
    // =========================================================================

    @Test
    void deleteByPathShouldRemoveAllChunksOfThatDocument() throws Exception {
        String keep = newDocFile("keep.txt");
        String drop = newDocFile("drop.txt");
        RagTool.index(Map.of("path", tempDir.toString()));

        int before = store.size();
        Map<String, Object> result = parse(RagTool.delete(Map.of("path", drop)));
        assertThat((Integer) result.get("deleted_chunks")).isPositive();
        assertThat(store.size()).isLessThan(before);

        Map<String, Object> listed = parse(RagTool.list(Map.of()));
        Map<String, Object> documents = (Map<String, Object>) listed.get("documents");
        assertThat(documents).containsKey(keep).doesNotContainKey(drop);
    }

    @Test
    void deleteUnknownPathShouldReturnZeroAndListIndexedCandidates() throws Exception {
        String indexed = newDocFile("real.txt");
        RagTool.index(Map.of("path", indexed));

        // 用不存在的相对路径删除：应返回 0 并给出已索引文档清单（帮模型自我纠错，
        // 而不是让它盲猜路径反复重试）
        Map<String, Object> result = parse(RagTool.delete(Map.of("path", "not-indexed.txt")));
        assertThat((Integer) result.get("deleted_chunks")).isZero();
        List<String> candidates = (List<String>) result.get("indexed_documents");
        assertThat(candidates).containsExactly(indexed);
        assertThat((String) result.get("note")).contains("没有被索引");
    }

    // =========================================================================
    // rag_delete：清空全部（all + confirm 双重门槛）
    // =========================================================================

    @Test
    void deleteAllWithoutConfirmShouldBeRefusedWithDataUntouched() throws Exception {
        newDocFile("a.txt");
        RagTool.index(Map.of("path", tempDir.toString()));
        int before = store.size();

        Map<String, Object> result = parse(RagTool.delete(Map.of("all", true)));
        assertThat(result).containsKey("error");
        assertThat((String) result.get("error")).contains("confirm");
        assertThat(store.size()).isEqualTo(before); // 数据分毫未动
    }

    @Test
    void deleteAllWithWrongConfirmValueShouldBeRefused() throws Exception {
        newDocFile("a.txt");
        RagTool.index(Map.of("path", tempDir.toString()));

        Map<String, Object> result = parse(RagTool.delete(Map.of("all", true, "confirm", "maybe")));
        assertThat(result).containsKey("error");
        assertThat(store.size()).isPositive();
    }

    @Test
    void deleteAllWithConfirmShouldClearEverythingButStayReusable() throws Exception {
        newDocFile("a.txt");
        RagTool.index(Map.of("path", tempDir.toString()));
        assertThat(store.size()).isPositive();

        Map<String, Object> result = parse(RagTool.delete(Map.of("all", true, "confirm", "yes")));
        assertThat((Boolean) result.get("cleared")).isTrue();
        assertThat((Integer) result.get("deleted_chunks")).isPositive();
        assertThat(store.size()).isZero();

        // 清空后知识库仍可继续使用（表结构/内存结构未破坏）
        String fresh = newDocFile("fresh.txt");
        Map<String, Object> reindex = parse(RagTool.index(Map.of("path", fresh)));
        assertThat((Integer) reindex.get("indexed_files")).isEqualTo(1);
    }

    // =========================================================================
    // 参数校验：path 与 all 互斥
    // =========================================================================

    @Test
    void deleteWithBothPathAndAllShouldBeRejected() throws Exception {
        Map<String, Object> result = parse(RagTool.delete(Map.of(
                "path", "some.txt", "all", true, "confirm", "yes")));
        assertThat(result).containsKey("error");
        assertThat((String) result.get("error")).contains("二选一");
    }

    @Test
    void deleteWithNeitherPathNorAllShouldBeRejected() throws Exception {
        Map<String, Object> result = parse(RagTool.delete(Map.of()));
        assertThat(result).containsKey("error");
        assertThat((String) result.get("error")).contains("二选一");
    }

    // =========================================================================
    // 引擎不可用（RAG 未启用）时的降级
    // =========================================================================

    @Test
    void allHandlersShouldReportErrorWhenEngineUnavailable() throws Exception {
        RagTool.setEngine(null);
        try {
            assertThat(parse(RagTool.list(Map.of()))).containsKey("error");
            assertThat(parse(RagTool.delete(Map.of("path", "x.txt")))).containsKey("error");
            assertThat(parse(RagTool.index(Map.of("path", "x.txt")))).containsKey("error");
            assertThat(parse(RagTool.search(Map.of("query", "q")))).containsKey("error");
        } finally {
            RagTool.setEngine(engine);
        }
    }

    // =========================================================================
    // rag_search 仍然正常（回归保护：新代码不破坏旧能力）
    // =========================================================================

    @Test
    void searchShouldStillReturnResultsAfterAllChanges() throws Exception {
        newDocFile("vec.txt");
        RagTool.index(Map.of("path", tempDir.toString()));

        // RagTool.search 成功时直接序列化 List<SearchResult>（JSON 数组）
        String json = RagTool.search(Map.of("query", "向量数据库", "top_k", 3));
        List<Object> results = mapper.readValue(json, List.class);
        assertThat(results).isNotEmpty();
    }
}
