package com.cyk.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class InputDraftTest {
    @TempDir Path directory;
    static byte[] png() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }
    @Test void validatesActualFormatInsteadOfExtension() throws Exception {
        Path file = directory.resolve("wrong.jpg");
        Files.write(file, png());
        ImageAttachment image = ImageAttachment.fromFile(file);
        assertThat(image.dataUrl()).startsWith("data:image/png;base64,");
        assertThat(image.width()).isEqualTo(2);
        assertThat(image.height()).isEqualTo(3);
        assertThat(image.toString()).doesNotContain("base64");
    }
    @Test void invalidAndOversizedFilesAreRejected() {
        assertThatThrownBy(() -> ImageAttachment.fromBytes("bad", new byte[]{1, 2, 3})).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> ImageAttachment.fromBytes("large", new byte[ImageAttachment.MAX_BYTES + 1]))
                .isInstanceOf(java.io.IOException.class);
    }
    @Test void attachmentsCanBeRemovedAndLimitCannotBeRaced() throws Exception {
        InputDraft draft = new InputDraft();
        ImageAttachment image = ImageAttachment.fromBytes("x", png());
        for (int i = 0; i < 4; i++) draft.add(image);
        assertThatThrownBy(() -> draft.add(image)).isInstanceOf(IllegalArgumentException.class);
        draft.remove(1);
        assertThat(draft.images()).hasSize(3);
        draft.clear();
        assertThat(draft.images()).isEmpty();
    }
    @Test void clipboardCommandOnlyAddsDraftAndFailurePreservesExistingText() throws Exception {
        InputDraft draft = new InputDraft();
        draft.text("原文");
        UserInput input = new UserInput() {
            public String readLine(String prompt) { return null; }
            public boolean isInteractive() { return true; }
            public boolean isEofReached() { return false; }
            public void close() { }
        };
        List<String> output = new ArrayList<>();
        new InputCommands(draft, input, () -> new ClipboardReader.Content("\r\n第二行", null), output::add).execute("/paste");
        assertThat(draft.text()).isEqualTo("原文\n第二行");
        new InputCommands(draft, input, () -> { throw new java.io.IOException("失败"); }, output::add).execute("/paste");
        assertThat(draft.text()).isEqualTo("原文\n第二行");
        assertThat(output).anyMatch(line -> line.contains("尚未发送"));
    }
    @Test void pastedBlocksExpandInOrderAndDisplayOnlyLineCounts() {
        InputDraft draft = new InputDraft();
        draft.text("说明:");
        draft.pasteText("甲\r\n\r\n乙\n");
        draft.pasteText("尾部");
        assertThat(draft.text()).isEqualTo("说明:甲\n\n乙\n尾部");
        assertThat(draft.displayText()).contains("已粘贴 4 行", "已粘贴 1 行").doesNotContain("甲", "乙", "尾部");
    }
    @Test void placeholderCannotBeForgedByTypedText() {
        InputDraft draft = new InputDraft();
        draft.text("[已粘贴 2 行 #1]");
        assertThat(draft.text()).isEqualTo("[已粘贴 2 行 #1]");
        assertThat(draft.hasBlocks()).isFalse();
    }
    @Test void partialDeleteRemovesTheWholeBlockAndKeepsSurroundingText() {
        InputDraft draft = new InputDraft();
        draft.text("前");
        draft.pasteText("隐藏\n内容");
        String before = draft.displayText();
        draft.edit(before, before.substring(0, before.length() - 1), before.length() - 1);
        assertThat(draft.text()).isEqualTo("前");
        assertThat(draft.displayText()).isEqualTo("前");
        assertThat(draft.hasBlocks()).isFalse();
    }
    @Test void quotedPathWithSpacesIsParsedAsOnePath() {
        assertThat(InputCommands.parsePath("\"my pictures/a.png\"")).isEqualTo("my pictures/a.png");
        assertThatThrownBy(() -> InputCommands.parsePath("\"missing")).isInstanceOf(IllegalArgumentException.class);
    }
}
