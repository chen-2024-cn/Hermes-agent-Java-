package com.cyk.tool;

import com.cyk.bean.Skill;
import com.cyk.bean.ToolDefinition;
import com.cyk.bean.ToolEntry;
import com.cyk.config.HermesConfig;
import com.cyk.constant.Constants;
import com.cyk.manager.SkillManager;
import com.cyk.util.ProcessTerminator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public class SkillTool {

    private static final Logger logger = LoggerFactory.getLogger(SkillTool.class);
    private static final SkillManager skillManager = SkillManager.getInstance();
    private static ToolRegistry toolRegistry;

    /**
     * 技能脚本执行超时（秒），由 {@link #register(ToolRegistry, HermesConfig)} 从配置注入。
     *
     * <p>P0-5 R10（附录 A9）：改造前脚本执行完全无超时，一个挂死的技能脚本
     * 会把整个会话永久阻塞在 {@code waitFor()} 上，用户只能强杀进程（丢会话）。</p>
     */
    private static int scriptTimeoutSeconds = Constants.DEFAULT_SKILL_SCRIPT_TIMEOUT_SECONDS;

    /** 超时上限：防止配置写成天文数字导致护栏失效。 */
    static final int MAX_SCRIPT_TIMEOUT_SECONDS = 600;

    public static void register(ToolRegistry registry, HermesConfig config) {
        toolRegistry = registry;
        if (config != null) {
            scriptTimeoutSeconds = Math.max(1,
                    Math.min(config.getSkillScriptTimeoutSeconds(), MAX_SCRIPT_TIMEOUT_SECONDS));
        }

        // Register the skill_manage tool itself
        registry.register(new ToolEntry.Builder()
                .name("skill_manage")
                .toolset("skill")
                .schema(Map.of(
                        "description", "Manage skills — save, get, list, search, or delete reusable skills. Use this to persist useful workflows, patterns, or knowledge for future sessions.",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "action", Map.of(
                                                "type", "string",
                                                "enum", List.of("save", "get", "list", "search", "delete"),
                                                "description", "Action: 'save' to create/update a skill (requires name+content), 'get' to retrieve by name, 'list' to show all, 'search' to find by query, 'delete' to remove by name"
                                        ),
                                        "name", Map.of(
                                                "type", "string",
                                                "description", "Skill name in kebab-case (e.g., 'deploy-spring-boot'). Required for save/get/delete."
                                        ),
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "Full skill content with YAML front matter and Markdown body. Required for 'save' action. Format:\n---\nname: skill-name\ndescription: What it does\ntype: workflow|reference|tool_def\ntags: [tag1, tag2]\n---\n\n# Title\n\nStep-by-step body..."
                                        ),
                                        "query", Map.of(
                                                "type", "string",
                                                "description", "Search query string. Required for 'search' action."
                                        )
                                ),
                                "required", List.of("action")
                        )
                ))
                .handler(SkillTool::handle)
                .emoji("🛠️")
                .build());

        // Register dynamic tools declared in directory-based skills
        registerSkillTools(registry);
    }

    /**
     * Register tools declared in directory-based skills' YAML front matter.
     * Tool names are prefixed with "{skillName}__" to avoid conflicts.
     */
    private static void registerSkillTools(ToolRegistry registry) {
        for (Skill skill : skillManager.listAll()) {
            if (skill.getTools() == null || skill.getTools().isEmpty()) continue;
            if (skill.getDirectory() == null) continue;

            for (ToolDefinition td : skill.getTools()) {
                if (td.getName() == null || td.getHandler() == null) {
                    logger.warn("Skipping incomplete tool definition in skill '{}'", skill.getName());
                    continue;
                }

                String toolName = skill.getName() + "__" + td.getName();
                Map<String, Object> schema = Map.of(
                        "description", td.getDescription() != null ? td.getDescription() : "",
                        "parameters", td.getParameters() != null ? td.getParameters() : Map.of("type", "object", "properties", Map.of())
                );

                registry.register(new ToolEntry.Builder()
                        .name(toolName)
                        .toolset("skill")
                        .schema(schema)
                        .handler(createScriptHandler(skill.getDirectory(), td))
                        .emoji("🔧")
                        .build());

                logger.info("Registered skill tool: {}", toolName);
            }
        }
    }

    /**
     * Create a handler function that executes a script via ProcessBuilder.
     * Args are passed as --key value CLI arguments.
     *
     * <p>P0-5 R10（附录 A9）修正的三个真实风险：</p>
     * <ol>
     *   <li><b>无超时</b> → 加 {@code waitFor(timeout)}，超时后终止整个进程树并返回错误；</li>
     *   <li><b>先读 stdout 再读 stderr 可能死锁</b> → 改为两路并发泵（与 {@code TerminalTool} 同方案）：
     *       管道缓冲区（Windows 约 4KB）写满后子进程会阻塞，串行读取时
     *       「脚本狂写 stderr 而主线程在读 stdout」会形成永久等待；</li>
     *   <li><b>未关闭子进程 stdin</b> → 立即关闭，需要交互输入的脚本快速失败而非永久挂起。</li>
     * </ol>
     *
     * <p>命令安全门禁（经 {@code CommandPolicy} 按 WRITE 确认、脚本哈希变更失效）属
     * <b>P0-2 R14</b> 范围，本方法不提前引入，避免两个需求相互阻塞。</p>
     */
    private static Function<Map<String, Object>, String> createScriptHandler(Path skillDir, ToolDefinition td) {
        return args -> {
            Path script = skillDir.resolve(td.getHandler()).normalize();

            // Security: prevent path traversal escaping the skill directory
            if (!script.startsWith(skillDir)) {
                return ToolRegistry.toolError("Security: script path escapes skill directory");
            }
            if (!Files.exists(script)) {
                return ToolRegistry.toolError("Script not found: " + td.getHandler());
            }

            List<String> command = new ArrayList<>();
            command.add(script.toString());
            for (var entry : args.entrySet()) {
                command.add("--" + entry.getKey());
                command.add(entry.getValue() != null ? entry.getValue().toString() : "");
            }

            return runScriptWithTimeout(command, skillDir, td.getHandler(), scriptTimeoutSeconds);
        };
    }

    /**
     * 以超时保护 + 两路并发泵执行一段命令行（技能脚本的唯一执行入口）。
     *
     * <p>从 handler lambda 里抽出来的理由：AC9（「超时被终止且无死锁」）需要能
     * 在不构造真实技能目录、不污染全局 ToolRegistry 的前提下验证本方法；
     * 直接测 lambda 则需要先把用户真技能注册进单例，既慢又有副作用。</p>
     *
     * @param command        参数数组形式的命令行（不用 shell 字符串拼接，CC-4）
     * @param workDir        工作目录（技能目录）
     * @param scriptName     脚本名，仅用于错误文案与日志
     * @param timeoutSeconds 超时上限（秒）
     * @return 统一格式的工具结果/错误 JSON
     */
    static String runScriptWithTimeout(List<String> command, Path workDir, String scriptName,
                                       int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            // 禁止交互：立即关闭 stdin，等待输入的脚本会快速报错而非永久挂起
            p.getOutputStream().close();

            // 两路并发泵输出，消除串行读取的管道缓冲区死锁
            CompletableFuture<String> stdoutFuture =
                    CompletableFuture.supplyAsync(() -> pumpStream(p, true));
            CompletableFuture<String> stderrFuture =
                    CompletableFuture.supplyAsync(() -> pumpStream(p, false));

            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                // 先杀后代再杀本体：只 destroyForcibly 父进程会让脚本派生的子进程
                // 变成孤儿继续占着 CPU 与文件句柄（见 ProcessTerminator 类注释）
                ProcessTerminator.destroyTree(p);
                String partialErr = safeGet(stderrFuture);
                logger.warn("技能脚本超时（{}s），已终止进程树: {}", timeoutSeconds, scriptName);
                return ToolRegistry.toolError("技能脚本执行超时（" + timeoutSeconds + "s），已强制终止："
                        + scriptName
                        + (partialErr.isBlank() ? "" : "\n部分错误输出: " + partialErr.trim())
                        + "\n请检查脚本是否存在等待输入、死循环或网络阻塞，"
                        + "或在 config.yaml 提高 skill.script_timeout_seconds 后重试。");
            }

            int exitCode = p.exitValue();
            String stdout = safeGet(stdoutFuture);
            String stderr = safeGet(stderrFuture);

            if (exitCode != 0) {
                String errDetail = stderr.isBlank() ? "(no stderr output)" : stderr.trim();
                return ToolRegistry.toolError("Script failed (exit " + exitCode + "): " + errDetail);
            }
            return ToolRegistry.toolResult(Map.of("output", stdout.trim(), "exit_code", exitCode));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolRegistry.toolError("Script execution interrupted");
        } catch (Exception e) {
            logger.error("Script execution failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Script execution error: " + e.getMessage());
        }
    }

    /**
     * 泵干一路输出流（stdout 或 stderr）并返回全文。
     *
     * <p>读取失败返回空串而不抛异常：泵在异步线程上执行，异常会变成
     * {@code ExecutionException} 掩盖真正的失败原因（退出码或超时）。</p>
     */
    private static String pumpStream(Process process, boolean stdout) {
        try (InputStream stream = stdout ? process.getInputStream() : process.getErrorStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.debug("读取技能脚本{}失败: {}", stdout ? " stdout" : " stderr", e.getMessage());
            return "";
        }
    }

    /** 安全获取泵结果：异常/超时不影响主流程（已尽力终止进程）。 */
    private static String safeGet(CompletableFuture<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException e) {
            return "";
        }
    }

    public static String handle(Map<String, Object> args) {
        Object actionObj = args.get("action");
        if (actionObj == null) {
            return ToolRegistry.toolError("Missing required parameter: action");
        }
        String action = actionObj.toString().toLowerCase();

        return switch (action) {
            case "save" -> saveSkill(args);
            case "get" -> getSkill(args);
            case "list" -> listSkills();
            case "search" -> searchSkills(args);
            case "delete" -> deleteSkill(args);
            default -> ToolRegistry.toolError("Unknown action: " + action + ". Valid actions: save, get, list, search, delete");
        };
    }

    private static String saveSkill(Map<String, Object> args) {
        Object nameObj = args.get("name");
        Object contentObj = args.get("content");

        if (nameObj == null || nameObj.toString().trim().isEmpty()) {
            return ToolRegistry.toolError("Missing required parameter: name");
        }
        if (contentObj == null || contentObj.toString().trim().isEmpty()) {
            return ToolRegistry.toolError("Missing required parameter: content");
        }

        String content = contentObj.toString();

        try {
            Skill skill = Skill.fromMarkdown(content);
            if (skill == null) {
                skill = new Skill();
                skill.setName(nameObj.toString().trim());
                skill.setContent(content);
                skill.setType("reference");
            }

            skill.setName(nameObj.toString().trim());

            Skill saved = skillManager.save(skill);

            return ToolRegistry.toolResult(Map.of(
                    "success", true,
                    "name", saved.getName(),
                    "type", saved.getType(),
                    "version", saved.getVersion(),
                    "message", "Skill '" + saved.getName() + "' saved (v" + saved.getVersion() + ")"
            ));
        } catch (Exception e) {
            logger.error("Failed to save skill: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Failed to save skill: " + e.getMessage());
        }
    }

    private static String getSkill(Map<String, Object> args) {
        Object nameObj = args.get("name");
        if (nameObj == null || nameObj.toString().trim().isEmpty()) {
            return ToolRegistry.toolError("Missing required parameter: name");
        }

        String name = nameObj.toString().trim();
        Optional<Skill> skillOpt = skillManager.get(name);

        if (skillOpt.isEmpty()) {
            return ToolRegistry.toolError("Skill not found: " + name);
        }

        Skill skill = skillOpt.get();
        return ToolRegistry.toolResult(Map.of(
                "name", skill.getName(),
                "type", skill.getType(),
                "description", skill.getDescription() != null ? skill.getDescription() : "",
                "version", skill.getVersion(),
                "tags", skill.getTags(),
                "full_content", skill.toMarkdown()
        ));
    }

    private static String listSkills() {
        List<Skill> skills = skillManager.listAll();
        List<Map<String, Object>> skillList = new ArrayList<>();
        for (Skill s : skills) {
            skillList.add(Map.of(
                    "name", s.getName(),
                    "type", s.getType(),
                    "description", s.getDescription() != null ? s.getDescription() : "",
                    "version", s.getVersion()
            ));
        }

        return ToolRegistry.toolResult(Map.of(
                "count", skillList.size(),
                "skills", skillList
        ));
    }

    private static String searchSkills(Map<String, Object> args) {
        Object queryObj = args.get("query");
        if (queryObj == null || queryObj.toString().trim().isEmpty()) {
            return ToolRegistry.toolError("Missing required parameter: query");
        }

        String query = queryObj.toString().trim();
        List<Skill> results = skillManager.search(query);

        List<Map<String, Object>> resultList = new ArrayList<>();
        for (Skill s : results) {
            resultList.add(Map.of(
                    "name", s.getName(),
                    "type", s.getType(),
                    "description", s.getDescription() != null ? s.getDescription() : "",
                    "tags", s.getTags()
            ));
        }

        return ToolRegistry.toolResult(Map.of(
                "query", query,
                "count", resultList.size(),
                "results", resultList
        ));
    }

    private static String deleteSkill(Map<String, Object> args) {
        Object nameObj = args.get("name");
        if (nameObj == null || nameObj.toString().trim().isEmpty()) {
            return ToolRegistry.toolError("Missing required parameter: name");
        }

        String name = nameObj.toString().trim();

        // Unregister any dynamic tools before deleting the skill
        Optional<Skill> skillOpt = skillManager.get(name);
        if (skillOpt.isPresent()) {
            Skill skill = skillOpt.get();
            if (skill.getTools() != null && toolRegistry != null) {
                for (ToolDefinition td : skill.getTools()) {
                    String toolName = skill.getName() + "__" + td.getName();
                    toolRegistry.unregister(toolName);
                    logger.info("Unregistered skill tool: {}", toolName);
                }
            }
        }

        boolean deleted = skillManager.delete(name);

        if (!deleted) {
            return ToolRegistry.toolError("Skill not found: " + name);
        }

        return ToolRegistry.toolResult(Map.of(
                "success", true,
                "name", name,
                "message", "Skill '" + name + "' deleted"
        ));
    }
}
