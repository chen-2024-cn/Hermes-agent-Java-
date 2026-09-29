package com.cyk.rag.chunking;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FixedSizeChunkingTest {

    private final FixedSizeChunking chunking = new FixedSizeChunking(512, 64);

    @Test
    void shouldNotSplitShortText() {
        List<Chunk> chunks = chunking.chunk("短文本。", "/test.txt", Map.of());

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("短文本。");
        assertThat(chunks.get(0).chunkIndex()).isEqualTo(0);
        assertThat(chunks.get(0).sourcePath()).isEqualTo("/test.txt");
    }

    @Test
    void shouldPreserveMetadata() {
        Map<String, Object> meta = Map.of("title", "My Doc");
        List<Chunk> chunks = chunking.chunk("Hello world。", "/test.txt", meta);

        assertThat(chunks.get(0).metadata()).containsEntry("title", "My Doc");
    }

    @Test
    void shouldReturnEmptyForBlankText() {
        List<Chunk> chunks = chunking.chunk("   ", "/test.txt", Map.of());
        assertThat(chunks).isEmpty();
    }

    @Test
    void shouldGenerateUniqueIds() {
        String longText = "A".repeat(5000);
        List<Chunk> chunks = chunking.chunk(longText, "/test.txt", Map.of());

        assertThat(chunks.size()).isGreaterThan(1);
        // 所有 ID 唯一
        Set<String> ids = new HashSet<>();
        for (Chunk c : chunks) ids.add(c.id());
        assertThat(ids).hasSize(chunks.size());
    }

    @Test
    void shouldIncrementChunkIndex() {
        String longText = "A".repeat(5000);
        List<Chunk> chunks = chunking.chunk(longText, "/test.txt", Map.of());

        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).chunkIndex()).isEqualTo(i);
        }
    }
}
