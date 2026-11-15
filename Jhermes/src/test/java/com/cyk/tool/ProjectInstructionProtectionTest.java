package com.cyk.tool;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectInstructionProtectionTest {
    @Test void toolsCannotReadWriteOrGrepFixedRulesEvenFromDirectorySearch() throws Exception {
        Path target = Path.of(System.getProperty("user.dir"), "target", "instruction-runtime");
        Files.createDirectories(target);
        Path folder = Files.createTempDirectory(target, "protected-unit-");
        Path rules = Files.writeString(folder.resolve("JHERMES.md"), "RULE_ONLY_MARKER");
        assertThat(FileTool.readFile(Map.of("path", rules.toString()))).contains("安全策略拦截");
        assertThat(FileTool.writeFile(Map.of("path", rules.toString(), "content", "REPLACED"))).contains("安全策略拦截");
        assertThat(Files.readString(rules)).isEqualTo("RULE_ONLY_MARKER");
        var grep = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                FileTool.grepFiles(Map.of("path", folder.toString(), "pattern", "RULE_ONLY_MARKER")));
        assertThat(grep.path("count").asInt(-1)).isZero();
        assertThat(FileTool.searchFiles(Map.of("path", folder.toString(), "pattern", "*.md")))
                .doesNotContain("JHERMES.md");
        assertThat(TerminalTool.runCommand(Map.of("command", "Get-Content JHERMES.md", "workdir", folder.toString())))
                .contains("安全策略拦截");
    }
}
