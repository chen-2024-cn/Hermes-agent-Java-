package com.cyk.rag.chunking;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MarkdownAwareChunkingTest {

    private final MarkdownAwareChunking chunking = new MarkdownAwareChunking(512, 64);

    @Test
    void shouldSplitByH2Sections() {
        String text = """
            ## Section 1
            Content A.

            ## Section 2
            Content B.
            """;

        List<Chunk> chunks = chunking.chunk(text, "/test.md", Map.of());

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).contains("Section 1");
        assertThat(chunks.get(1).content()).contains("Section 2");
    }

    @Test
    void shouldHandleNoH2Headers() {
        String text = "Plain content without any headers.";
        List<Chunk> chunks = chunking.chunk(text, "/test.md", Map.of());

        assertThat(chunks).hasSize(1);
    }

    @Test
    void shouldAddSectionMetadata() {
        String text = "## 数据库设计\n" + "详细内容。";

        List<Chunk> chunks = chunking.chunk(text, "/test.md", Map.of());

        assertThat(chunks.get(0).metadata()).containsEntry("section", "数据库设计");
    }
}
