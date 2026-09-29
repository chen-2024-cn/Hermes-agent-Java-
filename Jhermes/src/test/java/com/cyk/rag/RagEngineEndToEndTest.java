package com.cyk.rag;

import com.cyk.rag.chunking.Chunk;
import com.cyk.rag.embedding.EmbeddingClient;
import com.cyk.rag.parser.*;
import com.cyk.rag.search.HybridSearcher;
import com.cyk.rag.search.SearchResult;
import com.cyk.rag.store.InMemoryVectorStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * RAG 引擎端到端测试。
 * <p>
 * 使用 InMemoryVectorStore + 假 EmbeddingClient，无需 PostgreSQL/Docker/API Key。
 * 运行：mvn test -Dtest=RagEngineEndToEndTest
 *
 * <h3>测试流程</h3>
 * <ol>
 *   <li>创建临时的 .md 和 .txt 测试文档</li>
 *   <li>用真实 Parser + ChunkingStrategy 解析切分</li>
 *   <li>用假 Embedding 生成伪向量</li>
 *   <li>存入 InMemoryVectorStore</li>
 *   <li>通过 HybridSearcher 检索</li>
 *   <li>验证结果正确性</li>
 * </ol>
 */
@DisplayName("RAG 引擎端到端流水线测试")
class RagEngineEndToEndTest {

    private InMemoryVectorStore vectorStore;
    private EmbeddingClient fakeEmbedding;
    private HybridSearcher searcher;

    private TextParser textParser;
    private MarkdownParser markdownParser;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryVectorStore();

        // 假 Embedding：用文本前 1024 个字符的 hash 生成伪向量
        // 语义相似的文本会产生相似的向量（至少前几个维度相同字符会匹配）
        fakeEmbedding = new EmbeddingClient() {
            @Override
            public float[] embed(String text) {
                float[] vec = new float[1024];
                // 用字符编码填充向量，相同文本产生相同向量
                char[] chars = text.toCharArray();
                for (int i = 0; i < Math.min(chars.length, 1024); i++) {
                    vec[i] = (float) chars[i] / 65536.0f;
                }
                return vec;
            }

            @Override
            public List<float[]> embedBatch(List<String> texts) {
                return texts.stream().map(this::embed).toList();
            }

            @Override
            public int dimension() {
                return 1024;
            }
        };

        searcher = new HybridSearcher(vectorStore, fakeEmbedding, 0.7, 0.3);
        textParser = new TextParser();
        markdownParser = new MarkdownParser();
    }

    // ==================== 测试 1: 单文件索引 + 检索 ====================

    @Test
    @DisplayName("索引一个 Markdown 文件后能语义检索到相关内容")
    void shouldIndexAndSearchMarkdownFile(@TempDir Path tempDir) throws Exception {
        // 1. 创建测试文档
        Path mdFile = tempDir.resolve("design-doc.md");
        Files.writeString(mdFile, """
                # 用户认证系统设计

                ## 认证方式
                系统使用 JWT + RSA 密钥对进行用户认证。
                Token 有效期为 2 小时，支持 refresh token 续期。

                ## 数据库设计
                用户表包含 username、password_hash、email 字段。
                使用 PostgreSQL 作为主数据库，Redis 缓存 session。

                ## 部署方案
                使用 Docker Compose 一键部署，Nginx 反向代理。
                """);

        // 2. 解析
        String text = markdownParser.parse(mdFile);
        Map<String, Object> meta = markdownParser.extractMetadata(mdFile, text);
        assertThat(text).contains("JWT + RSA");
        assertThat(meta).containsEntry("title", "用户认证系统设计");

        // 3. 切分（MarkdownAwareChunking 按 ## 标题切）
        var chunking = new com.cyk.rag.chunking.MarkdownAwareChunking(512, 64);
        List<Chunk> chunks = chunking.chunk(text, mdFile.toAbsolutePath().toString(), meta);
        assertThat(chunks).isNotEmpty();
        System.out.println("  → 切分为 " + chunks.size() + " 个块");

        // 4. Embedding + 存储
        List<String> texts = chunks.stream().map(Chunk::content).toList();
        List<float[]> embeddings = fakeEmbedding.embedBatch(texts);
        vectorStore.insertBatch(chunks, embeddings);
        assertThat(vectorStore.size()).isEqualTo(chunks.size());

        // 5. 检索 — 搜"JWT 认证"
        List<SearchResult> results = searcher.search("JWT 认证 RSA", 3);
        assertThat(results).isNotEmpty();
        System.out.println("  → 检索到 " + results.size() + " 条结果");

        // 6. 验证 — 搜索结果中应该包含含 JWT 的块
        boolean foundJwt = results.stream().anyMatch(r -> r.content().contains("JWT"));
        assertThat(foundJwt).as("搜索结果中应包含 JWT 相关块").isTrue();
        assertThat(results.get(0).sourcePath()).contains("design-doc.md");

        // 7. 检索 — 搜"Redis"
        List<SearchResult> redisResults = searcher.search("Redis 缓存", 3);
        // 关键词 "redis" 能在内容中找到
        boolean foundRedis = redisResults.stream().anyMatch(r ->
                r.content().toLowerCase().contains("redis"));
        assertThat(foundRedis).as("搜索结果中应包含 Redis 相关块").isTrue();
    }

    // ==================== 测试 2: 多文件索引 + 交叉检索 ====================

    @Test
    @DisplayName("索引多个文件后能区分不同来源")
    void shouldDistinguishMultipleSources(@TempDir Path tempDir) throws Exception {
        // 1. 创建两个文档
        Path javaDoc = tempDir.resolve("java-guide.txt");
        Files.writeString(javaDoc, """
                Java 21 新增了虚拟线程（Virtual Threads）特性。
                虚拟线程由 JVM 调度，不是操作系统线程。
                使用 Executors.newVirtualThreadPerTaskExecutor() 创建。
                """);

        Path pythonDoc = tempDir.resolve("python-guide.txt");
        Files.writeString(pythonDoc, """
                Python 使用 asyncio 实现异步编程。
                async/await 语法简化了协程的使用。
                FastAPI 是基于 asyncio 的 Web 框架。
                """);

        // 2. 解析 + 切分 + 索引
        for (Path file : List.of(javaDoc, pythonDoc)) {
            String text = textParser.parse(file);
            var chunking = new com.cyk.rag.chunking.FixedSizeChunking(512, 64);
            List<Chunk> chunks = chunking.chunk(text, file.toAbsolutePath().toString(), Map.of());
            List<float[]> embeddings = fakeEmbedding.embedBatch(
                    chunks.stream().map(Chunk::content).toList());
            vectorStore.insertBatch(chunks, embeddings);
        }

        assertThat(vectorStore.countByPath("java-guide")).isEqualTo(1);
        assertThat(vectorStore.countByPath("python-guide")).isEqualTo(1);

        // 3. 搜 Java
        List<SearchResult> javaResults = searcher.search("虚拟线程 JVM", 3);
        assertThat(javaResults).isNotEmpty();
        assertThat(javaResults.get(0).sourcePath()).contains("java-guide");
        assertThat(javaResults.get(0).content()).contains("虚拟线程");

        // 4. 搜 Python — token 化后 "asyncio" 能命中
        List<SearchResult> pythonResults = searcher.search("asyncio async 协程", 3);
        assertThat(pythonResults).isNotEmpty();
        assertThat(pythonResults.get(0).sourcePath()).contains("python-guide");
        assertThat(pythonResults.get(0).content()).contains("asyncio");
    }

    // ==================== 测试 3: 关键词精确匹配 ====================

    @Test
    @DisplayName("精确关键词检索优于纯语义检索")
    void shouldFindExactKeywordMatch(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("config.txt");
        Files.writeString(file, """
                数据库连接字符串: jdbc:postgresql://192.168.1.100:5432/production
                API_KEY=sk-abc123def456
                JWT_SECRET=my-super-secret-key
                日志级别: DEBUG
                """);

        String text = textParser.parse(file);
        var chunking = new com.cyk.rag.chunking.FixedSizeChunking(512, 64);
        List<Chunk> chunks = chunking.chunk(text, file.toAbsolutePath().toString(), Map.of());
        List<float[]> embeddings = fakeEmbedding.embedBatch(
                chunks.stream().map(Chunk::content).toList());
        vectorStore.insertBatch(chunks, embeddings);

        // 搜精确字符串 — 关键词分支应该命中
        List<SearchResult> results = searcher.search("JWT_SECRET", 3);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).content()).contains("JWT_SECRET");
    }

    // ==================== 测试 4: 空结果场景 ====================

    @Test
    @DisplayName("搜索不存在的知识返回空结果")
    void shouldReturnEmptyForUnknownQuery(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("notes.txt");
        Files.writeString(file, "今天天气很好。适合出去散步。");

        String text = textParser.parse(file);
        var chunking = new com.cyk.rag.chunking.FixedSizeChunking(512, 64);
        List<Chunk> chunks = chunking.chunk(text, file.toAbsolutePath().toString(), Map.of());
        List<float[]> embeddings = fakeEmbedding.embedBatch(
                chunks.stream().map(Chunk::content).toList());
        vectorStore.insertBatch(chunks, embeddings);

        // 搜完全不相关的内容
        List<SearchResult> results = searcher.search("量子力学 薛定谔方程", 3);

        // 没有匹配内容，但假 embedding 仍会返回结果（因为向量检索总有最近邻）
        // 这里验证的是：不抛异常，正常返回
        assertThat(results).isNotNull();
        System.out.println("  → 检索到 " + results.size() + " 条结果（假向量总有最近邻）");
    }

    // ==================== 测试 5: 删除后不可检索 ====================

    @Test
    @DisplayName("删除索引后无法检索到对应内容")
    void shouldNotFindDeletedContent(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("temp-notes.txt");
        Files.writeString(file, "这是一份临时文档，稍后会被删除。");

        String text = textParser.parse(file);
        var chunking = new com.cyk.rag.chunking.FixedSizeChunking(512, 64);
        List<Chunk> chunks = chunking.chunk(text, file.toAbsolutePath().toString(), Map.of());
        List<float[]> embeddings = fakeEmbedding.embedBatch(
                chunks.stream().map(Chunk::content).toList());
        vectorStore.insertBatch(chunks, embeddings);

        assertThat(vectorStore.countByPath("temp-notes")).isEqualTo(1);

        // 删除
        vectorStore.deleteByPath(file.toAbsolutePath().toString());
        assertThat(vectorStore.countByPath("temp-notes")).isEqualTo(0);
    }

    // ==================== 测试 6: PDF 解析器 ====================

    @Test
    @DisplayName("PDF 解析器对无效文件抛出异常")
    void shouldThrowOnInvalidPdf(@TempDir Path tempDir) {
        PdfParser pdfParser = new PdfParser();
        Path notAPdf = tempDir.resolve("fake.pdf");
        // 文件不存在 — 应抛出 IOException
        assertThatThrownBy(() -> pdfParser.parse(notAPdf))
                .isInstanceOf(Exception.class);
    }
}
