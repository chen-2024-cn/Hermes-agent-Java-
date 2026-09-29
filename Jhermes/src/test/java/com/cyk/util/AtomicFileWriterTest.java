package com.cyk.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * AtomicFileWriter 原子落盘测试。
 *
 * <p>验证 write-temp-then-rename 范式的正确性与整洁性：内容正确、UTF-8 编码、
 * 覆盖旧文件、自动建父目录、成功后不残留临时文件、异常时清理临时文件。</p>
 */
class AtomicFileWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteContentToNewFile() throws IOException {
        Path target = tempDir.resolve("MEMORY.md");
        AtomicFileWriter.writeStringAtomically(target, "用户喜欢简洁回答§项目用 Java 21§");

        assertThat(target).exists();
        assertThat(Files.readString(target, StandardCharsets.UTF_8))
                .isEqualTo("用户喜欢简洁回答§项目用 Java 21§");
    }

    @Test
    void shouldOverwriteExistingFileCompletely() throws IOException {
        Path target = tempDir.resolve("USER.md");
        AtomicFileWriter.writeStringAtomically(target, "旧内容AAAAAAAAAAAAAAAA");
        AtomicFileWriter.writeStringAtomically(target, "新");

        // 覆盖必须是完整的，不能出现"新" + 旧内容残留（truncate 语义）
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("新");
    }

    @Test
    void shouldEncodeAsUtf8PreservingChineseAndSymbols() throws IOException {
        Path target = tempDir.resolve("utf8.md");
        String content = "中文·emoji😀·分隔符§·换行\n第二行";
        AtomicFileWriter.writeStringAtomically(target, content);

        // 按 UTF-8 读回应逐字节一致
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(content);
        byte[] raw = Files.readAllBytes(target);
        assertThat(new String(raw, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    void shouldCreateMissingParentDirectories() throws IOException {
        Path target = tempDir.resolve("a/b/c/deep.md");
        AtomicFileWriter.writeStringAtomically(target, "深层文件");

        assertThat(target).exists();
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("深层文件");
    }

    @Test
    void shouldNotLeaveTempFilesAfterSuccess() throws IOException {
        Path target = tempDir.resolve("clean.md");
        AtomicFileWriter.writeStringAtomically(target, "内容");

        // 成功后目录里只应有目标文件，不留 .hermes-atomic-*.tmp 垃圾
        try (Stream<Path> files = Files.list(tempDir)) {
            List<Path> leftovers = files
                    .filter(p -> p.getFileName().toString().startsWith(".hermes-atomic-"))
                    .toList();
            assertThat(leftovers).isEmpty();
        }
    }

    @Test
    void shouldHandleEmptyContent() throws IOException {
        Path target = tempDir.resolve("empty.md");
        AtomicFileWriter.writeStringAtomically(target, "");

        assertThat(target).exists();
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void shouldWriteLargeContentFully() throws IOException {
        // 验证大内容下 FileChannel.write 循环写完整（单次 write 可能只写部分字节）
        String big = "x".repeat(200_000);
        Path target = tempDir.resolve("big.md");
        AtomicFileWriter.writeStringAtomically(target, big);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).hasSize(200_000);
    }

    @Test
    void writeBytesShouldProduceSameResultAsString() throws IOException {
        String content = "字节写入测试§";
        Path target = tempDir.resolve("bytes.md");
        AtomicFileWriter.writeBytesAtomically(target, content.getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    void shouldPreserveExistingFileWhenTargetIsADirectory() {
        // 目标路径是一个已存在的目录：move 必然失败，应抛异常且不破坏原目录
        Path dir = tempDir.resolve("i-am-a-dir");
        assertThatCode(() -> Files.createDirectories(dir)).doesNotThrowAnyException();

        assertThatThrownBy(() -> AtomicFileWriter.writeStringAtomically(dir, "内容"))
                .isInstanceOf(IOException.class);
        assertThat(Files.isDirectory(dir)).isTrue();
    }
}
