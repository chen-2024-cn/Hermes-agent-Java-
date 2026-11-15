package com.cyk.ui;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JLineUserInputTest {
    private Terminal terminal(String input) throws Exception {
        Terminal terminal = TerminalBuilder.builder().system(false).type("xterm").paused(true)
                .streams(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream())
                .encoding(StandardCharsets.UTF_8).build();
        // Feed bytes only after raw attributes are installed, like a real line editor.
        var attributes = terminal.getAttributes();
        attributes.setInputFlag(org.jline.terminal.Attributes.InputFlag.ICRNL, false);
        attributes.setInputFlag(org.jline.terminal.Attributes.InputFlag.INLCR, false);
        attributes.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ISIG, false);
        attributes.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ICANON, false);
        attributes.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ECHO, false);
        terminal.setAttributes(attributes);
        terminal.resume();
        return terminal;
    }

    @Test void pastedTextIsCollapsedInOutputButSubmittedInFull() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder().system(false).type("xterm").paused(true)
                .streams(new ByteArrayInputStream("前\033[200~PRIVATE_ONE\nPRIVATE_TWO\033[201~后\r".getBytes(StandardCharsets.UTF_8)), output)
                .encoding(StandardCharsets.UTF_8).build();
        var attributes = terminal.getAttributes();
        attributes.setInputFlag(org.jline.terminal.Attributes.InputFlag.ICRNL, false);
        attributes.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ISIG, false);
        attributes.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ICANON, false);
        attributes.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ECHO, false);
        terminal.setAttributes(attributes);
        terminal.resume();
        try (terminal) {
            JLineUserInput input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            assertThat(input.readMessage("", new InputDraft())).isEqualTo("前PRIVATE_ONE\nPRIVATE_TWO后");
            assertThat(output.toString(StandardCharsets.UTF_8)).contains("已粘贴 2 行").doesNotContain("PRIVATE_ONE", "PRIVATE_TWO");
        }
    }

    @Test void backspaceRemovesEntirePasteBlock() throws Exception {
        try (Terminal terminal = terminal("前\033[200~删除\n第二行\033[201~\177后\r")) {
            JLineUserInput input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            assertThat(input.readMessage("", new InputDraft())).isEqualTo("前后");
        }
    }

    @Test void bracketedPasteIsOneMessageAndNextPromptHasNoTail() throws Exception {
        try (Terminal terminal = terminal("\033[200~第一行\r\n\r\n第二行\n\033[201~\r后续问题\r")) {
            JLineUserInput input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            assertThat(input.readMessage("", new InputDraft())).isEqualTo("第一行\n\n第二行\n");
            assertThat(input.readMessage("", new InputDraft())).isEqualTo("后续问题");
        }
    }

    @Test void pasteDoesNotSubmitBeforeIndependentEnter() throws Exception {
        try (Terminal terminal = terminal("\033[200~一\n二\033[201~\003下一条\r")) {
            JLineUserInput input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            InputDraft draft = new InputDraft();
            assertThat(input.readMessage("", draft)).isEmpty(); // Ctrl+C cancels, paste alone did not return a message.
            assertThat(input.readMessage("", draft)).isEqualTo("下一条");
        }
    }

    @Test void approvalCannotComeFromPastedYesOrTailLines() throws Exception {
        try (Terminal terminal = terminal("\033[200~yes\ny\033[201~\rno\r")) {
            JLineUserInput input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            assertThat(input.readLine("确认> ")).isEmpty();
            assertThat(input.readLine("确认> ")).isEqualTo("no");
        }
    }

    @Test void altEnterAddsNewlineWithoutSubmitting() throws Exception {
        try (Terminal terminal = terminal("一\033\r二\r")) {
            JLineUserInput input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            assertThat(input.readMessage("", new InputDraft())).isEqualTo("一\n二");
        }
    }

    @Test void explicitClipboardPasteUsesDraftAndDoesNotLoseText() throws Exception {
        try (Terminal terminal = terminal("前\033v后\r")) {
            JLineUserInput input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("一\r\n二", null));
            assertThat(input.readMessage("", new InputDraft())).isEqualTo("前一\n二后");
        }
    }
}
