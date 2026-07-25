package com.cyk.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cyk.bean.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
    会话处理
 */
public class SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final Path sessionsDir;
    private final Map<String, Session> activeSessions;
    
    public SessionManager(Path dataDir) {
        this.sessionsDir = dataDir.resolve("memory").resolve("sessions");
        this.activeSessions = new ConcurrentHashMap<>();
        
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            logger.error("Failed to create sessions directory: {}", e.getMessage());
        }
    }

    /**
     * 获取session，如果不存在则创建
     */
    public Session getSession(String sessionId) {
        return activeSessions.computeIfAbsent(sessionId, id -> {
            try {
                return loadSession(id);
            } catch (Exception e) {
                logger.error("Failed to load session {}: {}", id, e.getMessage(), e);
                return new Session(id);
            }
        });
    }


    /**
     * 加载session
     */
    private Session loadSession(String sessionId) throws IOException {
        Path sessionFile = sessionsDir.resolve(sessionId + ".json");

        if (Files.exists(sessionFile)) {
            //若磁盘存在则返回
            ObjectNode json = (ObjectNode) mapper.readTree(sessionFile.toFile());
            //将json转为session
            Session session = Session.fromJson(sessionId, json);
            return session;
        }

        return new Session(sessionId);
    }


    /**
     * 保存session
     */
    public void saveSession(Session session) throws IOException {
        Path sessionFile = sessionsDir.resolve(session.id + ".json");
        mapper.writeValue(sessionFile.toFile(), session.toJson());
    }
}