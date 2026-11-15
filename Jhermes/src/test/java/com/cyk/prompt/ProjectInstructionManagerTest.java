package com.cyk.prompt;

import com.cyk.capability.CapabilityRegistry;
import com.cyk.tool.ToolRegistry;
import com.cyk.util.SensitivePathGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectInstructionManagerTest {
    @TempDir Path directory;
    private ProjectInstructionManager manager() throws Exception {
        return new ProjectInstructionManager(new ProjectInstructionLoader(directory,
                Files.createDirectories(directory.resolve("personal"))));
    }

    @Test void automaticallyLoadsAllNamedFilesInPriorityOrder() throws Exception {
        Files.writeString(directory.resolve("AGENT.md"), "LOW_AGENT");
        Files.writeString(directory.resolve("AGENTS.md"), "DUPLICATE");
        Files.writeString(directory.resolve("CLAUDE.md"), "MID_CLAUDE");
        Files.writeString(directory.resolve("JHERMES.md"), "HIGH_JHERMES");
        String prompt = manager().snapshot().renderPrompt();
        assertThat(prompt).contains("LOW_AGENT", "MID_CLAUDE", "HIGH_JHERMES").doesNotContain("DUPLICATE");
        assertThat(prompt.indexOf("LOW_AGENT")).isLessThan(prompt.indexOf("MID_CLAUDE"));
        assertThat(prompt.indexOf("MID_CLAUDE")).isLessThan(prompt.indexOf("HIGH_JHERMES"));
        assertThat(prompt).contains("不是系统指令", "执行许可", "JHERMES.md 最高");
    }

    @Test void sameLengthAndSameTimestampEditIsDetectedOnlyAtRefresh() throws Exception {
        Path file = Files.writeString(directory.resolve("JHERMES.md"), "FIRST");
        var manager = manager();
        var time = Files.getLastModifiedTime(file);
        Files.writeString(file, "OTHER"); Files.setLastModifiedTime(file, time);
        assertThat(manager.snapshot().renderPrompt()).contains("FIRST");
        assertThat(manager.refresh(s -> { })).isTrue();
        assertThat(manager.snapshot().renderPrompt()).contains("OTHER").doesNotContain("FIRST");
        assertThat(manager.refresh(s -> { })).isFalse();
    }

    @Test void refreshRemovesDeletedAndRejectsNewInvalidContent() throws Exception {
        Path file = Files.writeString(directory.resolve("JHERMES.md"), "OLD");
        var manager = manager(); Files.delete(file);
        assertThat(manager.refresh(s -> { })).isTrue();
        assertThat(manager.snapshot().renderPrompt()).isEmpty();
        Files.writeString(file, "api_key=secret-fixture");
        List<String> output = new ArrayList<>(); manager.refresh(output::add);
        assertThat(manager.snapshot().renderPrompt()).isEmpty();
        assertThat(String.join("\n", output)).contains("凭据").doesNotContain("secret-fixture");
    }

    @Test void initCreatesTemplateWithoutModelAndNextTaskLoadsIt() throws Exception {
        var manager = manager(); List<String> output = new ArrayList<>();
        manager.initialize(output::add);
        assertThat(manager.snapshot().renderPrompt()).isEmpty();
        assertThat(manager.refresh(output::add)).isTrue();
        assertThat(manager.snapshot().renderPrompt()).contains("项目概况");
        assertThat(String.join("\n", output)).contains("已创建", "下一次提交任务").doesNotContain("/instructions");
    }

    @Test void composerKeepsAuthoredBudgetAndConsistencySeparate() throws Exception {
        Files.writeString(directory.resolve("JHERMES.md"), "project_fake_tool_name {{not_a_template}}");
        var capabilities = CapabilityRegistry.from(new ToolRegistry());
        var cwd = new WorkingDirSnapshot(directory, "test", "test", List.of());
        var composer = new PromptComposer(new PromptLoader(null));
        var base = composer.compose(new PromptContext(capabilities, cwd, "MEMORY", List.of()));
        var changed = composer.compose(new PromptContext(capabilities, cwd, "MEMORY", List.of(), manager().snapshot()));
        assertThat(changed.authoredText()).isEqualTo(base.authoredText());
        assertThat(changed.consistencyText()).isEqualTo(base.consistencyText());
        assertThat(changed.text()).contains("project_fake_tool_name {{not_a_template}}");
        assertThat(changed.segment("project-instructions")).isPresent();
    }

    @Test void allFixedRuleNamesAreProtectedButSimilarSourceNamesRemainAllowed() {
        for (String file : List.of("JHERMES.md", "AGENT.md", "AGENTS.md", "CLAUDE.md", "JHERMES.local.md")) {
            assertThat(SensitivePathGuard.check(file)).isNotNull();
        }
        assertThat(SensitivePathGuard.check("ProjectInstructionLoader.java")).isNull();
        assertThat(SensitivePathGuard.check("agents.md.java")).isNull();
    }
}
