package com.cyk.prompt;

import com.cyk.util.SensitiveDataRedactor;
import com.cyk.util.SensitivePathGuard;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Reads only fixed instruction filenames, never includes, URLs, scripts or arbitrary Markdown. */
public final class ProjectInstructionLoader {
    public static final String PRIMARY = "JHERMES.md";
    public static final String FALLBACK = "AGENTS.md";
    public static final String LOCAL = "JHERMES.local.md";
    public static final int MAX_FILE_BYTES = 32 * 1024;
    public static final int MAX_FILE_CHARS = 8_000;
    public static final int MAX_TOTAL_CHARS = 12_000;

    private final Path root;
    private final Path personalDirectory;

    public ProjectInstructionLoader(Path cwd, Path personalDirectory) {
        this.root = discoverRoot(cwd);
        Path personal = personalDirectory.toAbsolutePath().normalize();
        try { personal = personal.toRealPath(); } catch (IOException ignored) { }
        this.personalDirectory = personal;
    }

    public Path root() { return root; }

    public ProjectInstructions load() {
        List<ProjectInstructions.Document> documents = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        read(documents, personalDirectory, PRIMARY, "个人偏好", "数据目录/JHERMES.md");
        String agent = exists(root.resolve("AGENT.md")) ? "AGENT.md" : FALLBACK;
        read(documents, root, agent, "通用项目约定", agent);
        if (agent.equals("AGENT.md") && exists(root.resolve(FALLBACK))) notes.add("AGENT.md 存在; AGENTS.md 未重复加载。");
        read(documents, root, "CLAUDE.md", "项目兼容约定", "CLAUDE.md");
        read(documents, root, LOCAL, "项目个人补充", LOCAL);
        read(documents, root, PRIMARY, "最高优先级项目约定", PRIMARY);
        int used = ProjectInstructions.GUIDANCE.length() + 2;
        Set<Path> seen = new java.util.HashSet<>();
        // Allocate whole-file budgets from highest priority, then render in the original low-to-high order.
        for (int i = documents.size() - 1; i >= 0; i--) {
            var document = documents.get(i);
            if (!document.ready()) continue;
            int cost = document.render().length() + 2;
            if (!seen.add(document.path().toAbsolutePath().normalize())) {
                documents.set(i, rejected(document.scope(), document.path(), document.source(), "跳过: 同一文件已加载"));
            } else if (used + cost > MAX_TOTAL_CHARS) {
                documents.set(i, rejected(document.scope(), document.path(), document.source(),
                        "跳过: 总预算超过 " + MAX_TOTAL_CHARS + " 字符, 请精简"));
            } else {
                used += cost;
            }
        }
        return new ProjectInstructions(root, documents, notes);
    }

    private void read(List<ProjectInstructions.Document> documents, Path directory, String name,
                      String scope, String source) {
        Path file = directory.resolve(name);
        if (!exists(file)) return;
        try {
            if (!safeFile(file, directory)) {
                documents.add(rejected(scope, file, source, "跳过: 路径越界、链接或非普通文件"));
                return;
            }
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                documents.add(rejected(scope, file, source, "跳过: 超过 " + MAX_FILE_BYTES + " 字节"));
                return;
            }
            byte[] bytes;
            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_FILE_BYTES || !safeFile(file, directory)) {
                documents.add(rejected(scope, file, source, "跳过: 文件读取期间发生变化或超限"));
                return;
            }
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            text = PromptLoader.normalize(text);
            if (text.length() > MAX_FILE_CHARS) {
                documents.add(rejected(scope, file, source, "跳过: 超过 " + MAX_FILE_CHARS + " 字符"));
                return;
            }
            if (text.isEmpty()) {
                documents.add(rejected(scope, file, source, "跳过: 空文件"));
                return;
            }
            if (unsafeControls(text)) {
                documents.add(rejected(scope, file, source, "跳过: 含终端控制字符或不可见格式字符"));
                return;
            }
            if (!SensitiveDataRedactor.redact(text).equals(text)) {
                documents.add(rejected(scope, file, source, "跳过: 疑似包含凭据, 请手工移除后再提交任务"));
                return;
            }
            String fingerprint = file.toRealPath() + ":" + hash(bytes);
            documents.add(new ProjectInstructions.Document(scope, file, source, text, fingerprint,
                    true, "已自动加载"));
        } catch (CharacterCodingException e) {
            documents.add(rejected(scope, file, source, "跳过: 不是有效 UTF-8 文本"));
        } catch (IOException | RuntimeException e) {
            // Error messages can contain an external target path or private file contents.
            documents.add(rejected(scope, file, source, "跳过: 文件不可读"));
        }
    }

    private static ProjectInstructions.Document rejected(String scope, Path path, String source, String reason) {
        return new ProjectInstructions.Document(scope, path, source, "", "", false, reason);
    }

    private static boolean unsafeControls(String text) {
        return text.codePoints().anyMatch(c -> (Character.isISOControl(c) && c != '\n' && c != '\t')
                || (Character.getType(c) == Character.FORMAT && c != 0x200D));
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean safeFile(Path file, Path directory) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return false;
        Path realDirectory = directory.toRealPath();
        if (!realDirectory.equals(directory.toAbsolutePath().normalize())) return false;
        Path real = file.toRealPath();
        if (!real.startsWith(realDirectory) || !real.getParent().equals(realDirectory)) return false;
        if (SensitivePathGuard.isSensitivePath(realDirectory)) return false;
        try {
            if (((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() > 1) return false;
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // Windows does not expose unix:nlink. Real-path checks still apply.
        }
        return true;
    }

    /** Nearest Git boundary (including worktree .git files); without Git, only cwd is used. */
    public static Path discoverRoot(Path cwd) {
        Path base = cwd.toAbsolutePath().normalize();
        try { base = base.toRealPath(); } catch (IOException ignored) { }
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path current = base;
        for (int i = 0; i < 64 && current != null && current.getParent() != null; i++) {
            if (exists(current.resolve(".git"))) return current;
            if (current.equals(home)) break;
            current = current.getParent();
        }
        return base;
    }

    /** Explicit /init creates a fixed template only, never overwrites an existing primary or fallback. */
    public String initialize() {
        Path target = root.resolve(PRIMARY);
        if (List.of(PRIMARY, "CLAUDE.md", "AGENT.md", FALLBACK).stream().anyMatch(name -> exists(root.resolve(name)))) {
            return "已有项目规则文件; 未覆盖, 也未新建更高优先级文件。";
        }
        try {
            if (!Files.isDirectory(root) || !root.toRealPath().equals(root)
                    || SensitivePathGuard.isSensitivePath(root.toRealPath())) {
                return "项目边界不可写; 未创建指令文件。";
            }
            Files.writeString(target, template(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return "已创建 JHERMES.md 模板; 手工填写后下一次提交任务自动加载。";
        } catch (IOException | RuntimeException e) {
            return "创建 JHERMES.md 失败; 未覆盖已有文件。";
        }
    }

    static String template() {
        return "# 项目指令\n\n"
                + "## 项目概况\n- 请填写项目用途、技术栈和主要模块。\n\n"
                + "## 修改原则\n- 先阅读相关代码，只修改本次需求涉及的内容。\n"
                + "- 保留用户已有修改，不自动提交、推送或删除数据。\n\n"
                + "## 编码规范\n- 沿用现有命名、格式和注释风格，使用项目相对路径。\n\n"
                + "## 验证要求\n- 修改后运行相关测试；如实区分测试、运行验证和打包结果。\n\n"
                + "## 交付要求\n- 先说明结果，再给出代码位置、修改原理、方案理由和验证边界。\n\n"
                + "## 安全边界\n- 不在本文件中存放密钥、密码或访问令牌。\n"
                + "- 本文件只提供项目约定，不授予命令、文件写入或联网权限。\n";
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
