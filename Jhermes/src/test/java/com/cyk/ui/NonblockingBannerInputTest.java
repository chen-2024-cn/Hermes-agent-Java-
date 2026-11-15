package com.cyk.ui;

import com.cyk.util.BannerAnimation;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class NonblockingBannerInputTest {
    private Terminal terminal(String keys, ByteArrayOutputStream output) throws Exception {
        Terminal terminal = TerminalBuilder.builder().system(false).type("xterm").paused(true)
                .size(new Size(80, 35)).encoding(StandardCharsets.UTF_8)
                .streams(new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)), output).build();
        var attributes = terminal.getAttributes();
        attributes.setInputFlag(Attributes.InputFlag.ICRNL, false);
        attributes.setInputFlag(Attributes.InputFlag.INLCR, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        terminal.setAttributes(attributes);
        terminal.resume();
        return terminal;
    }

    private BannerAnimation.Presentation presentation(AtomicLong clock) {
        return new BannerAnimation.Presentation("2.2.0", false, List.of("模型: local-test"), clock::get);
    }

    @Test void firstMessageSubmitsImmediatelyAndOnlyOnePromptOwnsTheBanner() throws Exception {
        var output = new ByteArrayOutputStream();
        var clock = new AtomicLong();
        var view = presentation(clock);
        try (Terminal terminal = terminal("第一条\r第二条\r", output)) {
            var input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            input.prepareStartup(view);
            assertThat(input.appendStartupLine("Enter 发送")).isTrue();
            long started = System.nanoTime();
            assertThat(input.readMessage("你: ", new InputDraft())).isEqualTo("第一条");
            assertThat(System.nanoTime() - started).isLessThan(2_000_000_000L);
            assertThat(output.toString(StandardCharsets.UTF_8)).contains("Self-Evolving AI Agent", "模型: local-test", "你: ");
            assertThat(view.isAnimating()).isFalse();
            output.reset();
            clock.set(12_000_000_000L);
            assertThat(input.appendStartupLine("should not appear")).isFalse();
            assertThat(input.readMessage("你: ", new InputDraft())).isEqualTo("第二条");
            assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("Self-Evolving", "local-test", "should not appear");
        }
    }

    @Test void arrowEditingAndPastedBlocksKeepTheirExactContentWithTheHeader() throws Exception {
        var output = new ByteArrayOutputStream();
        try (Terminal terminal = terminal("ab\033ODZ\033OC\033[200~粘贴\n第二行\033[201~\033\r尾\r", output)) {
            var input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            input.prepareStartup(presentation(new AtomicLong()));
            assertThat(input.readMessage("你: ", new InputDraft())).isEqualTo("aZb粘贴\n第二行\n尾");
            assertThat(output.toString(StandardCharsets.UTF_8)).contains("已粘贴 2 行").doesNotContain("粘贴\n第二行");
        }
    }

    @Test void cancelStopsAnimationAndDoesNotTransferTheHeaderToNextInput() throws Exception {
        var output = new ByteArrayOutputStream();
        var view = presentation(new AtomicLong());
        try (Terminal terminal = terminal("not-sent\003next\r", output)) {
            var input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            input.prepareStartup(view);
            InputDraft draft = new InputDraft();
            assertThat(input.readMessage("你: ", draft)).isEmpty();
            assertThat(draft.text()).isEmpty();
            assertThat(view.isAnimating()).isFalse();
            output.reset();
            assertThat(input.readMessage("你: ", draft)).isEqualTo("next");
            assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("Self-Evolving");
        }
    }

    @Test void eofAndApprovalInputDiscardPendingStartupPresentation() throws Exception {
        var output = new ByteArrayOutputStream();
        try (Terminal terminal = terminal("\004", output)) {
            var input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            var view = presentation(new AtomicLong());
            input.prepareStartup(view);
            assertThat(input.readMessage("你: ", new InputDraft())).isNull();
            assertThat(view.isAnimating()).isFalse();
        }
        output.reset();
        try (Terminal terminal = terminal("n\r", output)) {
            var input = new JLineUserInput(terminal, () -> new ClipboardReader.Content("", null));
            input.prepareStartup(presentation(new AtomicLong()));
            assertThat(input.readLine("允许? ")).isEqualTo("n");
            assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("Self-Evolving", "local-test");
        }
    }
}
