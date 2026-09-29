package com.cyk.bean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Session {
        public final String id;
        public final List<Message> messages;
        public final Map<String, Object> metadata;
        public long lastActivity;
        
        // Session source info (for context)
        public String platform;
        public String chatId;
        public String chatName;
        public String chatType; // "dm", "group", "channel", "thread"
        public String userId;
        public String userName;
        public String threadId;
        
        public Session(String id) {
            this.id = id;
            this.messages = new ArrayList<>();
            this.metadata = new HashMap<>();
            this.lastActivity = System.currentTimeMillis();
            this.chatType = "dm";
        }
        
        public void addMessage(String role, String content) {
            messages.add(new Message(role, content, System.currentTimeMillis()));
            lastActivity = System.currentTimeMillis();
            
            // Keep only last 100 messages
            if (messages.size() > 100) {
                messages.remove(0);
            }
        }
        
        public void setMetadata(String key, Object value) {
            metadata.put(key, value);
        }
        
        public Object getMetadata(String key) {
            return metadata.get(key);
        }
        
        public ObjectNode toJson() {
            ObjectMapper mapper = new ObjectMapper();

            ObjectNode json = mapper.createObjectNode();
            json.put("id", id);
            json.put("lastActivity", lastActivity);
            
            // Source info
            if (platform != null) json.put("platform", platform);
            if (chatId != null) json.put("chat_id", chatId);
            if (chatName != null) json.put("chat_name", chatName);
            if (chatType != null) json.put("chat_type", chatType);
            if (userId != null) json.put("user_id", userId);
            if (userName != null) json.put("user_name", userName);
            if (threadId != null) json.put("thread_id", threadId);
            
            var messagesArray = json.putArray("messages");
            for (Message msg : messages) {
                ObjectNode msgJson = messagesArray.addObject();
                msgJson.put("role", msg.role());
                msgJson.put("content", msg.content());
                msgJson.put("timestamp", msg.timestamp());
            }
            
            json.set("metadata", mapper.valueToTree(metadata));
            return json;
        }
        
        public static Session fromJson(String id, ObjectNode json) {
            Session session = new Session(id);
            session.lastActivity = json.path("lastActivity").asLong();
            
            // Source info
            session.platform = json.path("platform").asText(null);
            session.chatId = json.path("chat_id").asText(null);
            session.chatName = json.path("chat_name").asText(null);
            session.chatType = json.path("chat_type").asText("dm");
            session.userId = json.path("user_id").asText(null);
            session.userName = json.path("user_name").asText(null);
            session.threadId = json.path("thread_id").asText(null);
            
            var messagesNode = json.path("messages");
            for (var msgNode : messagesNode) {
                session.messages.add(new Message(
                    msgNode.path("role").asText(),
                    msgNode.path("content").asText(),
                    msgNode.path("timestamp").asLong()
                ));
            }
            
            var metadataNode = json.path("metadata");
            metadataNode.fields().forEachRemaining(entry -> {
                session.metadata.put(entry.getKey(), entry.getValue());
            });
            
            return session;
        }
    }
    
