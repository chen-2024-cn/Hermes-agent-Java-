package com.cyk.bean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tool definition declared inside a skill's YAML front matter.
 * Each definition maps to a script in the skill directory.
 */
public class ToolDefinition {

    private String name;
    private String description;
    private Map<String, Object> parameters;
    private String handler; // relative path to script within skill directory

    public ToolDefinition() {
        this.parameters = new HashMap<>();
    }

    public ToolDefinition(String name, String description, Map<String, Object> parameters, String handler) {
        this.name = name;
        this.description = description;
        this.parameters = parameters != null ? parameters : new HashMap<>();
        this.handler = handler;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }

    public String getHandler() { return handler; }
    public void setHandler(String handler) { this.handler = handler; }

    /**
     * Build a ToolDefinition from a YAML-parsed map.
     */
    @SuppressWarnings("unchecked")
    public static ToolDefinition fromYamlMap(Map<String, Object> map) {
        ToolDefinition td = new ToolDefinition();
        td.setName(getString(map, "name"));
        td.setDescription(getString(map, "description"));
        td.setHandler(getString(map, "handler"));

        Object paramsObj = map.get("parameters");
        if (paramsObj instanceof Map) {
            td.setParameters(new HashMap<>((Map<String, Object>) paramsObj));
        }

        return td;
    }

    /**
     * Convert this definition to a YAML-serializable map (for toMarkdown).
     */
    public Map<String, Object> toYamlMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        if (description != null) map.put("description", description);
        if (parameters != null && !parameters.isEmpty()) map.put("parameters", parameters);
        if (handler != null) map.put("handler", handler);
        return map;
    }

    private static String getString(Map<String, Object> map, String key) {
        Object obj = map.get(key);
        return obj != null ? obj.toString() : null;
    }

    @Override
    public String toString() {
        return "ToolDefinition{name='" + name + "', handler='" + handler + "'}";
    }
}
