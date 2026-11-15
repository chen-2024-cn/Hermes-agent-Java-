package com.cyk.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Scoped explicit-paste listener. Never records other keys or watches clipboard contents while idle. */
final class WindowsPasteBridge implements AutoCloseable {
    interface ConsoleApi extends StdCallLibrary {
        ConsoleApi INSTANCE = Native.load("kernel32", ConsoleApi.class);
        int GetConsoleTitleW(char[] title, int capacity);
        boolean SetConsoleTitleW(WString title);
    }
    // Clipboard sequence number belongs to user32, not kernel32.
    interface ClipboardApi extends StdCallLibrary {
        ClipboardApi INSTANCE = Native.load("user32", ClipboardApi.class);
        int GetClipboardSequenceNumber();
    }
    record ImageEvent(long gesture, ImageAttachment image, String error) { }
    private final User32 user = User32.INSTANCE;
    private final ClipboardReader clipboard;
    private final Consumer<ImageEvent> listener;
    private final String token = "Jhermes input " + UUID.randomUUID();
    private final String previousTitle;
    private final ScheduledExecutorService work = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "jhermes-paste-action"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;
    private volatile long lastGesture;
    private volatile long historyDeadline;
    private volatile int historySequence;
    private volatile long hookThreadId;
    private volatile WinUser.HHOOK hook;
    private boolean vPressed;
    private final WinUser.LowLevelKeyboardProc callback = this::onKey;

    WindowsPasteBridge(ClipboardReader clipboard, Consumer<ImageEvent> listener) {
        this.clipboard = clipboard;
        this.listener = listener;
        char[] original = new char[1024];
        ConsoleApi.INSTANCE.GetConsoleTitleW(original, original.length);
        previousTitle = Native.toString(original);
        ConsoleApi.INSTANCE.SetConsoleTitleW(new WString(token));
        Thread thread = new Thread(this::runHook, "jhermes-paste-gesture");
        thread.setDaemon(true);
        thread.start();
        work.scheduleWithFixedDelay(this::checkHistorySelection, 80, 80, TimeUnit.MILLISECONDS);
    }

    private void runHook() {
        hookThreadId = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentThreadId();
        hook = user.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, callback, null, 0);
        if (hook == null) return;
        if (closed) { user.UnhookWindowsHookEx(hook); return; }
        WinUser.MSG message = new WinUser.MSG();
        try {
            while (!closed && user.GetMessage(message, null, 0, 0) > 0) {
                user.TranslateMessage(message);
                user.DispatchMessage(message);
            }
        } finally {
            user.UnhookWindowsHookEx(hook);
        }
    }

    private WinDef.LRESULT onKey(int code, WinDef.WPARAM message, WinUser.KBDLLHOOKSTRUCT key) {
        if (code >= 0 && !closed && key.vkCode == 0x56) {
            int type = message.intValue();
            if (type == WinUser.WM_KEYUP || type == WinUser.WM_SYSKEYUP) vPressed = false;
            if ((type == WinUser.WM_KEYDOWN || type == WinUser.WM_SYSKEYDOWN) && !vPressed) {
                vPressed = true;
                if (focused()) {
                    if (pressed(0x5b) || pressed(0x5c)) {
                        lastGesture = System.nanoTime();
                        historyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
                        historySequence = ClipboardApi.INSTANCE.GetClipboardSequenceNumber();
                    } else if (pressed(0x11)) {
                        long gesture = System.nanoTime();
                        lastGesture = gesture;
                        work.schedule(() -> readImage(gesture), 100, TimeUnit.MILLISECONDS);
                    }
                }
            }
        }
        return user.CallNextHookEx(hook, code, message, new WinDef.LPARAM(Pointer.nativeValue(key.getPointer())));
    }

    boolean hasRecentGesture() {
        return !closed && System.nanoTime() - lastGesture < TimeUnit.SECONDS.toNanos(1);
    }

    private boolean pressed(int key) { return (user.GetAsyncKeyState(key) & 0x8000) != 0; }
    private boolean focused() {
        WinDef.HWND foreground = user.GetForegroundWindow();
        if (foreground == null) return false;
        char[] title = new char[1024];
        user.GetWindowText(foreground, title, title.length);
        // Per-read nonce title follows the active terminal tab/pane. Unknown hosts fail closed.
        return Native.toString(title).contains(token);
    }

    private void checkHistorySelection() {
        if (closed || historyDeadline == 0) return;
        if (System.nanoTime() > historyDeadline) { historyDeadline = 0; return; }
        boolean focus = focused();
        int sequence = ClipboardApi.INSTANCE.GetClipboardSequenceNumber();
        if (focus && sequence != historySequence) {
            historyDeadline = 0;
            long gesture = System.nanoTime();
            lastGesture = gesture;
            readImage(gesture);
        }
    }

    private void readImage(long gesture) {
        if (closed || !focused()) return;
        try {
            ClipboardReader.Content content = clipboard.read();
            if (!closed && focused() && content.image() != null) listener.accept(new ImageEvent(gesture, content.image(), null));
        } catch (IOException e) {
            if (!closed && focused()) listener.accept(new ImageEvent(gesture, null, "读取粘贴图片失败, 可用 /paste 重试"));
        }
    }

    @Override public void close() {
        closed = true;
        historyDeadline = 0;
        work.shutdownNow();
        if (hookThreadId != 0) user.PostThreadMessage((int) hookThreadId, WinUser.WM_QUIT, null, null);
        if (hook != null) user.UnhookWindowsHookEx(hook);
        ConsoleApi.INSTANCE.SetConsoleTitleW(new WString(previousTitle));
    }
}
