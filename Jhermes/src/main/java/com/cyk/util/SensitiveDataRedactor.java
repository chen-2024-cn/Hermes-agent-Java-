package com.cyk.util;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h1>敏感数据脱敏器（JH-SPEC-001 CC-4 / P0-2 R10 / P2-4 R1）</h1>
 *
 * <p>把可能出现在命令、参数、日志、审计、轨迹里的<b>明文密钥</b>替换为掩码，
 * 再落盘或打印。这是「一处规则、全局复用」的设计：审计日志（P0-2 R10）、
 * 崩溃日志（P2-3 R3）、轨迹脱敏（P2-4 R1）共用本类，避免各写一套正则导致防护漂移
 * （与 {@code SensitivePathGuard} 抽出共享黑名单是同一条工程理由）。</p>
 *
 * <h2>覆盖的敏感形态</h2>
 * <ul>
 *   <li>{@code Bearer <token>}、{@code Authorization: <value>}</li>
 *   <li>{@code sk-} / {@code sk-ant-} / {@code sk-or-} / {@code sk-proj-} 等 OpenAI 系密钥</li>
 *   <li>{@code password=} / {@code passwd=} / {@code pwd=} / {@code -p<value>}（谨慎）</li>
 *   <li>{@code api_key=} / {@code apikey=} / {@code api-key=} / {@code access_token=} / {@code token=} / {@code secret=}</li>
 *   <li>AWS Access Key ID（{@code AKIA...}）</li>
 *   <li>URL 内嵌凭据（{@code scheme://user:pass@host}）</li>
 * </ul>
 *
 * <h2>设计取舍</h2>
 * <p>保留每个密钥的<b>前 4 位 + 尾 2 位</b>便于用户辨认是哪个凭据、又不泄露完整值——
 * 全掩成 {@code ***} 会让用户无法排查「到底哪个 key 被用了」。长度过短（&lt;6）的
 * 值全掩，避免前缀+后缀泄露比例过高。</p>
 */
public final class SensitiveDataRedactor {

    private SensitiveDataRedactor() {
    }

    /** 掩码占位符。 */
    private static final String MASK = "******";

    /**
     * 单条脱敏规则：正则 + 用掩码重建的 lambda。
     * 顺序无关（各规则匹配互不重叠的片段）。
     */
    private record Redaction(Pattern pattern, java.util.function.Function<Matcher, String> replace) {
    }

    private static final List<Redaction> RULES = List.of(
            // Bearer / Basic token
            new Redaction(Pattern.compile("(?i)(bearer|basic)\\s+[A-Za-z0-9._\\-+/=]{6,}"),
                    m -> m.group(1) + " " + MASK),
            // Authorization: value（HTTP 头）
            new Redaction(Pattern.compile("(?i)(authorization\\s*[:=]\\s*)[^\\s,;\"']{4,}"),
                    m -> m.group(1) + MASK),
            // api_key / apikey / access_token / token / secret / password 等 k=v 形式
            new Redaction(Pattern.compile(
                    "(?i)(api[-_]?key|apikey|access[-_]?token|refresh[-_]?token|auth[-_]?token|token|secret|secret[-_]?key|password|passwd|pwd)(\\s*[:=]\\s*)(\"|')?([^\\s\"';,}{)]{2,})"),
                    m -> m.group(1) + m.group(2) + MASK),
            // sk- 系密钥（含供应商前缀变体），出现在任意位置
            new Redaction(Pattern.compile("(?i)\\bsk-(?:ant-|or-|proj-|live-)?[A-Za-z0-9._\\-]{8,}"),
                    m -> "sk-" + keepEdges(m.group(), 3)),
            // AWS Access Key ID
            new Redaction(Pattern.compile("\\b(AKIA|ASIA|AROA)[A-Z0-9]{16}\\b"),
                    m -> m.group(1) + MASK),
            // URL 内嵌凭据 scheme://user:pass@host
            new Redaction(Pattern.compile("(?i)([a-z][a-z0-9+\\-.]*://[^/\\s:@]{1,32}:)([^@\\s]{1,64})(@)"),
                    m -> m.group(1) + MASK + m.group(3))
    );

    /**
     * 对文本做脱敏。null 安全；无匹配时原样返回（不新建字符串，零开销热路径友好）。
     *
     * @param text 原始文本（命令、参数、日志行等）
     * @return 脱敏后的文本
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        boolean changed = false;
        for (Redaction r : RULES) {
            Matcher m = r.pattern().matcher(result);
            if (m.find()) {
                StringBuilder sb = new StringBuilder();
                do {
                    m.appendReplacement(sb, Matcher.quoteReplacement(r.replace().apply(m)));
                } while (m.find());
                m.appendTail(sb);
                result = sb.toString();
                changed = true;
            }
        }
        return changed ? result : text;
    }

    /** 快速判断文本是否可能含敏感数据（供调用方决定是否走脱敏，热路径优化）。 */
    public static boolean mayContainSecret(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("sk-") || lower.contains("bearer") || lower.contains("password")
                || lower.contains("passwd") || lower.contains("api_key") || lower.contains("apikey")
                || lower.contains("token") || lower.contains("secret") || lower.contains("authorization")
                || lower.contains("akia") || lower.contains("://");
    }

    /** 保留首 {@code prefix} 位与末 2 位，中间掩码；过短则整体掩码。 */
    private static String keepEdges(String value, int prefix) {
        if (value == null || value.length() < 6) {
            return MASK;
        }
        int head = Math.min(prefix, value.length());
        return value.substring(0, head) + MASK;
    }
}
