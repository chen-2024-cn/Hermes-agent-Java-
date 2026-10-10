package com.cyk.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>read_file 读取缺省值修正（JH-SPEC-001 P0-5 R9 / AC8）</h1>
 *
 * <p>改造前有两个缺陷（附录 A7）：</p>
 * <ol>
 *   <li>未传 {@code limit} 时缺省只读 <b>1 行</b>——模型调用 read_file 想看整个文件，
 *       却只拿到第一行，于是反复试探 offset/limit，每次都是一整轮 LLM 往返 + 整份历史重发；</li>
 *   <li>{@code total_lines} 返回的是<b>本次返回的行数</b>而非文件真实总行数——
 *       模型无从判断「还剩多少内容、下一页 offset 该填多少」，只能翻页试探。</li>
 * </ol>
 *
 * <p>现在缺省读 {@link FileTool#DEFAULT_READ_LIMIT} 行（仍受 dispatch 的 12,000 字符
 * 护栏二次截断），{@code total_lines} 返回文件真实总行数。</p>
 */
class FileToolReadDefaultsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 测试目录刻意放在 {@code target/} 下，而不是 JUnit {@code @TempDir}：
     * {@code FileTool.isPathAllowed} 黑名单了 {@code java.io.tmpdir}
     * （Windows 下即 {@code AppData\Local\Temp}），用 @TempDir 建出来的文件
     * 会被 FileTool 自己的路径守卫判成「文件路径非法」，测试将验证不到真实逻辑。
     */
    private static final Path TEST_DIR =
            Path.of("target", "test-data", "filetool-read").toAbsolutePath().normalize();

    private static final int LINE_COUNT = 10;
    private Path sampleFile;

    @BeforeEach
    void writeSampleFile() throws IOException {
        Files.createDirectories(TEST_DIR);
        sampleFile = TEST_DIR.resolve("sample-10-lines.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= LINE_COUNT; i++) {
            sb.append("第").append(i).append("行内容\n");
        }
        Files.writeString(sampleFile, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 清理探针文件。
     *
     * <p>Windows 上杀毒软件/索引服务会短暂持有新建文件的句柄，导致
     * {@code FileSystemException: 另一个程序正在使用此文件}。重试几次仍删不掉则放弃：
     * 文件在 {@code target/} 下，{@code mvn clean} 会清掉，不得因此让用例失败。</p>
     */
    @AfterEach
    void cleanup() {
        deleteResiliently(sampleFile);
        deleteResiliently(TEST_DIR);
    }

    private static void deleteResiliently(Path path) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(80L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        System.err.println("[FileToolReadDefaultsTest] 清理失败（可能被占用，mvn clean 会清掉）: " + path);
    }

    /** 解析工具返回的 JSON。 */
    private static JsonNode parse(String json) throws IOException {
        JsonNode node = MAPPER.readTree(json);
        assertThat(node.has("error")).as("读取不应失败：%s", json).isFalse();
        return node;
    }

    private static int countLines(String content) {
        return content.isEmpty() ? 0 : content.split("\n", -1).length;
    }

    @Test
    @DisplayName("不传 limit：返回多行（AC8），total_lines 是文件真实总行数")
    void defaultLimitReadsWholeSmallFile() throws Exception {
        JsonNode result = parse(FileTool.readFile(Map.of("path", sampleFile.toString())));

        String content = result.path("content").asText();
        assertThat(countLines(content)).as("缺省 limit=1 时只会拿到第一行").isGreaterThan(1);
        assertThat(content).contains("第1行内容").contains("第" + LINE_COUNT + "行内容");

        assertThat(result.path("total_lines").asInt()).isEqualTo(LINE_COUNT);
        assertThat(result.path("limit").asInt()).as("本次实际返回行数").isEqualTo(LINE_COUNT);
        assertThat(result.path("offset").asInt()).isEqualTo(1);
        assertThat(result.path("truncated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("显式 limit 仍被尊重，且 total_lines 不再等于返回行数")
    void explicitLimitIsHonoredAndTotalLinesIsReal() throws Exception {
        JsonNode result = parse(FileTool.readFile(Map.of(
                "path", sampleFile.toString(),
                "limit", 3)));

        assertThat(countLines(result.path("content").asText())).isEqualTo(3);
        assertThat(result.path("limit").asInt()).isEqualTo(3);
        assertThat(result.path("total_lines").asInt())
                .as("total_lines 必须是文件真实行数，否则模型无从翻页")
                .isEqualTo(LINE_COUNT)
                .isNotEqualTo(3);
        assertThat(result.path("truncated").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("offset 搭配缺省 limit：从指定行读到文件末尾")
    void offsetWithDefaultLimitReadsToEof() throws Exception {
        JsonNode result = parse(FileTool.readFile(Map.of(
                "path", sampleFile.toString(),
                "offset", 8)));

        String content = result.path("content").asText();
        assertThat(countLines(content)).isEqualTo(3);
        assertThat(content).contains("第8行内容").contains("第10行内容").doesNotContain("第7行内容");
        assertThat(result.path("total_lines").asInt()).isEqualTo(LINE_COUNT);
        assertThat(result.path("truncated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("字符串形式的 limit 被容错解析（模型常把数字写成字符串）")
    void stringTypedLimitIsTolerated() throws Exception {
        JsonNode result = parse(FileTool.readFile(Map.of(
                "path", sampleFile.toString(),
                "limit", "4")));

        assertThat(countLines(result.path("content").asText())).isEqualTo(4);
        assertThat(result.path("total_lines").asInt()).isEqualTo(LINE_COUNT);
    }

    @Test
    @DisplayName("非法的 limit 字符串回退默认值而非抛异常")
    void invalidLimitFallsBackToDefault() throws Exception {
        JsonNode result = parse(FileTool.readFile(Map.of(
                "path", sampleFile.toString(),
                "limit", "not-a-number")));

        assertThat(countLines(result.path("content").asText())).isEqualTo(LINE_COUNT);
    }

    @Test
    @DisplayName("offset 超出文件末尾：返回空内容但不报错，total_lines 仍真实")
    void offsetBeyondEofReturnsEmptyGracefully() throws Exception {
        JsonNode result = parse(FileTool.readFile(Map.of(
                "path", sampleFile.toString(),
                "offset", 999)));

        assertThat(result.path("content").asText()).isEmpty();
        assertThat(result.path("total_lines").asInt())
                .as("即使本次没读到内容，也要让模型知道文件真实有多长")
                .isEqualTo(LINE_COUNT);
        assertThat(result.path("truncated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("缺省 limit 有上限常量约束，不得退回 1 行")
    void defaultLimitConstantIsSane() {
        assertThat(FileTool.DEFAULT_READ_LIMIT)
                .as("缺省读取行数必须远大于 1，否则模型要反复试探")
                .isGreaterThan(100);
    }
}
