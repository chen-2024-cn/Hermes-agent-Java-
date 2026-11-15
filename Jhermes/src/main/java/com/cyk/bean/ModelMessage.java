package com.cyk.bean;


import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Message types for LLM API communication.
 * Compatible with OpenAI-style chat completions API.
 */
public class ModelMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String role;
    private String content;
    private String name;

    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    @JsonProperty("tool_call_id")
    private String toolCallId;

    // For multimodal content
    private List<ContentPart> contentParts;

    public ModelMessage() {}

    public ModelMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static ModelMessage system(String content) {
        return new ModelMessage("system", content);
    }

    public static ModelMessage user(String content) {
        return new ModelMessage("user", content);
    }

    public static ModelMessage userMultimodal(String text, List<String> images) {
        ModelMessage message = user(text);
        if (images == null || images.isEmpty()) return message;
        List<ContentPart> parts = new ArrayList<>();
        if (text != null && !text.isBlank()) parts.add(ContentPart.text(text));
        images.forEach(url -> parts.add(ContentPart.image(url)));
        message.setContentParts(parts);
        return message;
    }

    /** Safe projection for learning/diagnostics, without duplicating image payloads. */
    public ModelMessage textOnlyCopy() {
        ModelMessage copy = new ModelMessage(role, content);
        copy.setName(name);
        copy.setToolCallId(toolCallId);
        copy.setToolCalls(toolCalls);
        long images = imageCount();
        if (images > 0) copy.setContent((content == null ? "" : content) + "\n[附 " + images + " 张图片]");
        return copy;
    }

    public long imageCount() {
        return contentParts == null ? 0 : contentParts.stream()
                .filter(part -> "image_url".equals(part.getType())).count();
    }

    public static ModelMessage assistant(String content) {
        return new ModelMessage("assistant", content);
    }

    public static ModelMessage tool(String content, String toolCallId) {
        ModelMessage msg = new ModelMessage("tool", content);
        msg.toolCallId = toolCallId;
        return msg;
    }

    // Getters and setters
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public List<ContentPart> getContentParts() { return contentParts; }
    public void setContentParts(List<ContentPart> contentParts) { this.contentParts = contentParts; }

    /**
     * Convert this message to a Jackson JsonNode for API serialization.
     * This ensures proper field naming and null handling.
     */
    public JsonNode toJsonNode() {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("role", role);
        if (contentParts != null && !contentParts.isEmpty()) {
            json.set("content", MAPPER.valueToTree(contentParts));
        } else if (content != null) {
            json.put("content", content);
        }
        if (name != null) {
            json.put("name", name);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            json.set("tool_calls", MAPPER.valueToTree(toolCalls));
        }
        if (toolCallId != null) {
            json.put("tool_call_id", toolCallId);
        }
        return json;
    }

    /**
     * Convert a list of ModelMessages to a list of JsonNodes.
     */
    public static List<JsonNode> toJsonNodeList(List<ModelMessage> messages) {
        List<JsonNode> result = new ArrayList<>();
        for (ModelMessage msg : messages) {
            result.add(msg.toJsonNode());
        }
        return result;
    }




}
