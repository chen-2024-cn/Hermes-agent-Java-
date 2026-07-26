package com.cyk.rag.store;

import com.cyk.rag.chunking.Chunk;
import com.cyk.rag.search.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * PostgreSQL + pgvector JDBC 实现。
 * 所有 SQL 参数化防注入。
 */
public class PgVectorStore implements VectorStore {

    private static final Logger logger = LoggerFactory.getLogger(PgVectorStore.class);

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PgVectorStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    @Override
    public void initSchema() {
        String sql = """
            CREATE EXTENSION IF NOT EXISTS vector;

            CREATE TABLE IF NOT EXISTS rag_documents (
                id              VARCHAR(36) PRIMARY KEY,
                source_path     VARCHAR(1024) NOT NULL,
                source_hash     VARCHAR(64),
                chunk_index     INT NOT NULL DEFAULT 0,
                content         TEXT NOT NULL,
                token_count     INT,
                embedding       vector(1536),
                metadata        JSONB DEFAULT '{}',
                created_at      TIMESTAMP DEFAULT NOW(),
                UNIQUE (source_path, chunk_index)
            )
            """;

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            logger.info("RAG schema initialized");
        } catch (SQLException e) {
            logger.error("Failed to init RAG schema", e);
            throw new RuntimeException("RAG schema init failed", e);
        }

        // 分别创建索引（CREATE INDEX IF NOT EXISTS 不是标准 SQL，需逐条 try-catch）
        String[] indexSqls = {
            "CREATE INDEX IF NOT EXISTS rag_docs_embedding_idx ON rag_documents USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100)",
            "CREATE INDEX IF NOT EXISTS rag_docs_fts_idx ON rag_documents USING GIN (to_tsvector('simple', content))",
            "CREATE INDEX IF NOT EXISTS rag_docs_path_idx ON rag_documents (source_path)"
        };
        for (String indexSql : indexSqls) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute(indexSql);
            } catch (SQLException e) {
                logger.debug("Index may already exist: {}", e.getMessage());
            }
        }
    }

    @Override
    public void insertBatch(List<Chunk> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks and embeddings must have same size");
        }
        if (chunks.isEmpty()) return;

        String sql = """
            INSERT INTO rag_documents (id, source_path, source_hash, chunk_index, content, token_count, embedding, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?::jsonb)
            ON CONFLICT (source_path, chunk_index) DO UPDATE SET
                content = EXCLUDED.content,
                embedding = EXCLUDED.embedding,
                metadata = EXCLUDED.metadata,
                created_at = NOW()
            """;

        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < chunks.size(); i++) {
                    Chunk chunk = chunks.get(i);
                    float[] vec = embeddings.get(i);

                    ps.setString(1, chunk.id());
                    ps.setString(2, chunk.sourcePath());
                    ps.setString(3, null); // source_hash: 由 RagEngine 计算
                    ps.setInt(4, chunk.chunkIndex());
                    ps.setString(5, chunk.content());
                    ps.setInt(6, chunk.tokenCount());

                    // pgvector: float[] → 逗号分隔的字符串表示
                    ps.setString(7, vectorToString(vec));
                    ps.setString(8, toJson(chunk.metadata()));

                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
            logger.debug("Inserted {} chunks", chunks.size());
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rb) {
                    logger.error("Rollback failed", rb);
                }
            }
            logger.error("Failed to insert batch", e);
            throw new RuntimeException("Batch insert failed", e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    logger.error("Failed to reset auto-commit", e);
                }
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.error("Failed to close connection", e);
                }
            }
        }
    }

    @Override
    public List<ScoredChunk> searchByVector(float[] queryVec, int limit) {
        String vecStr = vectorToString(queryVec);
        String sql = """
            SELECT id, source_path, content, 1 - (embedding <=> ?::vector) AS similarity, metadata
            FROM rag_documents
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;

        List<ScoredChunk> results = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, vecStr);
            ps.setString(2, vecStr);
            ps.setInt(3, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new ScoredChunk(
                        rs.getString("id"),
                        rs.getString("source_path"),
                        rs.getString("content"),
                        rs.getDouble("similarity"),
                        parseMetadata(rs.getString("metadata"))
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Vector search failed (limit={})", limit, e);
        }
        return results;
    }

    @Override
    public List<ScoredChunk> searchByKeyword(String query, int limit) {
        String sql = """
            SELECT id, source_path, content,
                   ts_rank(to_tsvector('simple', content), plainto_tsquery('simple', ?)) AS rank,
                   metadata
            FROM rag_documents
            WHERE to_tsvector('simple', content) @@ plainto_tsquery('simple', ?)
            ORDER BY rank DESC
            LIMIT ?
            """;

        List<ScoredChunk> results = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, query);
            ps.setString(2, query);
            ps.setInt(3, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new ScoredChunk(
                        rs.getString("id"),
                        rs.getString("source_path"),
                        rs.getString("content"),
                        rs.getDouble("rank"),
                        parseMetadata(rs.getString("metadata"))
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Keyword search failed (query={}, limit={})", query, limit, e);
        }
        return results;
    }

    @Override
    public void deleteByPath(String sourcePath) {
        String sql = "DELETE FROM rag_documents WHERE source_path = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourcePath);
            int deleted = ps.executeUpdate();
            logger.debug("Deleted {} chunks for path: {}", deleted, sourcePath);
        } catch (SQLException e) {
            logger.error("Failed to delete by path: {}", sourcePath, e);
        }
    }

    // --- helpers ---

    private String vectorToString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String toJson(Map<String, Object> metadata) {
        // 简单手写 JSON，避免依赖额外库（也可用 Jackson ObjectMapper）
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : metadata.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object val = entry.getValue();
            if (val instanceof String s) {
                sb.append("\"").append(escapeJson(s)).append("\"");
            } else if (val instanceof Number) {
                sb.append(val);
            } else {
                sb.append("\"").append(escapeJson(String.valueOf(val))).append("\"");
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        // 简单解析，实际可引入 Jackson
        Map<String, Object> result = new HashMap<>();
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
            for (String pair : json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().replaceAll("^\"|\"$", "");
                    String value = kv[1].trim().replaceAll("^\"|\"$", "");
                    result.put(key, value);
                }
            }
        }
        return result;
    }
}
