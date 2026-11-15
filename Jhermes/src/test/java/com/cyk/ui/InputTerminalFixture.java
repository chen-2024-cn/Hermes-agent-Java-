package com.cyk.ui;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.LineDisciplineTerminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Deterministic terminal input without native PTY pumps racing scripted input against EOF. */
final class InputTerminalFixture {
    private InputTerminalFixture() { }

    static Terminal terminal(String keys, ByteArrayOutputStream output, int columns, int rows) throws IOException {
        var terminal = new LineDisciplineTerminal("input-test", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(columns, rows));
        var attributes = terminal.getAttributes();
        attributes.setInputFlag(Attributes.InputFlag.ICRNL, false);
        attributes.setInputFlag(Attributes.InputFlag.INLCR, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        terminal.setAttributes(attributes);
        terminal.processInputBytes(keys.getBytes(StandardCharsets.UTF_8));
        return terminal;
    }
}
