package com.cyk.bean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TrajectorySession {
        private String sessionId;
        private String model;
        private List<ModelMessage> messages = new ArrayList<>();
        private Instant startTime;
        
        public TrajectorySession(String sessionId, String model) {
            this.sessionId = sessionId;
            this.model = model;
            this.startTime = Instant.now();
        }
        
        public void addMessage(ModelMessage message) {
            messages.add(message);
        }
        
        public TrajectoryEntry toEntry(boolean completed) {
            TrajectoryEntry entry = new TrajectoryEntry();
            entry.setSessionId(sessionId);
            entry.setModel(model);
            entry.setCompleted(completed);
            entry.setConversations(new ArrayList<>(messages));
            entry.setMetadata(Map.of(
                "duration_seconds", java.time.Duration.between(startTime, Instant.now()).getSeconds(),
                "message_count", messages.size()
            ));
            return entry;
        }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ModelMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ModelMessage> messages) {
        this.messages = messages;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }
}