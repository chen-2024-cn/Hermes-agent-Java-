package com.cyk.bean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public class Skill {
    private static final Logger logger = LoggerFactory.getLogger(Skill.class);
    private String name;
    private String description;
    private String content;
    private String type = "reference"; // reference | workflow | tool_def
    private List<String> tags = new ArrayList<>();
    private Map<String, Object> metadata = new HashMap<>();
    private Instant createdAt;
    private Instant updatedAt;
    private int version = 1;
    private int usageCount = 0;
    private Path directory; // null for flat-file skills, skill dir for directory-based skills
    private List<ToolDefinition> tools = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public List<String> getTags() { return new ArrayList<>(tags); }
    public void setTags(List<String> tags) { this.tags = tags; }

    public Map<String, Object> getMetadata() { return new HashMap<>(metadata); }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public int getUsageCount() { return usageCount; }
    public void setUsageCount(int usageCount) { this.usageCount = usageCount; }

    public Path getDirectory() { return directory; }
    public void setDirectory(Path directory) { this.directory = directory; }

    public List<ToolDefinition> getTools() { return tools; }
    public void setTools(List<ToolDefinition> tools) { this.tools = tools != null ? tools : new ArrayList<>(); }

    /**
     * Resolve a relative path against the skill directory.
     * Returns null if this is a flat-file skill (no directory).
     * Returns null if the resolved path escapes the skill directory.
     */
    public Path resolve(String relativePath) {
        if (directory == null || relativePath == null) {
            return null;
        }
        Path resolved = directory.resolve(relativePath).normalize();
        if (!resolved.startsWith(directory)) {
            return null;
        }
        return resolved;
    }

    /**
     * Serialize this skill to a .md file content (YAML front matter + Markdown body).
     */
    public String toMarkdown() {
        Map<String, Object> frontMatter = new LinkedHashMap<>();
        frontMatter.put("name", name != null ? name : "");
        frontMatter.put("description", description != null ? description : "");
        frontMatter.put("type", type != null ? type : "reference");
        if (tags != null && !tags.isEmpty()) {
            frontMatter.put("tags", tags);
        }
        frontMatter.put("version", version);
        frontMatter.put("usage_count", usageCount);
        if (createdAt != null) {
            frontMatter.put("created_at", createdAt.toString());
        }
        if (updatedAt != null) {
            frontMatter.put("updated_at", updatedAt.toString());
        }
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> toolMaps = new ArrayList<>();
            for (ToolDefinition td : tools) {
                toolMaps.add(td.toYamlMap());
            }
            frontMatter.put("tools", toolMaps);
        }

        org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
        options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append(yaml.dump(frontMatter));
        sb.append("---\n\n");
        if (content != null) {
            sb.append(content);
        }
        return sb.toString();
    }

    /**
     * Parse a .md file content into a Skill object.
     */
    public static Skill fromMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }

        String[] parts = markdown.split("(?m)^---$", 3);
        if (parts.length < 2) {
            return null;
        }

        String yamlStr = parts.length >= 2 ? parts[1].trim() : "";
        String contentBody = parts.length >= 3 ? parts[2].trim() : "";

        if (yamlStr.isEmpty()) {
            return null;
        }

        try {
            Yaml yaml = new Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> frontMatter = yaml.load(yamlStr);

            Skill skill = new Skill();
            skill.setName(getString(frontMatter, "name"));
            skill.setDescription(getString(frontMatter, "description"));
            skill.setType(getString(frontMatter, "type", "reference"));
            skill.setContent(contentBody);

            Object tagsObj = frontMatter.get("tags");
            if (tagsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> tagList = (List<String>) tagsObj;
                skill.setTags(new ArrayList<>(tagList));
            }

            Object versionObj = frontMatter.get("version");
            if (versionObj instanceof Number) {
                skill.setVersion(((Number) versionObj).intValue());
            }

            Object usageObj = frontMatter.get("usage_count");
            if (usageObj instanceof Number) {
                skill.setUsageCount(((Number) usageObj).intValue());
            }

            String createdAtStr = getString(frontMatter, "created_at");
            if (createdAtStr != null && !createdAtStr.isEmpty()) {
                skill.setCreatedAt(Instant.parse(createdAtStr));
            } else {
                skill.setCreatedAt(Instant.now());
            }

            String updatedAtStr = getString(frontMatter, "updated_at");
            if (updatedAtStr != null && !updatedAtStr.isEmpty()) {
                skill.setUpdatedAt(Instant.parse(updatedAtStr));
            } else {
                skill.setUpdatedAt(Instant.now());
            }

            Object toolsObj = frontMatter.get("tools");
            if (toolsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> toolList = (List<Map<String, Object>>) toolsObj;
                List<ToolDefinition> parsedTools = new ArrayList<>();
                for (Map<String, Object> toolMap : toolList) {
                    ToolDefinition td = ToolDefinition.fromYamlMap(toolMap);
                    if (td.getName() != null && td.getHandler() != null) {
                        parsedTools.add(td);
                    }
                }
                skill.setTools(parsedTools);
            }

            Map<String, Object> extra = new HashMap<>(frontMatter);
            extra.remove("name");
            extra.remove("description");
            extra.remove("type");
            extra.remove("tags");
            extra.remove("tools");
            extra.remove("version");
            extra.remove("usage_count");
            extra.remove("created_at");
            extra.remove("updated_at");
            skill.setMetadata(extra);

            return skill;
        } catch (Exception e) {
            logger.warn("Failed to parse skill from markdown: {}", e.getMessage());
            return null;
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        return getString(map, key, null);
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object obj = map.get(key);
        return obj != null ? obj.toString() : defaultValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Skill skill)) return false;
        return Objects.equals(name, skill.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "Skill{name='" + name + "', type='" + type + "', version=" + version + "}";
    }
}
