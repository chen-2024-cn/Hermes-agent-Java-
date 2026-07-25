package com.cyk.rag.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class TextParserTest {

    private final TextParser parser = new TextParser();

    @Test
    void shouldParseUtf8File(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello 世界");

        String result = parser.parse(file);
        assertThat(result).isEqualTo("Hello 世界");
    }

    @Test
    void shouldReturnFileNameMetadata(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("readme.txt");
        Files.writeString(file, "content");

        var meta = parser.extractMetadata(file, "content");
        assertThat(meta).containsEntry("file_name", "readme.txt");
    }

    @Test
    void shouldThrowOnMissingFile() {
        assertThatThrownBy(() -> parser.parse(Path.of("/nonexistent/file.txt")))
            .isInstanceOf(java.io.IOException.class);
    }
}
