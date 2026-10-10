package com.cyk.bean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>会话消息裁剪安全（JH-SPEC-001 P0-5 R6 / AC6）</h1>
 *
 * <p>改造前 {@code Session.addMessage} 超限时按单条 {@code messages.remove(0)} 从头删，
 * 会把一个「assistant(tool_calls) + tool 响应」组切成孤立的 tool 消息或悬空的
 * tool_calls——这样的历史发给服务端会被以 400 拒绝（附录 A5）。</p>
 *
 * <p>现在按<b>回合组</b>（一条 user 到下一条 user 之前）整体裁剪，并保留一条安全底线：
 * 当剩余内容已不足一个完整回合组时，宁可超限也不再裁剪（体积超标只是成本问题，
 * 结构非法会直接中断对话）。</p>
 */
class SessionTrimTest {

    /** 构造带 N 个 tool_calls 的 assistant 消息。 */
    private static Message assistantWithCalls(String... callIds) {
        List<ToolCall> calls = new ArrayList<>();
        for (String id : callIds) {
            Function function = new Function();
            function.setName("probe_tool");
            function.setArguments("{}");
            ToolCall call = new ToolCall();
            call.setId(id);
            call.setType("function");
            call.setFunction(function);
            calls.add(call);
        }
        return new Message("assistant", "", System.currentTimeMillis(), null, null, calls);
    }

    /** 构造 tool 响应消息（必须带 tool_call_id 才能与 assistant 的 tool_calls 配对）。 */
    private static Message toolResult(String callId) {
        return new Message("tool", "result-of-" + callId, System.currentTimeMillis(), null, callId, null);
    }

    @Test
    @DisplayName("回合组整体裁剪后不留孤立 tool 消息（AC6）")
    void trimByTurnGroupKeepsToolPairing() {
        Session session = new Session("trim-groups");
        session.setMaxMessages(6);

        // 回合 1：user + assistant(2 个调用) + 2 条 tool 响应 + assistant 收尾 = 5 条
        session.addMessage("user", "q1");
        session.addMessage(assistantWithCalls("c1", "c2"));
        session.addMessage(toolResult("c1"));
        session.addMessage(toolResult("c2"));
        session.addMessage("assistant", "a1");
        // 回合 2：再加 4 条 → 合计 9 > 上限 6，触发整组裁剪
        session.addMessage("user", "q2");
        session.addMessage(assistantWithCalls("c3"));
        session.addMessage(toolResult("c3"));
        session.addMessage("assistant", "a2");

        assertThat(session.messages).hasSizeLessThanOrEqualTo(6);
        assertThat(session.findUnpairedToolMessages()).as("裁剪后配对必须完整").isEmpty();
        // 被丢弃的是完整的回合 1，保留的是完整的回合 2
        assertThat(session.messages).extracting(Message::role)
                .containsExactly("user", "assistant", "tool", "assistant");
        assertThat(session.messages.get(0).content()).isEqualTo("q2");
    }

    @Test
    @DisplayName("全 user 消息的退化场景：仍按改造前语义裁剪到最后 N 条")
    void allUserMessagesTrimToTail() {
        Session session = new Session("trim-users");
        for (int i = 0; i < 150; i++) {
            session.addMessage("user", "msg-" + i);
        }

        // 每条 user 自成一个回合组，回合组裁剪退化为逐条裁剪，
        // 与既有 SessionManagerTest.messagesShouldBeTruncatedTo100 的期望一致
        assertThat(session.messages).hasSize(Session.DEFAULT_MAX_MESSAGES);
        assertThat(session.messages.get(0).content()).isEqualTo("msg-50");
        assertThat(session.messages.get(99).content()).isEqualTo("msg-149");
    }

    @Test
    @DisplayName("安全底线：单个超大回合组宁可不裁也不切成非法结构")
    void oversizedSingleTurnIsNotCorrupted() {
        Session session = new Session("one-big-turn");
        session.setMaxMessages(3);

        session.addMessage("user", "唯一一轮提问");
        session.addMessage(assistantWithCalls("c1", "c2", "c3"));
        session.addMessage(toolResult("c1"));
        session.addMessage(toolResult("c2"));
        session.addMessage(toolResult("c3"));
        session.addMessage("assistant", "收尾");

        assertThat(session.messages)
                .as("找不到下一个回合边界时必须停止裁剪")
                .hasSize(6);
        assertThat(session.findUnpairedToolMessages()).isEmpty();
    }

    @Test
    @DisplayName("非法上限（< 2）被忽略，保留默认值")
    void invalidMaxIsIgnored() {
        Session session = new Session("invalid-max");
        session.setMaxMessages(0);
        assertThat(session.getMaxMessages()).isEqualTo(Session.DEFAULT_MAX_MESSAGES);

        session.setMaxMessages(-5);
        assertThat(session.getMaxMessages()).isEqualTo(Session.DEFAULT_MAX_MESSAGES);

        session.setMaxMessages(7);
        assertThat(session.getMaxMessages()).isEqualTo(7);
    }

    @Test
    @DisplayName("载入超限的旧会话文件时也会被整组裁剪")
    void loadedSessionIsTrimmedOnParse() throws Exception {
        // 手工构造一份「旧版」会话文件：120 条 user 消息，无工具字段
        ObjectNode json = new ObjectMapper().createObjectNode();
        json.put("id", "legacy-big");
        json.put("lastActivity", System.currentTimeMillis());
        var array = json.putArray("messages");
        for (int i = 0; i < 120; i++) {
            var msg = array.addObject();
            msg.put("role", "user");
            msg.put("content", "m-" + i);
            msg.put("timestamp", 1000L + i);
        }

        Session loaded = Session.fromJson("legacy-big", json);

        assertThat(loaded.messages).hasSize(Session.DEFAULT_MAX_MESSAGES);
        assertThat(loaded.messages.get(0).content()).isEqualTo("m-20");
        assertThat(loaded.findUnpairedToolMessages()).isEmpty();
    }
}
