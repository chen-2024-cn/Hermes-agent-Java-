package com.cyk.rag.store;

import com.cyk.rag.chunking.Chunk;
import com.cyk.rag.search.ScoredChunk;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * 集成测试 — 需要本地或 Docker PostgreSQL + pgvector。
 * 环境变量 PG_URL / PG_USER / PG_PASS 配置连接。
 * 如无可用环境，CI 中用 Testcontainers 自动拉起。
 */
@Tag("integration")
class PgVectorStoreTest {

    private static PgVectorStore store;

    @BeforeAll
    static void setUp() {
        String url = System.getenv().getOrDefault("PG_URL", "jdbc:postgresql://localhost:5432/hermes_rag");
        String user = System.getenv().getOrDefault("PG_USER", "hermes");
        String pass = System.getenv().getOrDefault("PG_PASS", "hermes");
        store = new PgVectorStore(url, user, pass);
        store.initSchema();
    }

    @Test
    void shouldInsertAndSearchByVector() {
        store.deleteByPath("/test/vec.md");

        Chunk chunk = new Chunk(UUID.randomUUID().toString(), "/test/vec.md", 0,
            "向量检索测试内容", 5, Map.of("section", "test"));

        float[] embedding = new float[1536];
        embedding[0] = 1.0f;

        store.insertBatch(List.of(chunk), List.of(embedding));

        List<ScoredChunk> results = store.searchByVector(embedding, 5);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).sourcePath()).isEqualTo("/test/vec.md");
    }

    @Test
    void shouldSearchByKeyword() {
        store.deleteByPath("/test/kw.md");

        Chunk chunk = new Chunk(UUID.randomUUID().toString(), "/test/kw.md", 0,
            "Java 项目使用 Spring Boot 框架", 5, Map.of());

        float[] embedding = new float[1536];
        store.insertBatch(List.of(chunk), List.of(embedding));

        List<ScoredChunk> results = store.searchByKeyword("Spring Boot", 5);
        assertThat(results).isNotEmpty();
    }
}
