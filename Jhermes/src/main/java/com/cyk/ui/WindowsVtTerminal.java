package com.cyk.ui;

import org.jline.nativ.Kernel32;
import org.jline.terminal.Attributes;
import org.jline.terminal.Cursor;
import org.jline.terminal.Size;
import org.jline.terminal.impl.ExternalTerminal;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

/** Windows VT byte input preserves bracketed paste, unlike translating individual Win32 key records. */
final class WindowsVtTerminal extends ExternalTerminal {
    private static final int VT_INPUT = 0x0200;
    private static final int VT_OUTPUT = 0x0004;
    private final long inputHandle;
    private final long outputHandle;
    private final int inputMode;
    private final int outputMode;
    private boolean restored;

    static WindowsVtTerminal create() throws IOException {
        long in = Kernel32.GetStdHandle(Kernel32.STD_INPUT_HANDLE);
        long out = Kernel32.GetStdHandle(Kernel32.STD_OUTPUT_HANDLE);
        int[] inputMode = new int[1];
        int[] outputMode = new int[1];
        if (Kernel32.GetConsoleMode(in, inputMode) == 0 || Kernel32.GetConsoleMode(out, outputMode) == 0) {
            throw new IOException("不是 Windows 控制台");
        }
        // Disable cooked/echo/processed input and request the VT sequences from modern conhost/ConPTY.
        if (Kernel32.SetConsoleMode(in, (inputMode[0] & ~0x0007) | VT_INPUT) == 0) {
            throw new IOException("控制台不支持 VT 输入");
        }
        if (Kernel32.SetConsoleMode(out, outputMode[0] | VT_OUTPUT) == 0) {
            Kernel32.SetConsoleMode(in, inputMode[0]);
            throw new IOException("控制台不支持 VT 输出");
        }
        try {
            return new WindowsVtTerminal(in, out, inputMode[0], outputMode[0]);
        } catch (Exception e) {
            Kernel32.SetConsoleMode(in, inputMode[0]);
            Kernel32.SetConsoleMode(out, outputMode[0]);
            throw e;
        }
    }

    private WindowsVtTerminal(long in, long out, int inMode, int outMode) throws IOException {
        super(null, "Jhermes", "xterm-256color", new FilterInputStream(System.in) {
            @Override public void close() { }
        }, new FilterOutputStream(Ui.out()) {
            @Override public void close() throws IOException { flush(); }
        }, StandardCharsets.UTF_8, SignalHandler.SIG_DFL, true);
        inputHandle = in;
        outputHandle = out;
        inputMode = inMode;
        outputMode = outMode;
        Attributes attributes = getAttributes();
        attributes.setInputFlag(Attributes.InputFlag.ICRNL, false);
        attributes.setInputFlag(Attributes.InputFlag.INLCR, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        setAttributes(attributes);
        Runtime.getRuntime().addShutdownHook(new Thread(this::restoreModes, "jhermes-terminal-restore"));
        resume();
    }

    @Override public Size getSize() {
        Kernel32.CONSOLE_SCREEN_BUFFER_INFO info = new Kernel32.CONSOLE_SCREEN_BUFFER_INFO();
        return Kernel32.GetConsoleScreenBufferInfo(outputHandle, info) == 0 ? super.getSize()
                : new Size(info.windowWidth(), info.windowHeight());
    }

    @Override public Cursor getCursorPosition(IntConsumer discarded) {
        Kernel32.CONSOLE_SCREEN_BUFFER_INFO info = new Kernel32.CONSOLE_SCREEN_BUFFER_INFO();
        if (Kernel32.GetConsoleScreenBufferInfo(outputHandle, info) == 0) return null;
        return new Cursor(info.cursorPosition.x, info.cursorPosition.y);
    }

    synchronized void restoreModes() {
        if (!restored) {
            restored = true;
            Kernel32.SetConsoleMode(inputHandle, inputMode);
            Kernel32.SetConsoleMode(outputHandle, outputMode);
        }
    }
}
