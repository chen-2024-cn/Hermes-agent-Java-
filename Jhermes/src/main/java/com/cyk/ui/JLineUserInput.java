package com.cyk.ui;

import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.Size;
import org.jline.utils.AttributedString;
import com.cyk.util.BannerAnimation;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Pasted payloads stay out of the display buffer. Only explicit submission expands registered blocks. */
public final class JLineUserInput implements UserInput {
    private final Terminal terminal;
    private final PasteReader reader;
    private final ClipboardReader clipboard;
    private final ArrayBlockingQueue<WindowsPasteBridge.ImageEvent> imageEvents = new ArrayBlockingQueue<>(4);
    private boolean eof;
    private boolean confirmation;
    private boolean confirmationPasted;
    private boolean submittedBlocks;
    private String typedCommand;
    private InputDraft draft;
    private WindowsPasteBridge bridge;
    private BannerAnimation.Presentation pendingStartup;
    private BannerAnimation.Presentation activeStartup;
    private String startupInputPrompt;
    private final SlashMenu slashMenu = new SlashMenu();

    public JLineUserInput(Terminal terminal, ClipboardReader clipboard) throws IOException {
        this.terminal = terminal;
        this.clipboard = clipboard;
        this.reader = new PasteReader(terminal);
        reader.setParser(new DefaultParser().escapeChars(null).quoteChars(null));
        reader.setOpt(LineReader.Option.BRACKETED_PASTE);
        reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        reader.setVariable(LineReader.DISABLE_HISTORY, true);
        reader.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "  | ");
        reader.setKeyMap(LineReader.EMACS);
        reader.getKeyMaps().get(LineReader.EMACS).setAmbiguousTimeout(150);
        bind(() -> { if (!confirmation) reader.getBuffer().write('\n'); return true; },
                "\033\r", "\033\n", "\033[13;2u", "\033[27;2;13~");
        bind(this::pasteClipboard, KeyMap.alt('v'), KeyMap.ctrl('V'));
        bind(() -> { throw new UserInterruptException(reader.getBuffer().toString()); }, KeyMap.ctrl('C'));
        bind(() -> { slashMenu.dismiss(); reader.clearSlashMenu(); return true; }, "\033");
        reader.getKeyMaps().get(LineReader.EMACS).bind(new org.jline.reader.Reference(LineReader.UP_LINE_OR_HISTORY), "\033[A");
        reader.getKeyMaps().get(LineReader.EMACS).bind(new org.jline.reader.Reference(LineReader.DOWN_LINE_OR_HISTORY), "\033[B");
    }

    private void bind(Widget widget, String... keys) { reader.getKeyMaps().get(LineReader.EMACS).bind(widget, keys); }

    private final class PasteReader extends LineReaderImpl {
        PasteReader(Terminal terminal) throws IOException { super(terminal); }

        @Override public boolean beginPaste() {
            String text = InputDraft.normalize(doReadStringUntil(BRACKETED_PASTE_END));
            if (confirmation) {
                confirmationPasted = true;
                printAbove("确认提示不接受粘贴, 请手动输入明确的回答");
                return true;
            }
            try {
                int position = charPosition();
                if (!text.isEmpty()) {
                    int cursor = draft.pasteText(position, text);
                    setDisplay(cursor);
                } else if (bridge == null || !bridge.hasRecentGesture()) {
                    pasteClipboard();
                }
            } catch (IllegalArgumentException e) {
                printAbove("粘贴失败: " + e.getMessage());
            }
            return true;
        }

        @Override protected Widget getWidget(Object binding) {
            Widget widget = super.getWidget(binding);
            if (widget == null) return null;
            return () -> {
                if (confirmation || draft == null) return widget.apply();
                String before = getBuffer().toString();
                int beforeCursor = charPosition();
                String name = binding instanceof org.jline.reader.Reference ref ? ref.name() : "";
                if (slashMenu.visible()) {
                    if (name.equals(LineReader.UP_LINE_OR_HISTORY) || name.equals(LineReader.UP_LINE_OR_SEARCH)) {
                        slashMenu.move(-1); return true;
                    }
                    if (name.equals(LineReader.DOWN_LINE_OR_HISTORY) || name.equals(LineReader.DOWN_LINE_OR_SEARCH)) {
                        slashMenu.move(1); return true;
                    }
                    boolean tab = name.equals(LineReader.EXPAND_OR_COMPLETE) || name.equals(LineReader.COMPLETE_WORD);
                    if (tab || name.equals(LineReader.ACCEPT_LINE)) {
                        String completed = slashMenu.accept(tab);
                        if (completed != null) {
                            InputDraft.Edit edit = draft.edit(before, completed, completed.length());
                            setDisplay(edit.cursor());
                            return true;
                        }
                        post = null;
                    }
                }
                long revision = draft.revision();
                boolean applied = widget.apply();
                if (revision == draft.revision()) {
                    int cursor = charPosition();
                    try {
                        InputDraft.Edit edit = draft.edit(before, getBuffer().toString(), cursor);
                        int direction = cursor < beforeCursor ? -1 : 1;
                        setDisplay(draft.snapCursor(edit.cursor(), direction));
                    } catch (IllegalArgumentException e) {
                        setDisplay(beforeCursor);
                        printAbove(e.getMessage());
                    }
                }
                return applied;
            };
        }

        void clearSlashMenu() { post = null; }

        @Override protected void redisplay(boolean flush) {
            Size current = terminal.getSize();
            if (size.getColumns() != current.getColumns() || size.getRows() != current.getRows()) {
                size.copy(current);
                if (display != null) display.resize(size.getRows(), size.getColumns());
            }
            if (!confirmation && draft != null) {
                slashMenu.update(getBuffer().toString(), charPosition(), draft.hasBlocks());
                if (slashMenu.visible()) {
                    int headerRows = activeStartup == null ? 1
                            : activeStartup.renderHeader(terminal.getWidth(), terminal.getHeight()).split("\n", -1).length;
                    post = () -> new AttributedString(slashMenu.render(terminal.getWidth(),
                            Math.max(0, terminal.getHeight() - headerRows - 2)));
                } else {
                    post = null;
                }
            }
            if (activeStartup != null && !confirmation) {
                // The banner is prompt text, never buffer text. JLine owns all cursor movement.
                this.prompt = AttributedString.fromAnsi(activeStartup.renderHeader(
                        current.getColumns(), current.getRows()) + startupInputPrompt);
            }
            super.redisplay(flush);
        }

        @Override protected <T> T doReadBinding(KeyMap<T> keys, KeyMap<T> local) {
            while (!confirmation && draft != null) {
                Size current = terminal.getSize();
                if (size.getColumns() != current.getColumns() || size.getRows() != current.getRows()) redisplay();
                if (drainImages()) redisplay();
                if (activeStartup != null && activeStartup.needsRefresh(terminal.getWidth(), terminal.getHeight())) {
                    redisplay();
                }
                int next = bindingReader.peekCharacter(activeStartup != null && activeStartup.isAnimating() ? 40 : 80);
                if (next != org.jline.utils.NonBlockingReader.READ_EXPIRED) return super.doReadBinding(keys, local);
            }
            return super.doReadBinding(keys, local);
        }
    }

    private int charPosition() {
        String display = reader.getBuffer().toString();
        return display.offsetByCodePoints(0, Math.min(reader.getBuffer().cursor(), display.codePointCount(0, display.length())));
    }
    private void setDisplay(int charCursor) {
        String display = draft.displayText();
        reader.getBuffer().clear();
        reader.getBuffer().write(display);
        reader.getBuffer().cursor(display.codePointCount(0, Math.max(0, Math.min(charCursor, display.length()))));
    }

    private boolean drainImages() {
        WindowsPasteBridge.ImageEvent event;
        boolean changed = false;
        while ((event = imageEvents.poll()) != null) {
            if (draft == null || confirmation) continue;
            try {
                if (event.error() != null) { reader.printAbove(event.error()); continue; }
                int cursor = draft.add(charPosition(), event.image());
                setDisplay(cursor);
                changed = true;
            } catch (IllegalArgumentException e) {
                reader.printAbove(e.getMessage());
            }
        }
        return changed;
    }

    private boolean pasteClipboard() {
        if (confirmation) { confirmationPasted = true; return true; }
        if (draft == null) return true;
        // Native Ctrl+V and the scoped Windows listener represent the same gesture.
        boolean bridgedControlV = bridge != null && bridge.hasRecentGesture()
                && KeyMap.ctrl('V').equals(reader.getLastBinding());
        try {
            ClipboardReader.Content content = clipboard.read();
            if ((long) draft.text().length() + InputDraft.normalize(content.text()).length() > InputDraft.MAX_TEXT_CHARS) {
                throw new IOException("粘贴文字超过限制");
            }
            int cursor = charPosition();
            if (content.image() != null && !bridgedControlV) cursor = draft.add(cursor, content.image());
            if (!content.text().isEmpty()) cursor = draft.pasteText(cursor, content.text());
            setDisplay(cursor);
            if (content.text().isEmpty() && content.image() == null) reader.printAbove("剪贴板中没有文字或图片");
        } catch (IOException | IllegalArgumentException e) {
            reader.printAbove("粘贴失败: " + e.getMessage());
        }
        return true;
    }

    @Override public String readMessage(String prompt, InputDraft draft) {
        this.draft = draft;
        slashMenu.reset();
        confirmation = false;
        submittedBlocks = false;
        typedCommand = null;
        imageEvents.clear();
        if (terminal instanceof WindowsVtTerminal) {
            try { bridge = new WindowsPasteBridge(clipboard, imageEvents::offer); }
            catch (RuntimeException | LinkageError e) { Ui.diagln("Windows 直接图片粘贴不可用, 可用 /paste"); }
        }
        activeStartup = pendingStartup;
        pendingStartup = null;
        startupInputPrompt = prompt == null ? "" : prompt;
        String initialPrompt = activeStartup == null ? prompt : activeStartup.renderHeader(
                terminal.getWidth(), terminal.getHeight()) + startupInputPrompt;
        try {
            reader.readLine(initialPrompt, null, (Character) null, draft.displayText());
            typedCommand = draft.plainText();
            submittedBlocks = draft.hasBlocks();
            return draft.text();
        } catch (UserInterruptException e) {
            draft.clear();
            reader.printAbove("已取消当前草稿");
            return "";
        } catch (EndOfFileException e) {
            eof = true;
            draft.clear();
            return null;
        } finally {
            slashMenu.reset();
            reader.clearSlashMenu();
            stopStartup();
            if (bridge != null) { bridge.close(); bridge = null; }
            imageEvents.clear();
            this.draft = null;
        }
    }

    public void prepareStartup(BannerAnimation.Presentation presentation) {
        stopStartup();
        pendingStartup = presentation;
    }

    /** Keep the initial input hint inside the header instead of printing it above a later redraw. */
    public boolean appendStartupLine(String line) {
        if (pendingStartup == null) return false;
        pendingStartup.addLine(line);
        return true;
    }

    private void stopStartup() {
        if (activeStartup != null) activeStartup.stop();
        if (pendingStartup != null) pendingStartup.stop();
        activeStartup = null;
        pendingStartup = null;
        startupInputPrompt = null;
    }

    @Override public String commandText(String submitted) {
        if (!submittedBlocks) return submitted;
        // A typed slash command may manage the draft, but pasted commands are just message data.
        String command = typedCommand == null ? "" : typedCommand.strip();
        return command.startsWith("/") && command.indexOf('\n') < 0 ? command : "";
    }

    @Override public void configureCommands(java.util.List<CommandChoice> commands) {
        slashMenu.configure(commands);
    }

    @Override public String readLine(String prompt) {
        slashMenu.reset();
        reader.clearSlashMenu();
        stopStartup();
        confirmation = true;
        confirmationPasted = false;
        try {
            String answer = reader.readLine(prompt);
            return confirmationPasted || answer.indexOf('\n') >= 0 || answer.indexOf('\r') >= 0 ? "" : answer;
        } catch (UserInterruptException e) { return ""; }
        catch (EndOfFileException e) { eof = true; return null; }
        finally { confirmation = false; }
    }

    /** Only a real console can receive cursor-control status frames; redirected output stays plain. */
    public boolean supportsAnimation() {
        return System.console() != null && !eof && !"dumb".equals(terminal.getType());
    }

    public int terminalColumns() { return terminal.getWidth(); }
    public int terminalRows() { return terminal.getHeight(); }

    @Override public boolean isInteractive() { return !eof; }
    @Override public boolean isEofReached() { return eof; }
    @Override public void close() {
        slashMenu.reset();
        reader.clearSlashMenu();
        stopStartup();
        terminal.flush();
        if (terminal instanceof WindowsVtTerminal windows) windows.restoreModes();
    }
}
