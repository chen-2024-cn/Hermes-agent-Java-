package com.cyk.bean;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A single trajectory entry representing a completed session.
 * Stores conversation history and metadata for learning.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TrajectoryEntry {
    
    private String id;
    private List<ModelMessage> conversations;
    private Instant timestamp;
    private String model;
    private boolean completed;
    private String sessionId;
    private Map<String, Object> metadata;
    
    // Compression tracking
    private boolean compressed;
    private Integer originalTokenCount;
    private Integer compressedTokenCount;
    private String compressionSummary;
    
    // Learning outcomes
    private List<String> extractedInsights;
    private List<String> skillsCreated;
    private List<String> memoriesSaved;
    
    public TrajectoryEntry() {
        this.timestamp = Instant.now();
        this.id = java.util.UUID.randomUUID().toString();
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public List<ModelMessage> getConversations() { return conversations; }
    public void setConversations(List<ModelMessage> conversations) { this.conversations = conversations; }
    
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    
    public boolean isCompressed() { return compressed; }
    public void setCompressed(boolean compressed) { this.compressed = compressed; }
    
    public Integer getOriginalTokenCount() { return originalTokenCount; }
    public void setOriginalTokenCount(Integer originalTokenCount) { this.originalTokenCount = originalTokenCount; }
    
    public Integer getCompressedTokenCount() { return compressedTokenCount; }
    public void setCompressedTokenCount(Integer compressedTokenCount) { this.compressedTokenCount = compressedTokenCount; }
    
    public String getCompressionSummary() { return compressionSummary; }
    public void setCompressionSummary(String compressionSummary) { this.compressionSummary = compressionSummary; }
    
    public List<String> getExtractedInsights() { return extractedInsights; }
    public void setExtractedInsights(List<String> extractedInsights) { this.extractedInsights = extractedInsights; }
    
    public List<String> getSkillsCreated() { return skillsCreated; }
    public void setSkillsCreated(List<String> skillsCreated) { this.skillsCreated = skillsCreated; }
    
    public List<String> getMemoriesSaved() { return memoriesSaved; }
    public void setMemoriesSaved(List<String> memoriesSaved) { this.memoriesSaved = memoriesSaved; }
}
