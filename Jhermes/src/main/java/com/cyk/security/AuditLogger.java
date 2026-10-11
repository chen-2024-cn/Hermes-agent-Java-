package com.cyk.security;

import com.cyk.util.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * <h1>命令执行审计日志（JH-SPEC-001 P0-2 R10）</h1>
 *
 * <p>每一次命令分级与处置都写入 {@code ~/.jhermes/logs/audit-YYYY-MM-DD.jsonl}，
 * <b>被拒的命令同样记录</b>——审计的价值恰恰在于记录「模型想做什么危险动作、被拦了没」，
 * 而不仅是成功执行了什么。</p>
 *
 * <h2>字段（R10）</h2>
 * timestamp、session_id、seq（会话内序号）、command（原文，<b>已脱敏</b>）、
 * segments（分段结果）、level、matched_rules、decision、approval、exit_code、duration_ms、cwd。
 *
 * <h2>安全</h2>
 * <ul>
 *   <li>写入前统一经 {@link SensitiveDataRedactor} 脱敏（命令与分段都脱敏）；</li>
 *   <li><b>绝不记录 stdout/stderr</b>——工具输出可能含密钥，从根上杜绝审计文件泄密；</li>
 *   <li>Unix 下文件权限 600（仅属主可读写）；</li>
 *   <li>默认保留 30 天，构造时裁剪过期文件；</li>
 *   <li>日志目录纳入 {@link com.cyk.util.SensitivePathGuard} 写保护（R11），
 *       防模型篡改审计。</li>
 * </ul>
 *
 * <p>审计失败（磁盘满、权限）<b>不影响命令执行流</b>：记 WARN 后继续。审计是尽力而为的
 * 留痕，不应成为命令执行的单点故障。</p>
 */
public final class AuditLogger {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 默认保留天数（P2-4 D-06 一致：30 天）。 */
    public static final int DEFAULT_RETENTION_DAYS = 30;

    private final Path logsDir;
    private final String sessionId;
    private final int retentionDays;
    private final AtomicLong seq = new AtomicLong(0);

    /**
     * @param logsDir       审计目录（通常 {@code ~/.jhermes/logs}）
     * @param sessionId     会话 id（贯穿所有记录）
     * @param retentionDays 保留天数；&lt;=0 用默认 30
     */
    public AuditLogger(Path logsDir, String sessionId, int retentionDays) {
        this.logsDir = logsDir;
        this.sessionId = sessionId;
        this.retentionDays = retentionDays > 0 ? retentionDays : DEFAULT_RETENTION_DAYS;
        try {
            Files.createDirectories(logsDir);
            applyRetention();
        } catch (IOException | RuntimeException e) {
            logger.warn("审计目录初始化失败（命令执行不受影响，审计将降级为内存日志）: {}", e.toString());
        }
    }

    /** 处置动作枚举，写入审计的 decision 字段。 */
    public enum Decision {
        /** 自动放行（READ_ONLY / --yolo 跳过 WRITE / 会话内同类已放行）。 */
        ALLOWED,
        /** 用户在确认闸门批准。 */
        CONFIRMED,
        /** 用户在确认闸门拒绝。 */
        REJECTED_BY_USER,
        /** 非交互模式下需确认而拒绝。 */
        REJECTED_NONINTERACTIVE,
        /** BLOCKED 硬拦，永不执行。 */
        BLOCKED,
        /** 敏感路径守卫拦截。 */
        BLOCKED_SENSITIVE,
        /** 重复命令护栏拒绝。 */
        BLOCKED_REPEAT
    }

    /**
     * 一条审计记录（由 TerminalTool 填充，本类补 timestamp/seq/session 并脱敏落盘）。
     *
     * @param command     命令原文（本类负责脱敏）
     * @param segments    分段后的原始文本列表
     * @param level       分级
     * @param matchedRules 命中规则 id
     * @param decision    处置
     * @param approval    用户确认动作（y/n/a/yes/auto/null）
     * @param exitCode    退出码；未执行为 null
     * @param durationMs  执行耗时；未执行为 null
     * @param cwd         执行目录
     */
    public record AuditRecord(String command, List<String> segments, CommandLevel level,
                              List<String> matchedRules, Decision decision, String approval,
                              Integer exitCode, Long durationMs, String cwd) {
    }

    /**
     * 写入一条审计记录。任何 IO 失败都吞掉并记 WARN，绝不中断命令流。
     */
    public void record(AuditRecord rec) {
        if (rec == null) {
            return;
        }
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("session_id", sessionId);
            entry.put("seq", seq.incrementAndGet());
            // 命令与分段一律脱敏后写入（CC-4：审计不得出现明文密钥）
            entry.put("command", SensitiveDataRedactor.redact(rec.command()));
            entry.put("segments", rec.segments() == null ? List.of()
                    : rec.segments().stream().map(SensitiveDataRedactor::redact).toList());
            entry.put("level", rec.level() != null ? rec.level().name() : null);
            entry.put("matched_rules", rec.matchedRules() != null ? rec.matchedRules() : List.of());
            entry.put("decision", rec.decision() != null ? rec.decision().name() : null);
            entry.put("approval", rec.approval());
            entry.put("exit_code", rec.exitCode());
            entry.put("duration_ms", rec.durationMs());
            entry.put("cwd", rec.cwd());

            Path file = auditFileForDate(logsDir, LocalDate.now());
            appendLine(file, MAPPER.writeValueAsString(entry));
        } catch (IOException | RuntimeException e) {
            logger.warn("写入审计记录失败（命令执行不受影响）: {}", e.toString());
        }
    }

    /** 追加一行到审计文件，首次创建时设置 Unix 600 权限。 */
    private synchronized void appendLine(Path file, String line) throws IOException {
        boolean isNew = Files.notExists(file);
        Files.createDirectories(file.getParent());
        Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        if (isNew) {
            restrictPermissions(file);
        }
    }

    /** Unix 下设 600（仅属主读写）；不支持 POSIX 权限的平台（Windows）静默跳过。 */
    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 无 POSIX 权限模型，忽略
            logger.debug("设置审计文件权限失败（平台可能不支持 POSIX）: {}", e.toString());
        }
    }

    /** 裁剪超过保留期的审计文件。 */
    private void applyRetention() {
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        try (Stream<Path> files = Files.list(logsDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("audit-"))
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(p -> {
                        LocalDate date = parseDateFromFileName(p.getFileName().toString());
                        if (date != null && date.isBefore(cutoff)) {
                            try {
                                Files.deleteIfExists(p);
                                logger.debug("已裁剪过期审计文件: {}", p.getFileName());
                            } catch (IOException e) {
                                logger.warn("裁剪过期审计文件失败 {}: {}", p.getFileName(), e.getMessage());
                            }
                        }
                    });
        } catch (IOException | RuntimeException e) {
            logger.debug("审计保留期裁剪跳过: {}", e.toString());
        }
    }

    /** 某日期对应的审计文件路径（供 AuditLogger 与 audit 子命令共用）。 */
    public static Path auditFileForDate(Path logsDir, LocalDate date) {
        return logsDir.resolve("audit-" + date.format(DATE_FMT) + ".jsonl");
    }

    private static LocalDate parseDateFromFileName(String name) {
        try {
            // audit-YYYY-MM-DD.jsonl
            String datePart = name.substring("audit-".length(), name.length() - ".jsonl".length());
            return LocalDate.parse(datePart, DATE_FMT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @return 当前审计目录（供自检/展示）。 */
    public Path logsDir() {
        return logsDir;
    }
}
