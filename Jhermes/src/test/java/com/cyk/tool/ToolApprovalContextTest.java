package com.cyk.tool;

import com.cyk.bean.ToolDefinition;
import com.cyk.config.HermesConfig;
import com.cyk.security.SecurityContext;
import com.cyk.ui.Ui;
import com.cyk.ui.UserInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ToolApprovalContextTest {
    private String previousDir;
    private Path directory;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @BeforeEach void init() throws Exception {
        previousDir = System.getProperty("user.dir");
        Path target = Path.of(previousDir, "target");
        Files.createDirectories(target);
        directory = Files.createTempDirectory(target, "approval-unit-");
        Path workspace = directory.resolve("workspace");
        Files.createDirectories(workspace);
        System.setProperty("user.dir", workspace.toString());
        Ui.setStreams(new PrintStream(output, true, StandardCharsets.UTF_8), null);
        Ui.setUserInput(new UserInput() {
            public String readLine(String prompt) {
                assertThat(output.toString(StandardCharsets.UTF_8)).contains("目的:", "预期结果:", "尚未发生");
                return "n";
            }
            public boolean isInteractive() { return true; }
            public boolean isEofReached() { return false; }
            public void close() { }
        });
        SecurityContext.init(new HermesConfig(null, Map.of("security", Map.of("audit_enabled", false))));
    }

    @AfterEach void reset() {
        System.setProperty("user.dir", previousDir);
        SecurityContext.resetForTest();
        TerminalTool.resetCommandCounts();
        Ui.resetForTest();
    }

    @Test void terminalHandlerPassesExplanationWithoutExecutingOnDenial() throws Exception {
        Path marker = directory.resolve("must-not-exist.txt");
        String result = TerminalTool.runCommand(Map.of("command", "Set-Content -Path '" + marker + "' -Value generated",
                "workdir", directory.toString(), "purpose", "保存本次测试报告", "expected_result", "生成一个报告文件"));
        assertThat(result).contains("用户拒绝");
        assertThat(Files.exists(marker)).isFalse();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("保存本次测试报告", "生成一个报告文件", directory.toString());
    }

    @Test void missingTerminalExplanationStillGetsAnHonestPrompt() {
        String result = TerminalTool.runCommand(Map.of("command", "unknown-test-operation", "workdir", directory.toString()));
        assertThat(result).contains("用户拒绝");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("未提供具体目的", "未提供预期结果");
    }

    @Test void fileHandlerSeparatesModelExpectationFromKnownOverwriteEffect() throws Exception {
        Path file = directory.resolve("existing.txt");
        Files.writeString(file, "ORIGINAL");
        String result = FileTool.writeFile(Map.of("path", file.toString(), "content", "REPLACEMENT",
                "purpose", "更新报告", "expected_result", "保存新报告"));
        assertThat(result).contains("用户拒绝");
        assertThat(Files.readString(file)).isEqualTo("ORIGINAL");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("更新报告", "保存新报告", "覆盖已有文件", "原内容将被替换")
                .doesNotContain("REPLACEMENT");
    }

    @Test void fileHandlerExplainsAppendAndCreateWithoutInventingTaskPurpose() throws Exception {
        Path file = directory.resolve("existing.txt");
        Files.writeString(file, "ORIGINAL");
        assertThat(FileTool.writeFile(Map.of("path", file.toString(), "content", "NEXT", "append", true))).contains("用户拒绝");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("追加内容");
        output.reset();
        Path fresh = directory.resolve("new.txt");
        assertThat(FileTool.writeFile(Map.of("path", fresh.toString(), "content", "NEXT"))).contains("用户拒绝");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("新建文件");
        assertThat(Files.exists(fresh)).isFalse();
    }

    @Test void skillHandlerUsesDeclarationWithoutChangingBusinessArguments() throws Exception {
        Path script = directory.resolve("unused.cmd");
        Files.writeString(script, "@echo off\r\necho generated\r\n");
        ToolDefinition definition = new ToolDefinition("report", "生成本地报告", Map.of(), "unused.cmd");
        var method = SkillTool.class.getDeclaredMethod("createScriptHandler", Path.class, ToolDefinition.class, String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Function<Map<String, Object>, String> handler = (Function<Map<String, Object>, String>) method.invoke(null, directory, definition, "test__report");
        assertThat(handler.apply(Map.of("business_option", "original"))).contains("用户拒绝");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("生成本地报告", "取决于脚本", "技能声明", "sha256");
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("business_option");
    }

    @Test void schemasDescribeOptionalMetadataButPreserveExistingRequiredArguments() {
        ToolRegistry registry = new ToolRegistry();
        TerminalTool.register(registry);
        FileTool.register(registry);
        for (var tool : registry.getDefinitions(new java.util.HashSet<>(List.of("run_command", "write_file")))) {
            var function = (Map<?, ?>) tool.get("function");
            var parameters = (Map<?, ?>) function.get("parameters");
            var properties = (Map<?, ?>) parameters.get("properties");
            assertThat(properties.keySet().stream().map(Object::toString).toList()).contains("purpose", "expected_result");
            assertThat(((List<?>) parameters.get("required")).stream().map(Object::toString).toList())
                    .doesNotContain("purpose", "expected_result");
        }
    }
}
