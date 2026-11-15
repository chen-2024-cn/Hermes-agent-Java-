package com.cyk.security;

import com.cyk.util.SensitiveDataRedactor;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/** Display-only explanations. Never used to classify risk, grant permission, or build process arguments. */
public record ApprovalContext(String purpose, String expectedResult, String workdir, String impact, String source) {
    static final int MAX_EXPLANATION_CHARS = 200;
    private static final Pattern TERMINAL_SEQUENCE = Pattern.compile(
            "(?:\\x1b\\]|\\x9d)[^\\x07\\x1b\\x9c]*(?:\\x07|\\x1b\\\\|\\x9c|$)"
                    + "|(?:\\x1b\\[|\\x9b)[0-?]*[ -/]*[@-~]|\\x1b[ -/]*[@-~]");

    public ApprovalContext {
        purpose = explanation(purpose, "未提供具体目的，请核对命令与风险");
        expectedResult = explanation(expectedResult, "未提供预期结果，以实际输出为准");
        workdir = safeCommand(workdir == null ? "" : workdir);
        impact = explanation(impact, "");
        source = explanation(source, "未提供操作说明");
    }

    public static ApprovalContext unspecified() {
        return new ApprovalContext(null, null, null, null, null);
    }

    public static ApprovalContext forCommand(Map<String, Object> args, Path workdir) {
        String purpose = stringArg(args, "purpose");
        String expected = stringArg(args, "expected_result");
        return new ApprovalContext(purpose, expected, workdir.toString(),
                "执行上述命令；以风险分级和命令内容为准", provenance(purpose, expected));
    }

    public static ApprovalContext forFile(Map<String, Object> args, Path file, boolean append, boolean exists) {
        String operation = !exists ? "新建文件并写入内容" : append ? "在已有文件末尾追加内容"
                : "覆盖已有文件，原内容将被替换";
        String purpose = stringArg(args, "purpose");
        String expected = stringArg(args, "expected_result");
        return new ApprovalContext(purpose == null ? "将给定内容写入指定文件" : purpose,
                expected == null ? operation : expected,
                file.getParent() == null ? "" : file.getParent().toString(), operation,
                purpose == null && expected == null ? "工具行为" : "模型说明；文件影响由工具确定");
    }

    public static ApprovalContext forSkill(String description, Path script) {
        return new ApprovalContext(description, "运行脚本并返回输出；文件和网络影响取决于脚本",
                script.getParent() == null ? "" : script.getParent().toString(),
                "执行技能脚本；脚本改动后需重新确认", "技能声明，非安全保证");
    }

    private static String provenance(String purpose, String expected) {
        return purpose == null && expected == null ? "未提供操作说明" : "模型说明，非安全保证";
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** Keep explanations to a single bounded line; secrets are redacted before truncating. */
    static String explanation(String text, String fallback) {
        if (text == null || text.isBlank()) return fallback;
        String cleaned = stripTerminalSequences(text);
        StringBuilder line = new StringBuilder();
        cleaned.codePoints().forEach(cp -> {
            if (Character.getType(cp) == Character.FORMAT) return;
            if (Character.isISOControl(cp) || Character.isWhitespace(cp)) line.append(' ');
            else line.appendCodePoint(cp);
        });
        String safe = SensitiveDataRedactor.redact(line.toString()).replaceAll(" +", " ").strip();
        if (safe.isBlank()) return fallback;
        return safe.length() > MAX_EXPLANATION_CHARS ? safe.substring(0, MAX_EXPLANATION_CHARS) + "…" : safe;
    }

    /** Do not truncate actual commands: users must be able to inspect a dangerous trailing segment. */
    public static String safeCommand(String text) {
        if (text == null) return "";
        StringBuilder safe = new StringBuilder();
        // Escape controls rather than removing bytes from the command users must approve.
        text.codePoints().forEach(cp -> {
            if (cp == '\n') safe.append("\\n");
            else if (cp == '\r') safe.append("\\r");
            else if (cp == '\t') safe.append("\\t");
            else if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) {
                safe.append(String.format(java.util.Locale.ROOT, "\\u%04x", cp));
            } else safe.appendCodePoint(cp);
        });
        return SensitiveDataRedactor.redact(safe.toString());
    }

    private static String stripTerminalSequences(String text) {
        return TERMINAL_SEQUENCE.matcher(text).replaceAll("");
    }
}
