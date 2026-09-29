package com.cyk.tool;

import com.cyk.bean.Skill;
import com.cyk.bean.ToolDefinition;
import com.cyk.bean.ToolEntry;
import com.cyk.manager.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

public class SkillTool {

    private static final Logger logger = LoggerFactory.getLogger(SkillTool.class);
    private static final SkillManager skillManager = SkillManager.getInstance();
    private static ToolRegistry toolRegistry;

    public static void register(ToolRegistry registry) {
        toolRegistry = registry;

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

            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(skillDir.toFile());
                pb.redirectErrorStream(false);
                Process p = pb.start();

                String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = p.waitFor();

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
        };
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
