package com.cyk.ui;

import java.util.List;
import java.util.Locale;

/** Selection is display state only; accepting a candidate never submits the line. */
final class SlashMenu {
    private List<UserInput.CommandChoice> choices = List.of();
    private List<UserInput.CommandChoice> matches = List.of();
    private String prefix = "";
    private String dismissed;
    private int selected;
    private boolean navigated;

    void configure(List<UserInput.CommandChoice> choices) { this.choices = List.copyOf(choices); reset(); }
    void update(String text, int cursor, boolean blocked) {
        String candidate = text == null ? "" : text;
        if (blocked || cursor != candidate.length() || !candidate.startsWith("/")
                || candidate.indexOf('\n') >= 0 || candidate.indexOf('\r') >= 0) {
            matches = List.of(); return;
        }
        if (!candidate.equals(prefix)) {
            prefix = candidate; dismissed = null; selected = 0; navigated = false;
        }
        if (candidate.equals(dismissed)) { matches = List.of(); return; }
        String normalized = candidate.toLowerCase(Locale.ROOT);
        matches = choices.stream().filter(c -> c.name().toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
        selected = Math.min(selected, Math.max(0, matches.size() - 1));
    }
    boolean visible() { return !matches.isEmpty(); }
    void move(int direction) {
        if (!visible()) return;
        selected = Math.floorMod(selected + direction, matches.size()); navigated = true;
    }
    String accept(boolean tab) {
        if (!visible()) return null;
        UserInput.CommandChoice choice = matches.get(selected);
        if (!tab && !navigated && choice.name().equalsIgnoreCase(prefix)) {
            dismiss(); return null; // A fully typed exact command keeps ordinary Enter semantics.
        }
        String result = choice.name() + (choice.takesArguments() ? " " : "");
        dismiss(); dismissed = result; prefix = result;
        return result;
    }
    void dismiss() { dismissed = prefix; matches = List.of(); }
    void reset() { prefix = ""; dismissed = null; matches = List.of(); selected = 0; navigated = false; }

    String render(int columns, int availableRows) {
        if (!visible() || availableRows < 2) return "";
        int count = Math.min(matches.size(), Math.min(8, availableRows - 1));
        int start = Math.max(0, Math.min(selected - count + 1, matches.size() - count));
        StringBuilder out = new StringBuilder();
        for (int i = start; i < start + count; i++) {
            if (!out.isEmpty()) out.append('\n');
            var choice = matches.get(i);
            out.append(clip((i == selected ? "> " : "  ") + choice.name() + "  " + choice.description(), columns - 1));
        }
        out.append('\n').append(clip("↑/↓ 选择 · Enter/Tab 填入 · Esc 关闭  " + (selected + 1) + "/" + matches.size(), columns - 1));
        return out.toString();
    }

    private static String clip(String text, int columns) {
        StringBuilder out = new StringBuilder(); int width = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset); offset += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) continue;
            int size = org.jline.utils.WCWidth.wcwidth(cp);
            if (width + Math.max(0, size) > Math.max(0, columns)) break;
            out.appendCodePoint(cp); width += Math.max(0, size);
        }
        return out.toString();
    }
}
