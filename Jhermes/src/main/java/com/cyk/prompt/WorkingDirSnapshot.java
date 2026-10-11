package com.cyk.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <h1>工作目录快照（working-dir.md 段的模板数据源）</h1>
 *
 * <p>Claude Code 式目录感知：把用户启动终端时所在的目录（JVM {@code user.dir}）连同
 * 顶层文件/目录清单固化成一份只读快照，供 {@code PromptComposer} 填充
 * {@code {{cwd}}} / {@code {{os}}} / {@code {{home}}} / {@code {{entries}}} 四个模板变量。
 * 模型由此知道用户正在哪个项目里提问、相对路径以何为基准，优先实探而非猜测。</p>
 *
 * <p><b>容错</b>：目录不可读（权限、网络盘断开）→ 条目清单为空，快照仍可用，
 * 绝不因 IO 失败阻断提示词装配（对齐旧 {@code Agent#buildWorkingDirectoryContext} 行为）。</p>
 *
 * <p>期 3 保持旧版行为原样迁移；期 4（R8）将在条目采集处叠加控制字符过滤与
 * 注入话术检查——文件名可被不可信仓库控制，属提示词注入面。</p>
 *
 * @param cwd     工作目录绝对规范化路径
 * @param osName  操作系统名（{@code os.name}）
 * @param home    用户主目录（{@code user.home}）
 * @param entries 顶层条目（目录带 {@code /} 后缀，按名排序，最多 {@link #MAX_ENTRIES} 条 + 截断标记）
 */
public record WorkingDirSnapshot(Path cwd, String osName, String home, List<String> entries) {

    private static final Logger logger = LoggerFactory.getLogger(WorkingDirSnapshot.class);

    /** 顶层条目上限（沿袭旧版：防大目录撑爆 prompt）。 */
    static final int MAX_ENTRIES = 50;

    public WorkingDirSnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** 生产入口：捕获当前进程的工作目录。 */
    public static WorkingDirSnapshot capture() {
        return capture(Path.of(System.getProperty("user.dir")));
    }

    /**
     * 捕获指定目录（测试注入用）。
     *
     * @param dir 目标目录；null 时退化为进程工作目录
     */
    public static WorkingDirSnapshot capture(Path dir) {
        Path base = dir != null ? dir : Path.of(System.getProperty("user.dir"));
        Path cwd = base.toAbsolutePath().normalize();
        return new WorkingDirSnapshot(cwd,
                System.getProperty("os.name"),
                System.getProperty("user.home"),
                listTopLevelEntries(cwd, MAX_ENTRIES));
    }

    /**
     * 列出目录顶层条目（目录名带 {@code /} 后缀），最多 {@code max} 条，
     * 超出时追加省略标记。不可读时返回空列表（容错铁律：绝不让快照失败影响对话启动）。
     */
    private static List<String> listTopLevelEntries(Path dir, int max) {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        if (names.size() <= max) {
                            names.add(p.getFileName() + (Files.isDirectory(p) ? "/" : ""));
                        }
                    });
        } catch (IOException | RuntimeException e) {
            logger.warn("无法列出工作目录 {}: {}", dir, e.getMessage());
            return List.of();
        }
        if (names.size() > max) {
            List<String> truncated = new ArrayList<>(names.subList(0, max));
            truncated.add("...（共 " + names.size() + "+ 项，已截断，可用 search_files 进一步探查）");
            return truncated;
        }
        return names;
    }

    /**
     * 渲染 {@code {{entries}}} 变量：有条目时为「标题行 + 4 空格缩进清单」，
     * 无条目时为空串（模板行随之消失，不留孤立空行——composer 装配时对段正文 strip）。
     */
    public String entriesBlock() {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("- 工作目录顶层内容:");
        for (String entry : entries) {
            sb.append("\n    ").append(entry);
        }
        return sb.toString();
    }
}
