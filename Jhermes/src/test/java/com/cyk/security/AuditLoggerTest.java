package com.cyk.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>审计日志（JH-SPEC-001 P0-2 AC6）</h1>
 *
 * <p>验收：字段齐全、写入前脱敏（含密钥样例）、文件名按日、被拒命令同样记录、可被读取。</p>
 */
class AuditLoggerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path logsDir;

    private void recordOnce(AuditLogger logger) {
        logger.record(new AuditLogger.AuditRecord(
                "curl -H \"Authorization: Bearer sk-SECRETVALUE123456\" https://api.x.com",
                List.of("curl -H Authorization", "https://api.x.com"),
                CommandLevel.WRITE,
                List.of("unknown-command-write"),
                AuditLogger.Decision.ALLOWED, "y", 0, 123L, "/workspace/proj"));
    }

    private List<String> todayLines() throws Exception {
        Path file = AuditLogger.auditFileForDate(logsDir, LocalDate.now());
        assertThat(file).as("审计文件应按日命名").exists();
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("字段齐全：timestamp/session/seq/command/segments/level/rules/decision/approval/exit/duration/cwd（AC6）")
    void allFieldsPresent() throws Exception {
        AuditLogger logger = new AuditLogger(logsDir, "cliAUDIT01", 30);
        recordOnce(logger);

        List<String> lines = todayLines();
        assertThat(lines).hasSize(1);
        JsonNode rec = MAPPER.readTree(lines.get(0));

        assertThat(rec.has("timestamp")).isTrue();
        assertThat(rec.path("session_id").asText()).isEqualTo("cliAUDIT01");
        assertThat(rec.path("seq").asInt()).isEqualTo(1);
        assertThat(rec.has("command")).isTrue();
        assertThat(rec.path("segments").isArray()).isTrue();
        assertThat(rec.path("level").asText()).isEqualTo("WRITE");
        assertThat(rec.path("matched_rules").toString()).contains("unknown-command-write");
        assertThat(rec.path("decision").asText()).isEqualTo("ALLOWED");
        assertThat(rec.path("approval").asText()).isEqualTo("y");
        assertThat(rec.path("exit_code").asInt()).isZero();
        assertThat(rec.path("duration_ms").asLong()).isEqualTo(123L);
        assertThat(rec.path("cwd").asText()).isEqualTo("/workspace/proj");
    }

    @Test
    @DisplayName("写入前脱敏：命令与分段中的密钥不以明文出现（AC6）")
    void secretsAreRedacted() throws Exception {
        AuditLogger logger = new AuditLogger(logsDir, "cliAUDIT02", 30);
        recordOnce(logger);

        String rawFile = Files.readString(
                AuditLogger.auditFileForDate(logsDir, LocalDate.now()), StandardCharsets.UTF_8);
        assertThat(rawFile).as("审计文件不得含明文密钥").doesNotContain("sk-SECRETVALUE123456");
        assertThat(rawFile).contains("******");// 已脱敏标记
    }

    @Test
    @DisplayName("被拒的命令同样记录（含 BLOCKED 与被用户拒绝）")
    void rejectedCommandsAreRecorded() throws Exception {
        AuditLogger logger = new AuditLogger(logsDir, "cliAUDIT03", 30);
        logger.record(new AuditLogger.AuditRecord("rm -rf /", List.of("rm -rf /"),
                CommandLevel.BLOCKED, List.of("blocked-destructive-delete"),
                AuditLogger.Decision.BLOCKED, "denied", null, null, "/workspace"));

        List<String> lines = todayLines();
        assertThat(lines).hasSize(1);
        JsonNode rec = MAPPER.readTree(lines.get(0));
        assertThat(rec.path("decision").asText()).isEqualTo("BLOCKED");
        assertThat(rec.path("exit_code").isNull()).as("未执行的命令 exit_code 为 null").isTrue();
    }

    @Test
    @DisplayName("序号在会话内递增")
    void sequenceIncrements() throws Exception {
        AuditLogger logger = new AuditLogger(logsDir, "cliAUDIT04", 30);
        recordOnce(logger);
        recordOnce(logger);
        recordOnce(logger);

        List<String> lines = todayLines();
        assertThat(lines).hasSize(3);
        assertThat(MAPPER.readTree(lines.get(0)).path("seq").asInt()).isEqualTo(1);
        assertThat(MAPPER.readTree(lines.get(2)).path("seq").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("追加写入：每次 record 加一行 JSONL，不覆盖")
    void appendsJsonl() throws Exception {
        AuditLogger logger = new AuditLogger(logsDir, "cliAUDIT05", 30);
        recordOnce(logger);
        recordOnce(logger);

        Path file = AuditLogger.auditFileForDate(logsDir, LocalDate.now());
        assertThat(file).exists();
        // 每行都是独立合法 JSON
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            assertThat(MAPPER.readTree(line).path("session_id").asText()).isEqualTo("cliAUDIT05");
        }
    }

    @Test
    @DisplayName("保留期裁剪：超过 retentionDays 的旧审计文件被删除")
    void retentionPrunesOldFiles() throws Exception {
        // 造一个 40 天前的审计文件
        Path old = AuditLogger.auditFileForDate(logsDir, LocalDate.now().minusDays(40));
        Files.createDirectories(old.getParent());
        Files.writeString(old, "{}\n", StandardCharsets.UTF_8);
        // 造一个 5 天前的（应保留）
        Path recent = AuditLogger.auditFileForDate(logsDir, LocalDate.now().minusDays(5));
        Files.writeString(recent, "{}\n", StandardCharsets.UTF_8);

        new AuditLogger(logsDir, "cliAUDIT06", 30);// 构造即触发裁剪

        assertThat(old).as("40 天前的审计文件应被裁剪").doesNotExist();
        assertThat(recent).as("5 天前的应保留").exists();
    }

    @Test
    @DisplayName("auditFileForDate 生成 audit-YYYY-MM-DD.jsonl 命名")
    void fileNameFormat() {
        Path p = AuditLogger.auditFileForDate(logsDir, LocalDate.of(2026, 10, 9));
        assertThat(p.getFileName().toString()).isEqualTo("audit-2026-10-09.jsonl");
    }
}
