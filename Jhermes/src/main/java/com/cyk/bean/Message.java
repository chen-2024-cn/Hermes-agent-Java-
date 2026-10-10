package com.cyk.bean;

import java.util.List;

/**
 * 会话持久化消息（{@code memory/sessions/<id>.json} 的 {@code messages[]} 元素）。
 *
 * <p>与 {@link ModelMessage}（发给服务端的 API DTO）分工：本 record 只管<b>落盘</b>。
 * JH-SPEC-001 P0-5 R5 要求两者字段对齐——改造前只持久化 {@code role} 与 {@code content}，
 * 导致含工具调用的会话 resume 后请求体缺失 {@code tool_call_id}，服务端以 400 拒绝（附录 A5）。</p>
 *
 * <h2>向后兼容（CC-2 红线）</h2>
 * <ul>
 *   <li>新增的 {@code name} / {@code toolCallId} / {@code toolCalls} 全部为<b>可选</b>：
 *       旧版 JSON 文件缺少这些键时反序列化为 null，读取不报错。</li>
 *   <li>序列化时 null 字段<b>不写入</b>，避免给旧文件凭空增加噪音键。</li>
 *   <li>保留三参构造器，既有调用点（{@code new Message(role, content, ts)}）与既有测试不破。</li>
 * </ul>
 *
 * @param role       消息角色：system / user / assistant / tool
 * @param content    文本内容（assistant 仅调用工具时可能为 null）
 * @param timestamp  落盘时间戳（毫秒）
 * @param name       可选：工具名（部分供应商要求 tool 消息带 name）
 * @param toolCallId 可选：tool 消息对应的 tool_call id，服务端据此匹配请求
 * @param toolCalls  可选：assistant 消息发起的工具调用列表
 */
public record Message(String role, String content, long timestamp,
                      String name, String toolCallId, List<ToolCall> toolCalls) {

    /**
     * 兼容构造：不带工具字段的普通消息（既有调用点与旧文件反序列化路径均走此构造）。
     */
    public Message(String role, String content, long timestamp) {
        this(role, content, timestamp, null, null, null);
    }

    /**
     * 从 API 消息构造持久化消息。
     *
     * <p>{@code content} 允许为 null：assistant 仅发起工具调用时服务端可以不给正文，
     * {@code ModelMessage.toJsonNode()} 对 null content 的语义也是省略该键，
     * 持久化侧沿用同样语义，避免把 null 写成字符串 "null"。</p>
     */
    public static Message from(ModelMessage message) {
        return new Message(message.getRole(), message.getContent(), System.currentTimeMillis(),
                message.getName(), message.getToolCallId(), message.getToolCalls());
    }

    /**
     * 还原为可直接下发服务端的 API 消息（resume 路径使用）。
     *
     * <p>三参构造只带 role/content，会把 tool_calls 与 tool_call_id 丢掉——
     * 那正是改造前 resume 会话非法的根因，故必须走本方法做完整还原。</p>
     */
    public ModelMessage toModelMessage() {
        ModelMessage message = new ModelMessage(role, content);
        message.setName(name);
        message.setToolCallId(toolCallId);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            message.setToolCalls(toolCalls);
        }
        return message;
    }

    /** @return 是否为 tool 角色消息（裁剪配对校验用） */
    public boolean isToolMessage() {
        return "tool".equals(role);
    }

    /** @return 是否为发起工具调用的 assistant 消息（裁剪配对校验用） */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
