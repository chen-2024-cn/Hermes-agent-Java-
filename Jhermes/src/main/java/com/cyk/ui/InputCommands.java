package com.cyk.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Draft-only actions: no model invocation and no second stdin reader. */
public final class InputCommands {
    private final InputDraft draft;
    private final UserInput input;
    private final ClipboardReader clipboard;
    private final Consumer<String> output;

    public InputCommands(InputDraft draft, UserInput input, ClipboardReader clipboard, Consumer<String> output) {
        this.draft = draft;
        this.input = input;
        this.clipboard = clipboard;
        this.output = output;
    }

    public void execute(String command) {
        int separator = command.indexOf(' ');
        String name = separator < 0 ? command : command.substring(0, separator);
        String argument = separator < 0 ? "" : command.substring(separator + 1).trim();
        try {
            switch (name) {
                case "/paste" -> {
                    if (!input.isInteractive()) {
                        output.accept("非交互模式不读取剪贴板; 请在终端对话中使用 /paste");
                        return;
                    }
                    ClipboardReader.Content content = clipboard.read();
                    String combined = draft.text() + InputDraft.normalize(content.text());
                    if (combined.length() > InputDraft.MAX_TEXT_CHARS) {
                        throw new IllegalArgumentException("文字超过限制");
                    }
                    if (content.image() != null) draft.add(content.image());
                    draft.pasteText(content.text());
                    output.accept(content.image() == null && content.text().isEmpty()
                            ? "剪贴板中没有文字或图片" : "已加入草稿, 尚未发送");
                    if (draft.hasImages()) output.accept(draft.summary());
                }
                case "/image" -> {
                    Path path = Path.of(parsePath(argument));
                    draft.add(ImageAttachment.fromFile(path));
                    output.accept(draft.summary());
                }
                case "/remove" -> {
                    if (argument.isEmpty()) {
                        output.accept(draft.summary());
                    } else {
                        int index = Integer.parseInt(argument) - 1;
                        if (index < 0 || index >= draft.images().size()) throw new IllegalArgumentException("图片编号不存在");
                        draft.remove(index);
                        output.accept(draft.summary());
                    }
                }
                case "/clear" -> {
                    draft.clear();
                    output.accept("已清空草稿与附件");
                }
                case "/multiline" -> readMultiline();
                default -> throw new IllegalArgumentException("未知草稿命令");
            }
        } catch (IOException | IllegalArgumentException e) {
            output.accept("输入操作失败: " + e.getMessage());
        }
    }

    private void readMultiline() {
        output.accept("多行输入: 单独一行 /end 完成草稿, /cancel 取消; 完成后仍需 Enter 发送");
        StringBuilder text = new StringBuilder();
        boolean first = true;
        while (true) {
            String line = input.readMessage("  | ", new InputDraft());
            if (line == null || line.equals("/cancel")) {
                output.accept("已取消多行输入");
                return;
            }
            if (line.equals("/end")) break;
            if ((long) text.length() + line.length() + 1 > InputDraft.MAX_TEXT_CHARS) {
                // Consume to the explicit boundary rather than leaking remaining lines into REPL/approval.
                do { line = input.readMessage("  | ", new InputDraft()); } while (line != null && !line.equals("/end") && !line.equals("/cancel"));
                throw new IllegalArgumentException("多行文字超过限制, 未加入草稿");
            }
            if (!first) text.append('\n');
            first = false;
            text.append(line);
        }
        draft.pasteText(text.toString());
        output.accept("多行文字已加入草稿, 尚未发送");
    }

    static String parsePath(String argument) {
        if (argument.isEmpty()) throw new IllegalArgumentException("用法: /image \"图片路径\"");
        if (argument.charAt(0) == '"') {
            if (argument.length() < 2 || argument.charAt(argument.length() - 1) != '"') {
                throw new IllegalArgumentException("图片路径引号未闭合");
            }
            return argument.substring(1, argument.length() - 1);
        }
        return argument;
    }
}
