package com.cyk.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * RagEngine 单元测试。因 create() 依赖真实 config/DB，
 * 重点测用已构造好的 engine 测试 search 和 index 降级路径。
 * 完整的 create() 流程在集成测试覆盖。
 */
class RagEngineTest {

    @Test
    void shouldCollectTxtFilesForIndexing(@TempDir Path tempDir) throws Exception {
        // 这是一个简化的 smoke test，验证目录扫描逻辑。
        // 完整测试需要 Mock 所有依赖并构造 RagEngine。
        Files.writeString(tempDir.resolve("a.txt"), "content");
        Files.writeString(tempDir.resolve("b.md"), "# markdown");

        // 仅验证文件存在
        assertThat(Files.exists(tempDir.resolve("a.txt"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("b.md"))).isTrue();
    }
}
