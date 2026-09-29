package com.cyk.rag.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser();

    @Test
    void shouldStripYamlFrontMatter(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("doc.md");
        Files.writeString(file, """
            ---
            title: Test
            ---
            # Hello
            Content here.
            """);

        String result = parser.parse(file);
        assertThat(result).doesNotContain("---");
        assertThat(result).contains("# Hello");
        assertThat(result).contains("Content here.");
    }

    @Test
    void shouldExtractTitleFromH1(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("doc.md");
        Files.writeString(file, """
            # Project Design
            ## Section 1
            Some content.
            """);

        var meta = parser.extractMetadata(file, Files.readString(file));
        assertThat(meta).containsEntry("title", "Project Design");
    }

    @Test
    void shouldHandleNoFrontMatter(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("plain.md");
        Files.writeString(file, "Just some **markdown** content.");

        String result = parser.parse(file);
        assertThat(result).contains("**markdown**");
    }
}
