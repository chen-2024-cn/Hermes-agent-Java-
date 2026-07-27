package com.cyk.rag;

import com.cyk.config.HermesConfig;
import com.cyk.rag.chunking.*;
import com.cyk.rag.embedding.*;
import com.cyk.rag.parser.*;
import com.cyk.rag.search.*;
import com.cyk.rag.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

/**
 * RAG 引擎 — 统一入口。
 * 编排文档解析、分块、向量化、存储、检索全流程。
 * 不依赖 ToolRegistry / Agent，可独立测试。
 */
public class RagEngine {

    private static final Logger logger = LoggerFactory.getLogger(RagEngine.class);

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final Map<String, DocumentParser> parsers;
    private final HybridSearcher searcher;
    private final ChunkingStrategy defaultChunking;
    private final ChunkingStrategy markdownChunking;

    private RagEngine(EmbeddingClient embeddingClient, VectorStore vectorStore,
                      Map<String, DocumentParser> parsers, HybridSearcher searcher,
                      ChunkingStrategy defaultChunking, ChunkingStrategy markdownChunking) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.parsers = parsers;
        this.searcher = searcher;
        this.defaultChunking = defaultChunking;
        this.markdownChunking = markdownChunking;
    }

    /** 工厂方法：从配置创建 RagEngine。RAG 未启用或初始化失败返回 null。 */
    public static RagEngine create(HermesConfig config) {
        Boolean enabled = config.getFromYml("rag.enabled", false);
        if (!enabled) {
            logger.info("RAG is disabled (rag.enabled=false)");
            return null;
        }

        try {
            // Embedding
            String embedUrl = config.getFromYml("rag.embedding.base_url", config.getBaseUrl());
            String embedModel = config.getFromYml("rag.embedding.model", "text-embedding-3-small");
            int dimension = config.getFromYml("rag.embedding.dimension", 1536);
            String apiKey = config.getApiKey();
            EmbeddingClient embedding = new DeepSeekEmbedding(embedUrl, embedModel, apiKey, dimension);

            // VectorStore
            String pgUrl = config.getFromYml("rag.pgvector.url", "");
            String pgUser = config.getFromYml("rag.pgvector.username", "");
            String pgPass = config.getFromYml("rag.pgvector.password", "");
            VectorStore store = new PgVectorStore(pgUrl, pgUser, pgPass);
            store.initSchema();

            // Parsers
            Map<String, DocumentParser> parsers = new HashMap<>();
            parsers.put("txt", new TextParser());
            parsers.put("md", new MarkdownParser());
            parsers.put("pdf", new PdfParser());

            // Chunking
            int chunkSize = config.getFromYml("rag.chunking.size", 512);
            int overlap = config.getFromYml("rag.chunking.overlap", 64);
            ChunkingStrategy defaultChunking = new FixedSizeChunking(chunkSize, overlap);
            ChunkingStrategy markdownChunking = new MarkdownAwareChunking(chunkSize, overlap);

            // Searcher
            double vecWeight = config.getFromYml("rag.search.vector_weight", 0.7);
            double kwWeight = config.getFromYml("rag.search.keyword_weight", 0.3);
            HybridSearcher searcher = new HybridSearcher(store, embedding, vecWeight, kwWeight);

            return new RagEngine(embedding, store, parsers, searcher, defaultChunking, markdownChunking);
        } catch (Exception e) {
            logger.error("Failed to initialize RAG engine", e);
            return null;
        }
    }

    /**
     * 索引文件或目录。
     * @param path 文件或目录路径
     * @param recursive 是否递归子目录
     * @param force 是否强制重建（会先删除已有索引）
     * @param fileTypes 逗号分隔的扩展名，null 表示所有支持类型
     * @return 统计结果 Map
     */
    public Map<String, Object> index(Path path, boolean recursive, boolean force, String fileTypes) throws IOException {
        Set<String> allowedTypes = fileTypes != null
            ? new HashSet<>(Arrays.asList(fileTypes.toLowerCase().split(",")))
            : parsers.keySet();

        List<Path> files = collectFiles(path, recursive, allowedTypes);

        int indexedFiles = 0, totalChunks = 0, skippedFiles = 0, failedFiles = 0;
        long startTime = System.currentTimeMillis();

        for (Path file : files) {
            try {
                String ext = getExtension(file).toLowerCase();
                DocumentParser parser = parsers.get(ext);
                if (parser == null) {
                    skippedFiles++;
                    continue;
                }

                if (!force && !hasChanged(file)) {
                    skippedFiles++;
                    continue;
                }

                if (force) {
                    vectorStore.deleteByPath(file.toString());
                }

                // 解析
                String text = parser.parse(file);
                Map<String, Object> meta = parser.extractMetadata(file, text);

                // 切分
                ChunkingStrategy strategy = "md".equals(ext) ? markdownChunking : defaultChunking;
                List<Chunk> chunks = strategy.chunk(text, file.toAbsolutePath().toString(), meta);

                if (chunks.isEmpty()) {
                    skippedFiles++;
                    continue;
                }

                // Embedding（批量）
                List<String> chunkTexts = chunks.stream().map(Chunk::content).toList();
                List<float[]> embeddings = embeddingClient.embedBatch(chunkTexts);

                // 存储
                vectorStore.insertBatch(chunks, embeddings);

                indexedFiles++;
                totalChunks += chunks.size();
                logger.debug("Indexed: {} ({} chunks)", file.getFileName(), chunks.size());

            } catch (Exception e) {
                logger.error("Failed to index: {}", file, e);
                failedFiles++;
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        return Map.of(
            "indexed_files", indexedFiles,
            "total_chunks", totalChunks,
            "skipped_files", skippedFiles,
            "failed_files", failedFiles,
            "elapsed_seconds", elapsed / 1000.0
        );
    }

    /** 检索知识库。 */
    public List<SearchResult> search(String query, int topK) {
        return searcher.search(query, topK);
    }

    /** 按路径删除已索引的文档块。 */
    public int deleteByPath(String sourcePath) {
        // VectorStore.deleteByPath is void, count not available
        vectorStore.deleteByPath(sourcePath);
        return 0;
    }

    // --- private helpers ---

    private List<Path> collectFiles(Path path, boolean recursive, Set<String> allowedTypes) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(path)) {
            String ext = getExtension(path).toLowerCase();
            if (allowedTypes.contains(ext)) {
                files.add(path);
            }
        } else if (Files.isDirectory(path)) {
            int maxDepth = recursive ? Integer.MAX_VALUE : 1;
            try (Stream<Path> stream = Files.walk(path, maxDepth)) {
                stream.filter(Files::isRegularFile)
                    .filter(f -> allowedTypes.contains(getExtension(f).toLowerCase()))
                    .forEach(files::add);
            }
        }
        return files;
    }

    private String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    private boolean hasChanged(Path file) {
        try {
            String hash = sha256(file);
            // 简化：这里不做差量检测（需查 DB）。force=false 时仅依赖 ON CONFLICT 的幂等性。
            // 完整的增量检测需在此处查询 rag_documents.source_hash，暂略。
            return true;
        } catch (Exception e) {
            return true; // 算不出 hash 就当有变化
        }
    }

    private String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
