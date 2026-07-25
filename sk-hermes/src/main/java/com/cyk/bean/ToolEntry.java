package com.cyk.bean;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Metadata for a single registered tool.
 */
public class ToolEntry {
    
    private final String name;
    private final String toolset;
    private final Map<String, Object> schema;
    private final Function<Map<String, Object>, String> handler;
    private final List<String> requiresEnv;
    private final boolean async;
    private final String description;
    private final String emoji;
    private final Long maxResultSizeChars;
    
    public ToolEntry(
            String name,
            String toolset,
            Map<String, Object> schema,
            Function<Map<String, Object>, String> handler,
            List<String> requiresEnv,
            boolean async,
            String description,
            String emoji,
            Long maxResultSizeChars) {
        this.name = name;
        this.toolset = toolset;
        this.schema = schema;
        this.handler = handler;
        this.requiresEnv = requiresEnv;
        this.async = async;
        this.description = description;
        this.emoji = emoji;
        this.maxResultSizeChars = maxResultSizeChars;
    }
    
    // Getters
    public String getName() { return name; }
    public String getToolset() { return toolset; }
    public Map<String, Object> getSchema() { return schema; }
    public Function<Map<String, Object>, String> getHandler() { return handler; }
    public List<String> getRequiresEnv() { return requiresEnv; }
    public boolean isAsync() { return async; }
    public String getDescription() { return description; }
    public String getEmoji() { return emoji; }
    public Long getMaxResultSizeChars() { return maxResultSizeChars; }

    /**
     * Builder for ToolEntry.
     */
    public static class Builder {
        private String name;
        private String toolset;
        private Map<String, Object> schema;
        private Function<Map<String, Object>, String> handler;
        private List<String> requiresEnv = List.of();
        private boolean async = false;
        private String description = "";
        private String emoji = "⚡";
        private Long maxResultSizeChars = null;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder toolset(String toolset) {
            this.toolset = toolset;
            return this;
        }

        public Builder schema(Map<String, Object> schema) {
            this.schema = schema;
            return this;
        }

        public Builder handler(Function<Map<String, Object>, String> handler) {
            this.handler = handler;
            return this;
        }

        public Builder requiresEnv(List<String> requiresEnv) {
            this.requiresEnv = requiresEnv;
            return this;
        }

        public Builder async(boolean async) {
            this.async = async;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder emoji(String emoji) {
            this.emoji = emoji;
            return this;
        }

        public Builder maxResultSizeChars(Long maxResultSizeChars) {
            this.maxResultSizeChars = maxResultSizeChars;
            return this;
        }

        public ToolEntry build() {
            if (name == null || toolset == null || schema == null || handler == null) {
                throw new IllegalStateException("name, toolset, schema, and handler are required");
            }
            return new ToolEntry(name, toolset, schema, handler, requiresEnv,
                    async, description, emoji, maxResultSizeChars);
        }
    }
}
