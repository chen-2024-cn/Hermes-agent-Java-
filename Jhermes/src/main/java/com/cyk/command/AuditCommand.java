package com.cyk.command;

import com.cyk.constant.Constants;
import com.cyk.security.AuditLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * <h1>{@code Jhermes audit} —— 命令审计查询（JH-SPEC-001 P0-2 R10）</h1>
 *
 * <p>读取 {@code ~/.jhermes/logs/audit-YYYY-MM-DD.jsonl}，按天数与级别过滤后展示，
 * 让用户/负责人能复查「模型这段时间想执行什么、被如何处置」。每条记录已由
 * {@link AuditLogger} 在写入前脱敏，本命令只读不改。</p>
 *
 * <p>纯本地文件读取，不联网、不调模型。</p>
 */
@CommandLine.Command(name = "audit", description = "查看命令执行审计日志")
public class AuditCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认回溯天数。 */
    private static final int DEFAULT_DAYS = 7;

    @CommandLine.Option(names = {"--days"}, description = "回溯最近 N 天的审计文件（默认 7）")
    private Integer days;

    @CommandLine.Option(names = {"--level"}, description = "按级别过滤：READ_ONLY/WRITE/DANGEROUS/BLOCKED")
    private String level;

    @CommandLine.Option(names = {"-n", "--limit"}, description = "最多展示条数（默认 100，从最新往前）")
    private Integer limit;

    @Override
    public Integer call() {
        Path logsDir = Constants.getHermesHome().resolve("logs");
        if (!Files.isDirectory(logsDir)) {
            System.out.println("尚无审计日志目录（" + logsDir + "）。运行过命令后才会生成。");
            return 0;
        }

        int backDays = (days != null && days > 0) ? days : DEFAULT_DAYS;
        int maxLines = (limit != null && limit > 0) ? limit : 100;
        String levelFilter = (level != null && !level.isBlank()) ? level.trim().toUpperCase() : null;

        // 收集最近 backDays 天的审计文件（含今天）
        LocalDate today = LocalDate.now();
        List<JsonNode> records = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < backDays; i++) {
                Path file = AuditLogger.auditFileForDate(logsDir, today.minusDays(i));
                if (Files.isRegularFile(file)) {
                    records.addAll(readRecords(file));
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("读取审计日志失败：" + e.getMessage());
            return 1;
        }

        // 从最新往前展示（文件内按写入顺序，越靠后越新）
        java.util.Collections.reverse(records);

        int shown = 0;
        int matched = 0;
        for (JsonNode rec : records) {
            String recLevel = rec.path("level").asText("");
            if (levelFilter != null && !levelFilter.equals(recLevel)) {
                continue;
            }
            matched++;
            if (shown >= maxLines) {
                continue;// 继续计数但不展示，用于底部汇总
            }
            printRecord(rec);
            shown++;
        }

        System.out.println();
        System.out.printf("共 %d 条记录（回溯 %d 天%s），展示 %d 条。%n",
                matched, backDays,
                levelFilter != null ? "，级别 " + levelFilter : "", shown);
        return 0;
    }

    /** 解析一份 JSONL 审计文件的全部记录。损坏行跳过不中断。 */
    private List<JsonNode> readRecords(Path file) throws IOException {
        List<JsonNode> out = new java.util.ArrayList<>();
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    out.add(MAPPER.readTree(line));
                } catch (IOException e) {
                    System.err.println("跳过无法解析的审计行：" + truncate(line, 60));
                }
            }
        }
        return out;
    }

    /** 单条记录的一行摘要 + 关键字段。 */
    private void printRecord(JsonNode rec) {
        String ts = rec.path("timestamp").asText("?");
        String lvl = rec.path("level").asText("?");
        String decision = rec.path("decision").asText("?");
        String cmd = rec.path("command").asText("");
        String rules = String.join(",", arrayValues(rec.path("matched_rules")));
        String exit = rec.path("exit_code").isNull() ? "-" : rec.path("exit_code").asText();
        String dur = rec.path("duration_ms").isNull() ? "-" : rec.path("duration_ms").asText() + "ms";
        System.out.printf("%s | %-11s | %-22s | %-8s %s%n  cmd: %s%n",
                ts.length() > 19 ? ts.substring(11, 19) : ts, lvl, decision,
                "exit=" + exit, "dur=" + dur, truncate(cmd, 120));
        if (!rules.isEmpty()) {
            System.out.println("  rules: " + rules);
        }
    }

    private static List<String> arrayValues(JsonNode node) {
        List<String> out = new java.util.ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
