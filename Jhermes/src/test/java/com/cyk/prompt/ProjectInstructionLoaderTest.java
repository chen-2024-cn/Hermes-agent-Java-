package com.cyk.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectInstructionLoaderTest {
    @TempDir Path directory;

    private ProjectInstructionLoader loader(Path root) throws Exception {
        Path home = directory.resolve("personal");
        Files.createDirectories(home);
        return new ProjectInstructionLoader(root, home);
    }

    @Test void discoversNearestGitRootAndStopsAtNestedRepository() throws Exception {
        Files.createDirectory(directory.resolve(".git"));
        Path nested = Files.createDirectories(directory.resolve("nested/src/deeper"));
        assertThat(ProjectInstructionLoader.discoverRoot(nested)).isEqualTo(directory.toRealPath());
        Files.writeString(directory.resolve("nested/.git"), "gitdir: ignored");
        assertThat(ProjectInstructionLoader.discoverRoot(nested)).isEqualTo(directory.resolve("nested").toRealPath());
    }

    @Test void withoutGitDoesNotLoadParentRulesOrOtherMarkdown() throws Exception {
        Path child = Files.createDirectory(directory.resolve("child"));
        Files.writeString(directory.resolve("JHERMES.md"), "PARENT");
        Files.writeString(child.resolve("CLAUDE.md"), "OTHER");
        Files.writeString(child.resolve("README.md"), "OTHER");
        assertThat(loader(child).load().documents()).extracting(ProjectInstructions.Document::source).containsExactly("CLAUDE.md");
        assertThat(ProjectInstructionLoader.discoverRoot(child)).isEqualTo(child.toRealPath());
    }

    @Test void primaryWinsWithoutCombiningFallback() throws Exception {
        Files.writeString(directory.resolve("JHERMES.md"), "PRIMARY");
        Files.writeString(directory.resolve("AGENTS.md"), "FALLBACK");
        var snapshot = loader(directory).load();
        assertThat(snapshot.documents()).hasSize(2);
        assertThat(snapshot.documents()).extracting(ProjectInstructions.Document::source).containsExactly("AGENTS.md", "JHERMES.md");
        assertThat(snapshot.renderPrompt()).contains("PRIMARY", "FALLBACK");
        assertThat(snapshot.renderPrompt().indexOf("FALLBACK")).isLessThan(snapshot.renderPrompt().indexOf("PRIMARY"));
    }

    @Test void fallbackIsUsedOnlyWhenPrimaryIsAbsent() throws Exception {
        Files.writeString(directory.resolve("AGENTS.md"), "FALLBACK");
        assertThat(loader(directory).load().documents().getFirst().text()).isEqualTo("FALLBACK");
        Files.writeString(directory.resolve("JHERMES.md"), "");
        assertThat(loader(directory).load().documents().getLast().status()).contains("空文件");
    }

    @Test void personalPreferencesAreSeparateAndAutomaticallyAdopted() throws Exception {
        var loader = loader(directory);
        Files.writeString(directory.resolve("personal/JHERMES.md"), "PERSONAL");
        Files.writeString(directory.resolve("JHERMES.md"), "PROJECT");
        Files.writeString(directory.resolve("JHERMES.local.md"), "LOCAL");
        var snapshot = loader.load();
        assertThat(snapshot.documents()).extracting(ProjectInstructions.Document::scope)
                .containsExactly("个人偏好", "项目个人补充", "最高优先级项目约定");
        assertThat(snapshot.renderPrompt()).contains("PERSONAL", "PROJECT", "LOCAL");
    }

    @Test void normalizesBomAndWindowsNewlinesWithoutExpandingReferences() throws Exception {
        Files.writeString(directory.resolve("JHERMES.md"), "﻿first\r\n@secret.txt\r\nhttps://example.invalid\r");
        var document = loader(directory).load().documents().getFirst();
        assertThat(document.text()).isEqualTo("first\n@secret.txt\nhttps://example.invalid");
        assertThat(document.status()).contains("已自动加载");
    }

    @Test void rejectsOversizedFilesWithoutPartialInjection() throws Exception {
        Files.writeString(directory.resolve("JHERMES.md"), "x".repeat(ProjectInstructionLoader.MAX_FILE_CHARS + 1));
        var snapshot = loader(directory).load();
        assertThat(snapshot.documents().getFirst().status()).contains("字符");
        assertThat(snapshot.documents().getFirst().text()).isEmpty();
        Files.writeString(directory.resolve("JHERMES.md"), "x".repeat(ProjectInstructionLoader.MAX_FILE_BYTES + 1));
        assertThat(loader(directory).load().documents().getFirst().status()).contains("字节");
    }

    @Test void totalBudgetRejectsWholeDocumentAndPreservesEarlierLayers() throws Exception {
        var loader = loader(directory);
        Files.writeString(directory.resolve("personal/JHERMES.md"), "P".repeat(6500));
        Files.writeString(directory.resolve("JHERMES.md"), "R".repeat(6500));
        var snapshot = loader.load();
        assertThat(snapshot.documents().getFirst().status()).contains("总预算");
        assertThat(snapshot.renderPrompt()).contains("R".repeat(100)).doesNotContain("P".repeat(100));
        assertThat(snapshot.renderPrompt().length()).isLessThanOrEqualTo(ProjectInstructionLoader.MAX_TOTAL_CHARS);
    }

    @Test void rejectsMalformedUtf8AndTerminalControlCharacters() throws Exception {
        Files.write(directory.resolve("JHERMES.md"), new byte[]{(byte) 0xc3, 0x28});
        assertThat(loader(directory).load().documents().getFirst().status()).contains("UTF-8");
        Files.writeString(directory.resolve("JHERMES.md"), "text[31mFAKE");
        assertThat(loader(directory).load().documents().getFirst().status()).contains("控制字符");
    }

    @Test void knownSecretsAreRejectedWithoutEchoingTheirValue() throws Exception {
        Files.writeString(directory.resolve("JHERMES.md"), "api_key=secret-fixture-never-used");
        var document = loader(directory).load().documents().getFirst();
        assertThat(document.status()).contains("凭据").doesNotContain("secret-fixture");
        assertThat(document.text()).isEmpty();
    }

    @Test void directoryAtInstructionFilenameIsRejected() throws Exception {
        Files.createDirectory(directory.resolve("JHERMES.md"));
        assertThat(loader(directory).load().documents().getFirst().status()).contains("非普通文件");
    }

    @Test void symlinkCannotReadAnExternalTargetWhenSupported() throws Exception {
        Path root = Files.createDirectory(directory.resolve("project"));
        Path target = Files.writeString(directory.resolve("external.txt"), "MUST_NOT_LOAD");
        try { Files.createSymbolicLink(root.resolve("JHERMES.md"), target); }
        catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "平台未授权创建符号链接");
        }
        var snapshot = loader(root).load();
        assertThat(snapshot.documents().getFirst().text()).isEmpty();
        assertThat(snapshot.renderPrompt()).doesNotContain("MUST_NOT_LOAD");
    }

    @Test void initDoesNotOverwriteExistingPrimaryOrFallback() throws Exception {
        var loader = loader(directory);
        assertThat(loader.initialize()).contains("已创建");
        String original = Files.readString(directory.resolve("JHERMES.md"));
        assertThat(loader.initialize()).contains("未覆盖");
        assertThat(Files.readString(directory.resolve("JHERMES.md"))).isEqualTo(original);
        Path other = Files.createDirectory(directory.resolve("other"));
        Files.writeString(other.resolve("AGENTS.md"), "KEEP");
        assertThat(loader(other).initialize()).contains("未覆盖");
        assertThat(Files.exists(other.resolve("JHERMES.md"))).isFalse();
    }
}
