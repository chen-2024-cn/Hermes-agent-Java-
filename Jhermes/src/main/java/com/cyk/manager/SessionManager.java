package com.cyk.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cyk.bean.Session;
import com.cyk.util.AtomicFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
    会话处理
 */
public class SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final Path sessionsDir;
    private final Map<String, Session> activeSessions;

    /** 会话消息上限（P0-5 R6）：统一在此注入，避免每个 Session 各自硬编码。 */
    private final int maxMessages;

    public SessionManager(Path dataDir) {
        this(dataDir, Session.DEFAULT_MAX_MESSAGES);
    }

    /**
     * @param dataDir     数据目录（{@code HERMES_HOME}）
     * @param maxMessages 会话保留的消息上限；非法值回退到默认
     */
    public SessionManager(Path dataDir, int maxMessages) {
        this.sessionsDir = dataDir.resolve("memory").resolve("sessions");
        this.activeSessions = new ConcurrentHashMap<>();
        this.maxMessages = maxMessages >= 2 ? maxMessages : Session.DEFAULT_MAX_MESSAGES;

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

        Session session;
        if (Files.exists(sessionFile)) {
            //若磁盘存在则返回
            ObjectNode json = (ObjectNode) mapper.readTree(sessionFile.toFile());
            //将json转为session
            session = Session.fromJson(sessionId, json);
        } else {
            session = new Session(sessionId);
        }
        // 上限统一由管理器注入（载入的旧会话也可能需要按新上限重新裁剪）
        session.setMaxMessages(maxMessages);
        return session;
    }


    /**
     * 保存 session（原子落盘）。
     *
     * <p>与 MEMORY.md 同类的崩溃一致性隐患：旧实现 {@code mapper.writeValue(File, ...)}
     * 是 truncate+write 非原子，进程被强杀/断电时可能把 session json 损坏成半截，
     * 导致 {@link #listSessions()} 只能跳过它（会话记录丢失）、resume 无法恢复。
     * 改为「先序列化到内存字符串，再原子写」：任何时刻磁盘上要么旧完整版本要么新完整版本。</p>
     *
     * <p>序列化失败（JsonProcessingException）在写盘前就抛出，目标文件绝不会被触碰——
     * 这也是原子写工具的额外好处：先构造完整内容再落盘，避免「写到一半发现序列化错误」。
     */
    public void saveSession(Session session) throws IOException {
        Path sessionFile = sessionsDir.resolve(session.id + ".json");
        // 先序列化为完整 JSON 字符串（含缩进便于人工排查），再原子写入
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(session.toJson());
        AtomicFileWriter.writeStringAtomically(sessionFile, json);
    }

    /**
     * 是否存在指定 id 的已持久化会话。
     *
     * @param sessionId 会话 id
     * @return 磁盘上存在对应 json 文件则为 true
     */
    public boolean sessionExists(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return Files.exists(sessionsDir.resolve(sessionId + ".json"));
    }

    /**
     * 列出全部已持久化会话，按 lastActivity 降序（最近的在前）。
     *
     * <p>供 {@code Jhermes sessions} 命令与 resume 流程使用。
     * 单个会话文件损坏时跳过并记日志，不中断整体列举。</p>
     *
     * @return 会话列表；目录为空或不存在时返回空列表
     */
    public List<Session> listSessions() {
        List<Session> result = new ArrayList<>();
        if (!Files.isDirectory(sessionsDir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(sessionsDir)) {
            List<Path> jsonFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .toList();
            for (Path file : jsonFiles) {
                String id = file.getFileName().toString().replaceFirst("\\.json$", "");
                try {
                    ObjectNode json = (ObjectNode) mapper.readTree(file.toFile());
                    result.add(Session.fromJson(id, json));
                } catch (Exception e) {
                    logger.warn("跳过损坏的会话文件 {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.error("Failed to list sessions directory: {}", e.getMessage());
        }
        result.sort(Comparator.comparingLong((Session s) -> s.lastActivity).reversed());
        return result;
    }
}