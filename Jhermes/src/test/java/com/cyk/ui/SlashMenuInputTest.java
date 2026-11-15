package com.cyk.ui;

import com.cyk.command.slash.SlashCommandRegistry;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SlashMenuInputTest {
    private Terminal terminal(String keys, ByteArrayOutputStream output) throws Exception {
        Terminal terminal = TerminalBuilder.builder().system(false).type("xterm").paused(true)
                .size(new Size(100, 35)).encoding(StandardCharsets.UTF_8)
                .streams(new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)), output).build();
        var attributes = terminal.getAttributes();
        attributes.setInputFlag(Attributes.InputFlag.ICRNL, false);
        attributes.setInputFlag(Attributes.InputFlag.INLCR, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        terminal.setAttributes(attributes); terminal.resume(); return terminal;
    }

    @Test void firstEnterCompletesAndSecondEnterSubmitsSelectedCommand() throws Exception {
        var output = new ByteArrayOutputStream();
        try (Terminal terminal = terminal("/se\r\rNEXT\r", output)) {
            var input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            input.configureCommands(SlashCommandRegistry.withBuiltins().menuChoices());
            assertThat(input.readMessage("你: ", new InputDraft())).isEqualTo("/sessions");
            assertThat(output.toString(StandardCharsets.UTF_8)).contains("选择", "sessions");
            output.reset();
            assertThat(input.readMessage("你: ", new InputDraft())).isEqualTo("NEXT");
            assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("填入");
        }
    }

    @Test void arrowsSelectMenuWhileTypedExactCommandStillSubmits() throws Exception {
        var output = new ByteArrayOutputStream();
        try (Terminal terminal = terminal("/res\033[B\rID\r/help\r", output)) {
            var input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            input.configureCommands(SlashCommandRegistry.withBuiltins().menuChoices());
            assertThat(input.readMessage("你: ", new InputDraft())).isEqualTo("/resume ID");
            assertThat(input.readMessage("你: ", new InputDraft())).isEqualTo("/help");
        }
    }

    @Test void pastedSlashIsDataAndApprovalReadNeverShowsMenu() throws Exception {
        var output = new ByteArrayOutputStream();
        try (Terminal terminal = terminal("\033[200~/sessions\n/exit\033[201~\r/help\r", output)) {
            var input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            input.configureCommands(SlashCommandRegistry.withBuiltins().menuChoices());
            String submitted = input.readMessage("你: ", new InputDraft());
            assertThat(submitted).isEqualTo("/sessions\n/exit");
            assertThat(input.commandText(submitted)).isEmpty();
            assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("填入");
            output.reset();
            assertThat(input.readLine("允许? ")).isEqualTo("/help");
            assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("填入");
        }
    }
}
