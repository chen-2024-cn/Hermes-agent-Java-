package com.cyk.ui;

import java.util.ArrayList;
import java.util.List;

/** Display text and pasted payloads are separate: only registered spans can expand on submission. */
public final class InputDraft {
    public static final int MAX_IMAGES = 4;
    public static final int MAX_TEXT_CHARS = 1_000_000;
    private String display = "";
    private final List<Block> blocks = new ArrayList<>();
    private final List<ImageAttachment> images = new ArrayList<>();
    private int nextId = 1;
    private long revision;

    private static final class Block {
        int start;
        int end;
        final String payload;
        final ImageAttachment image;
        Block(int start, int end, String payload, ImageAttachment image) {
            this.start = start;
            this.end = end;
            this.payload = payload;
            this.image = image;
        }
    }

    public record Edit(String display, int cursor) { }
    public String displayText() { return display; }
    public long revision() { return revision; }
    public boolean hasBlocks() { return !blocks.isEmpty(); }
    public String text() { return expand(display); }

    public void text(String text) {
        String value = normalize(text == null ? "" : text);
        checkLength(value.length());
        display = value;
        blocks.clear();
        revision++;
    }

    public int pasteText(int position, String text) {
        String value = normalize(text == null ? "" : text);
        if (value.isEmpty()) return position;
        checkLength((long) text().length() + value.length());
        int lines = 1 + (int) value.chars().filter(c -> c == '\n').count();
        return insertBlock(position, "[已粘贴 " + lines + " 行 #" + nextId++ + "]", value, null);
    }

    public void pasteText(String text) { pasteText(display.length(), text); }
    public List<ImageAttachment> images() { return List.copyOf(images); }
    public boolean hasImages() { return !images.isEmpty(); }
    public void add(ImageAttachment image) { add(display.length(), image); }
    public int add(int position, ImageAttachment image) {
        if (images.size() >= MAX_IMAGES) throw new IllegalArgumentException("单条消息最多 4 张图片");
        int cursor = insertBlock(position, "[图片 #" + nextId++ + "]", "", image);
        images.add(image);
        return cursor;
    }

    private int insertBlock(int position, String token, String payload, ImageAttachment image) {
        int at = snapCursor(Math.max(0, Math.min(position, display.length())), 1);
        for (Block block : blocks) {
            if (block.start >= at) { block.start += token.length(); block.end += token.length(); }
        }
        display = display.substring(0, at) + token + display.substring(at);
        blocks.add(new Block(at, at + token.length(), payload, image));
        blocks.sort(java.util.Comparator.comparingInt(block -> block.start));
        revision++;
        return at + token.length();
    }

    /** Reconcile one editor operation. An edit touching a placeholder deletes it atomically. */
    public Edit edit(String before, String after, int cursor) {
        if (before.equals(after)) return new Edit(display, cursor);
        if (!before.equals(display)) throw new IllegalStateException("草稿与输入缓冲不同步");
        int prefix = 0;
        while (prefix < before.length() && prefix < after.length() && before.charAt(prefix) == after.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < before.length() - prefix && suffix < after.length() - prefix
                && before.charAt(before.length() - suffix - 1) == after.charAt(after.length() - suffix - 1)) suffix++;
        int start = prefix;
        int end = before.length() - suffix;
        String inserted = after.substring(prefix, after.length() - suffix);
        if (start == end) {
            start = snapCursor(start, 1);
            end = start;
        } else {
            for (Block block : blocks) {
                if (start < block.end && end > block.start) {
                    start = Math.min(start, block.start);
                    end = Math.max(end, block.end);
                }
            }
        }
        int change = inserted.length() - (end - start);
        int editStart = start;
        int editEnd = end;
        String result = before.substring(0, start) + inserted + before.substring(end);
        long length = result.length();
        for (Block block : blocks) {
            if (block.end <= start || block.start >= end) length += block.payload.length() - (block.end - block.start);
        }
        checkLength(length);
        blocks.removeIf(block -> {
            if (editStart < block.end && editEnd > block.start) {
                if (block.image != null) images.remove(block.image);
                return true;
            }
            return false;
        });
        for (Block block : blocks) {
            if (block.start >= end) { block.start += change; block.end += change; }
        }
        display = result;
        revision++;
        return new Edit(display, Math.min(display.length(), start + inserted.length()));
    }

    public int snapCursor(int cursor, int direction) {
        for (Block block : blocks) {
            if (cursor > block.start && cursor < block.end) return direction < 0 ? block.start : block.end;
        }
        return cursor;
    }

    public String plainText() {
        StringBuilder result = new StringBuilder();
        int offset = 0;
        for (Block block : blocks) {
            result.append(display, offset, block.start);
            offset = block.end;
        }
        return result.append(display.substring(offset)).toString();
    }

    /** Keep attachments while a separately typed slash command is handled. */
    public void discardPlainText() {
        StringBuilder result = new StringBuilder();
        for (Block block : blocks) {
            String token = display.substring(block.start, block.end);
            block.start = result.length();
            result.append(token);
            block.end = result.length();
        }
        display = result.toString();
        revision++;
    }

    private String expand(String value) {
        StringBuilder result = new StringBuilder();
        int offset = 0;
        for (Block block : blocks) {
            result.append(value, offset, block.start).append(block.payload);
            offset = block.end;
        }
        return result.append(value.substring(offset)).toString();
    }

    public void remove(int index) {
        ImageAttachment image = images.get(index);
        Block target = blocks.stream().filter(block -> block.image == image).findFirst().orElse(null);
        if (target == null) { images.remove(index); return; }
        int start = target.start;
        int length = target.end - target.start;
        display = display.substring(0, target.start) + display.substring(target.end);
        blocks.remove(target);
        images.remove(index);
        for (Block block : blocks) {
            if (block.start >= start) { block.start -= length; block.end -= length; }
        }
        revision++;
    }
    public void clear() { display = ""; blocks.clear(); images.clear(); revision++; }
    public List<String> imageUrls() { return images.stream().map(ImageAttachment::dataUrl).toList(); }
    public String summary() {
        StringBuilder result = new StringBuilder("待发送附件: ").append(images.size()).append(" 张");
        for (int i = 0; i < images.size(); i++) result.append("\n  ").append(i + 1).append(". ").append(images.get(i).summary());
        return result.append("\nEnter 发送; /remove <编号> 移除; /clear 清空草稿").toString();
    }
    private static void checkLength(long length) {
        if (length > MAX_TEXT_CHARS) throw new IllegalArgumentException("文字超过 100 万字符限制");
    }
    public static String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n'); }
}
