package com.cyk.bean;

import com.cyk.manager.SessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <h1>会话格式向后兼容持久化（JH-SPEC-001 P0-5 R5 / AC5）</h1>
 *
 * <p>CC-2 红线：既有 {@code memory/sessions/*.json} 必须可读，新增字段必须可选，
 * <b>存储路径与文件结构不得改变</b>（规范 C-03 明确「不得改存储格式与路径」）。</p>
 *
 * <p>本测试同时覆盖改造要修的真实缺陷：改造前只持久化 role 与 content，
 * 含工具调用的会话 resume 后 tool 消息丢失 tool_call_id，请求体被服务端以 400 拒绝（附录 A5）。</p>
 */
class SessionFormatCompatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dataDir;

    /** 手工构造一份「旧版（v2.1.0 及之前）」会话文件内容。 */
    private static ObjectNode legacyJson(String... contents) {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("id", "legacy-session");
        json.put("lastActivity", 1_700_000_000_000L);
        json.put("platform", "cli");
        json.put("chat_type", "dm");
        var array = json.putArray("messages");
        long ts = 1_700_000_000_000L;
        for (int i = 0; i < contents.length; i += 2) {
            var msg = array.addObject();
            msg.put("role", contents[i]);
            msg.put("content", contents[i + 1]);
            msg.put("timestamp", ts++);
        }
        json.putObject("metadata").put("model", "test-model");
        return json;
    }

    @Test
    @DisplayName("旧格式文件（无工具字段）可正常读取，工具字段为 null")
    void legacySessionFileIsReadable() {
        ObjectNode json = legacyJson("user", "你好", "assistant", "你好，有什么可以帮你？");

        Session session = Session.fromJson("legacy-session", json);

        assertThat(session.messages).hasSize(2);
        assertThat(session.messages.get(0).role()).isEqualTo("user");
        assertThat(session.messages.get(0).content()).isEqualTo("你好");
        assertThat(session.messages.get(0).toolCalls()).isNull();
        assertThat(session.messages.get(0).toolCallId()).isNull();
        assertThat(session.messages.get(0).name()).isNull();
        assertThat(session.metadata).containsEntry("model", "test-model");
        assertThat(session.platform).isEqualTo("cli");
    }

    @Test
    @DisplayName("tool_calls 与 tool_call_id 经磁盘往返后完整保留（AC5）")
    void toolFieldsSurviveDiskRoundTrip() throws Exception {
        Session saved = new Session("with-tools");

        ModelMessage assistant = new ModelMessage();
        assistant.setRole("assistant");
        assistant.setContent("");
        Function function = new Function();
        function.setName("read_file");
        function.setArguments("{\"path\":\"a.txt\"}");
        ToolCall call = new ToolCall();
        call.setId("call_77");
        call.setType("function");
        call.setFunction(function);
        assistant.setToolCalls(List.of(call));

        saved.addMessage(ModelMessage.user("读一下 a.txt"));
        saved.addMessage(assistant);
        saved.addMessage(ModelMessage.tool("{\"content\":\"hi\"}", "call_77"));
        saved.addMessage(ModelMessage.assistant("内容是 hi"));

        new SessionManager(dataDir).saveSession(saved);

        Session reloaded = new SessionManager(dataDir).getSession("with-tools");
        assertThat(reloaded.messages).hasSize(4);
        assertThat(reloaded.findUnpairedToolMessages()).as("配对必须完整").isEmpty();

        Message restoredAssistant = reloaded.messages.get(1);
        assertThat(restoredAssistant.hasToolCalls()).isTrue();
        assertThat(restoredAssistant.toolCalls()).hasSize(1);
        assertThat(restoredAssistant.toolCalls().get(0).getId()).isEqualTo("call_77");
        assertThat(restoredAssistant.toolCalls().get(0).getFunction().getName()).isEqualTo("read_file");
        assertThat(restoredAssistant.toolCalls().get(0).getFunction().getArguments())
                .isEqualTo("{\"path\":\"a.txt\"}");

        Message restoredTool = reloaded.messages.get(2);
        assertThat(restoredTool.isToolMessage()).isTrue();
        assertThat(restoredTool.toolCallId()).isEqualTo("call_77");

        // 还原为 API 消息：resume 后请求体必须每条 tool 消息都带 tool_call_id
        ModelMessage asApi = restoredTool.toModelMessage();
        assertThat(asApi.getToolCallId()).isEqualTo("call_77");
        assertThat(asApi.getRole()).isEqualTo("tool");
    }

    @Test
    @DisplayName("新会话文件不写入值为 null 的可选键（旧读新也不受干扰）")
    void nullOptionalFieldsAreNotSerialized() throws Exception {
        Session session = new Session("clean");
        session.addMessage("user", "普通消息");

        String json = MAPPER.writeValueAsString(session.toJson());

        assertThat(json).contains("普通消息");
        assertThat(json).as("无工具字段时不应出现 tool_calls 键").doesNotContain("tool_calls");
        assertThat(json).doesNotContain("tool_call_id");
    }

    @Test
    @DisplayName("仅有工具调用、content 为 null 的 assistant 可往返（不会写成字符串 null）")
    void toolOnlyAssistantRoundTrips() throws Exception {
        Session session = new Session("tool-only");

        ModelMessage assistant = new ModelMessage();
        assistant.setRole("assistant");
        // 刻意不设置 content：服务端在纯工具调用轮可以不给正文
        Function function = new Function();
        function.setName("run_command");
        function.setArguments("not-json");
        ToolCall call = new ToolCall();
        call.setId("call_x");
        call.setFunction(function);
        assistant.setToolCalls(List.of(call));

        session.addMessage(assistant);
        String json = MAPPER.writeValueAsString(session.toJson());
        assertThat(json).as("null content 不应被序列化为 \"null\"").doesNotContain("\"content\":\"null\"");

        Session reloaded = roundTripThroughDisk(session);
        Message restored = reloaded.messages.get(0);
        assertThat(restored.content()).isNull();
        assertThat(restored.hasToolCalls()).isTrue();
    }

    @Test
    @DisplayName("tool_calls 结构损坏时降级为纯文本，不让整个 resume 失败")
    void corruptedToolCallsDegradeGracefully() throws Exception {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("id", "broken");
        var array = json.putArray("messages");
        var bad = array.addObject();
        bad.put("role", "assistant");
        bad.put("content", "半截数据");
        // tool_calls 是数组但元素不是对象 → 反序列化必然失败
        bad.putArray("tool_calls").add("i-am-not-an-object");

        Session[] holder = new Session[1];
        assertThatCode(() -> holder[0] = Session.fromJson("broken", json)).doesNotThrowAnyException();
        assertThat(holder[0].messages).hasSize(1);
        assertThat(holder[0].messages.get(0).content()).isEqualTo("半截数据");
        assertThat(holder[0].messages.get(0).toolCalls())
                .as("损坏的 tool_calls 应降级为 null 而非抛异常")
                .isNull();
    }

    @Test
    @DisplayName("存储路径与文件名不变：memory/sessions/&lt;id&gt;.json（规范 C-03 硬约束）")
    void storagePathIsUnchanged() throws Exception {
        SessionManager manager = new SessionManager(dataDir);
        Session session = manager.getSession("cli12345678");
        session.addMessage("user", "hi");
        manager.saveSession(session);

        Path expected = dataDir.resolve("memory").resolve("sessions").resolve("cli12345678.json");
        assertThat(expected).as("会话文件路径必须保持 memory/sessions/<id>.json").exists();

        String content = Files.readString(expected, StandardCharsets.UTF_8);
        assertThat(content).contains("\"role\"").contains("\"content\"").contains("hi");
        assertThat(manager.sessionExists("cli12345678")).isTrue();
    }

    /** 通过真实磁盘往返一次，返回重新载入的会话。 */
    private Session roundTripThroughDisk(Session original) throws Exception {
        Path dir = Files.createTempDirectory(dataDir, "rt");
        SessionManager writer = new SessionManager(dir);
        Session toSave = writer.getSession(original.id);
        for (Message m : original.messages) {
            toSave.addMessage(m);
        }
        writer.saveSession(toSave);
        return new SessionManager(dir).getSession(original.id);
    }
}
