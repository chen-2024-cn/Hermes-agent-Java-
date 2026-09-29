package com.cyk.manager;

import com.cyk.bean.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * SessionManager 的持久化、列举与恢复测试。
 *
 * <p>覆盖本轮修复 #3：会话可被列出并跨进程恢复。每个测试使用独立的临时目录，
 * {@code SessionManager} 构造参数即 dataDir，会话存放于 {@code dataDir/memory/sessions}。</p>
 */
class SessionManagerTest {

    @TempDir
    Path tempDir;

    private SessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new SessionManager(tempDir);
    }

    /** 会话文件应落在 tempDir/memory/sessions/ 下。 */
    private Path sessionsDir() {
        return tempDir.resolve("memory").resolve("sessions");
    }

    private Session buildSession(String id, long lastActivity, String... userMessages) throws IOException {
        Session session = new Session(id);
        for (String m : userMessages) {
            session.addMessage("user", m);
        }
        // 必须在 addMessage 之后再设置 lastActivity：
        // Session.addMessage() 内部会把 lastActivity 重置为当前时间，先设会被覆盖。
        session.lastActivity = lastActivity;
        manager.saveSession(session);
        return session;
    }

    @Test
    void saveAndLoadShouldRoundTrip() throws IOException {
        Session session = new Session("abc123");
        session.addMessage("user", "你好");
        session.addMessage("assistant", "你好，有什么可以帮你？");
        manager.saveSession(session);

        // 新实例从磁盘重新加载
        SessionManager reloaded = new SessionManager(tempDir);
        Session loaded = reloaded.getSession("abc123");

        assertThat(loaded.messages).hasSize(2);
        assertThat(loaded.messages.get(0).role()).isEqualTo("user");
        assertThat(loaded.messages.get(0).content()).isEqualTo("你好");
        assertThat(loaded.messages.get(1).role()).isEqualTo("assistant");
    }

    @Test
    void sessionExistsShouldReflectDiskState() throws IOException {
        assertThat(manager.sessionExists("ghost")).isFalse();

        buildSession("real", System.currentTimeMillis(), "hi");

        assertThat(manager.sessionExists("real")).isTrue();
        assertThat(manager.sessionExists("ghost")).isFalse();
        assertThat(manager.sessionExists(null)).isFalse();
        assertThat(manager.sessionExists("")).isFalse();
    }

    @Test
    void listSessionsShouldReturnEmptyWhenNoneExist() {
        assertThat(manager.listSessions()).isEmpty();
    }

    @Test
    void listSessionsShouldReturnAllSaved() throws IOException {
        buildSession("s1", System.currentTimeMillis(), "msg1");
        buildSession("s2", System.currentTimeMillis(), "msg2");
        buildSession("s3", System.currentTimeMillis(), "msg3");

        List<Session> sessions = manager.listSessions();

        assertThat(sessions).hasSize(3);
        assertThat(sessions).extracting(s -> s.id)
                .containsExactlyInAnyOrder("s1", "s2", "s3");
    }

    @Test
    void listSessionsShouldBeSortedByLastActivityDescending() throws IOException {
        // 故意乱序写入：old(最旧) → new(最新) → mid(中间)
        buildSession("mid", 2000L, "m");
        buildSession("old", 1000L, "o");
        buildSession("new", 3000L, "n");

        List<Session> sessions = manager.listSessions();

        // 最近活跃的应排在第一位（resume --last 依赖此顺序）
        assertThat(sessions).extracting(s -> s.id)
                .containsExactly("new", "mid", "old");
    }

    @Test
    void listSessionsShouldSkipCorruptedFile() throws IOException {
        buildSession("good", System.currentTimeMillis(), "ok");

        // 写入一个非法 JSON 文件模拟损坏
        Files.writeString(sessionsDir().resolve("broken.json"), "{ this is not valid json");

        List<Session> sessions = manager.listSessions();

        // 损坏文件被跳过，不影响正常会话列举
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).id).isEqualTo("good");
    }

    @Test
    void getSessionShouldCreateEmptyWhenMissing() {
        Session session = manager.getSession("brand-new");

        assertThat(session).isNotNull();
        assertThat(session.id).isEqualTo("brand-new");
        assertThat(session.messages).isEmpty();
    }

    @Test
    void messagesShouldBeTruncatedTo100() {
        Session session = new Session("big");
        for (int i = 0; i < 150; i++) {
            session.addMessage("user", "msg-" + i);
        }

        // Session.addMessage 内部滚动保留最近 100 条
        assertThat(session.messages).hasSize(100);
        assertThat(session.messages.get(0).content()).isEqualTo("msg-50");
        assertThat(session.messages.get(99).content()).isEqualTo("msg-149");
    }
}
