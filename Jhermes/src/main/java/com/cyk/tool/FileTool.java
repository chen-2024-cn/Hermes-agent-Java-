package com.cyk.tool;

import com.cyk.bean.ToolEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FileTool {

    public static final Logger logger = LoggerFactory.getLogger(FileTool.class);
    public static final long MAX_FILE_SIZE = 1024 * 1024 * 10;//10mb
    private static final int MAX_RESULTS = 100;

    /**
     * 读取文件
     */
    public static String readFile(Map<String, Object> args) {
        try {
            //第一步：获取参数
            //获取参数，举例： 你传 {path: "test.txt", offset: 5, limit: 10} → 读第5行到第14行
            String pathStr = (String) args.get("path");
            if (pathStr == null || pathStr.isBlank()) {
                return ToolRegistry.toolError("Path parameter is required");
            }
            int offset = args.containsKey("offset") ? ((Number) args.get("offset")).intValue() : 1;
            int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 1;

            //第二步：路径规范化
            //Paths.get(pathStr)：把字符串变成 Path 对象
            //.toAbsolutePath()：转成绝对路径（你传 ./test.txt → D:\项目\test.txt）
            //.normalize()：去掉多余的 . 和 ..
            Path path = Paths.get(pathStr).toAbsolutePath().normalize();

            //第三步：安全检查
            //判断path是否存在黑名单
            if (!isPathAllowed(path)) {
                return ToolRegistry.toolError("文件路径非法：" + path);
            }

            //第四步：大小检查
            //判断文件大小
            long size = Files.size(path);
            if (size > MAX_FILE_SIZE) {
                return ToolRegistry.toolError("文件过大:" + path);
            }

            //第五步：读取全部行
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

            //第六步：截取需要的行
            //处理起始结束位置
            int start = Math.max(0, offset - 1);//防止 offset = 0 时出现负数下标
            int end = Math.min(lines.size(), start + limit);

            List<String> result = lines.subList(start, end);
            String content = String.join("\n", result);

            //第七步：返回结果
            return ToolRegistry.toolResult(Map.of(
                    "content", content,
                    "path", path,
                    "total_lines", result.size(),
                    "offset", start + 1,
                    "truncated", end < lines.size()// 是否被截断（true=后面还有内容没读完）
            ));

        } catch (IOException e) {
            return ToolRegistry.toolError("Read failed: " + e.getMessage());
        }

    }

    /**
     * 写入文件
     *
     * @param args
     * @return
     */
    public static String writeFile(Map<String, Object> args) {
        //第一步：获取参数
        String pathStr = (String) args.get("path");
        String content = (String) args.get("content");
        boolean append = args.containsKey("append") && (Boolean) args.get("append");

        try {
            //第二步：安全检查 + 自动创建目录
            Path path = Paths.get(pathStr).toAbsolutePath().normalize();
            if (!isPathAllowed(path)) return ToolRegistry.toolError("Access denied: " + path);
            if (Files.isDirectory(path)) {
                return ToolRegistry.toolError("路径是目录而非文件，请指定完整文件路径（例如 D:\\English\\data.txt）");
            }
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);

            //第三步：写入
            if (append) {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                //Files.writeString 默认就是覆盖，原有内容全部清掉，写入新内容
                Files.writeString(path, content, StandardCharsets.UTF_8);
            }
            return ToolRegistry.toolResult(Map.of(
                    "path", path.toString(),
                    "success", true
            ));
        } catch (Exception e) {
            return ToolRegistry.toolError("Write failed: " + e.getMessage());
        }
    }

    /**
     * 按文件名搜索
     */
    public static String searchFiles(Map<String, Object> args) {
        String pattern = (String) args.get("pattern");
        String pathStr = (String) args.getOrDefault("path", ".");
        try {
            Path root = Paths.get(pathStr).toAbsolutePath().normalize();
            if (!isPathAllowed(root)) return ToolRegistry.toolError("Access denied: " + root);
            List<String> results = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Path.of(fileName))) {
                        results.add(file.toString());
                        if (results.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return ToolRegistry.toolResult(Map.of(
                    "pattern", pattern,
                    "path", root.toString(),
                    "results", results, "count", results.size(),
                    "truncated", results.size() >= MAX_RESULTS
            ));
        } catch (Exception e) {
            return ToolRegistry.toolError("Search failed: " + e.getMessage());
        }
    }

    /**
     * 按内容搜索
     * @param args
     * @return
     */
    public static String grepFiles(Map<String, Object> args) {
        String pattern = (String) args.get("pattern");//正则表达式
        String pathStr = (String) args.get("path");
        String filePattern = (String) args.getOrDefault("file_pattern", "*");//文件名过滤（默认 *，即所有文件）
        try {
            Path root = Paths.get(pathStr).toAbsolutePath().normalize();
            if (!isPathAllowed(root)) return ToolRegistry.toolError("Access denied: " + root);

            java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);
            List<Map<String, Object>> results = new ArrayList<>();

            if (Files.isRegularFile(root)) {
                grepFile(root, regex, results);
            } else {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String fileName = file.getFileName().toString();
                        if (FileSystems.getDefault().getPathMatcher("glob:" + filePattern).matches(Path.of(fileName))) {
                            grepFile(file, regex, results);
                            if (results.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            return ToolRegistry.toolResult(Map.of(
                    "pattern", pattern, "path", root.toString(),
                    "results", results, "count", results.size(),
                    "truncated", results.size() >= MAX_RESULTS
            ));
        } catch (Exception e) {
            return ToolRegistry.toolError("Grep failed: " + e.getMessage());
        }
    }

    private static void grepFile(Path file, java.util.regex.Pattern pattern, List<Map<String, Object>> results) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    results.add(Map.of("file", file.toString(), "line", i + 1, "content", lines.get(i).trim()));
                    if (results.size() >= MAX_RESULTS) return;
                }
            }
        } catch (IOException e) {
            logger.debug("Could not read file: {}", file);
        }
    }

    /**
     * 判断路径是否在允许的范围内
     *
     * @param path
     * @return
     */
    private static boolean isPathAllowed(Path path) {
        String str = path.toString().toLowerCase();

        // 黑名单：禁止访问系统临时目录和用户 AppData 目录
        String tmpDir = System.getProperty("java.io.tmpdir");
        List<String> blocked = new ArrayList<>();
        if (tmpDir != null) {
            blocked.add(tmpDir.toLowerCase());
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            blocked.add(Paths.get(userHome, "AppData", "Local").toString().toLowerCase());
        }
        for (String s : blocked) {
            if (str.contains(s)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 注册工具
     */
    public static void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
                .name("read_file")
                .toolset("file_operations")
                .schema(Map.of(
                        "description", "Read contents of a file",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description", "File path"),
                                        "offset", Map.of("type", "integer", "description", "Start line (1-indexed)"),
                                        "limit", Map.of("type", "integer", "description", "Max lines to read")),
                                "required", List.of("path"))))
                .handler(FileTool::readFile)
                .emoji("📂")
                .build());

        registry.register(new ToolEntry.Builder()
                .name("write_file").toolset("file_operations")
                .schema(Map.of("description", "Write content to a file",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description", "File path"),
                                        "content", Map.of("type", "string", "description", "Content to write"),
                                        "append", Map.of("type", "boolean", "description", "Append instead of overwrite")),
                                "required", List.of("path", "content"))))
                .handler(FileTool::writeFile)
                .build());
        registry.register(new ToolEntry.Builder()
                .name("search_files").toolset("file_operations")
                .schema(Map.of("description", "Search for files by name pattern",
                        "parameters", Map.of("type", "object",
                                "properties", Map.of("pattern", Map.of("type", "string", "description", "Search pattern (glob)"),
                                        "path", Map.of("type", "string", "description", "Directory to search")),
                                "required", List.of("pattern"))))
                .handler(FileTool::searchFiles).build());

        registry.register(new ToolEntry.Builder()
                .name("grep_files").toolset("file_operations")
                .schema(Map.of("description", "Search file contents for pattern",
                        "parameters", Map.of("type", "object",
                                "properties", Map.of("pattern", Map.of("type", "string", "description", "Regex pattern"),
                                        "path", Map.of("type", "string", "description", "File or directory"),
                                        "file_pattern", Map.of("type", "string", "description", "Filter files by pattern")),
                                "required", List.of("pattern", "path"))))
                .handler(FileTool::grepFiles).build());
    }
}

