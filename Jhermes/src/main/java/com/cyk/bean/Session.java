package com.cyk.bean;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Session {
        /** 会话保留消息数的默认上限（P0-5 R6：可配置，缺省与改造前行为一致）。 */
        public static final int DEFAULT_MAX_MESSAGES = 100;

        /**
         * 共享的 Jackson 映射器。
         *
         * <p>ObjectMapper 构造成本高且线程安全，官方推荐全局复用；
         * 旧实现在 {@code toJson()} 里每次 new 一个，而静态的 {@code fromJson()}
         * 因没有实例可用而无法做嵌套结构（tool_calls）的转换。</p>
         */
        private static final ObjectMapper MAPPER = new ObjectMapper();

        public final String id;
        public final List<Message> messages;
        public final Map<String, Object> metadata;
        public long lastActivity;

        /** 当前生效的消息上限，由 {@code SessionManager} 按配置注入。 */
        private int maxMessages = DEFAULT_MAX_MESSAGES;
        
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
        
        /**
         * 追加普通文本消息（兼容旧调用点与旧测试）。
         */
        public void addMessage(String role, String content) {
            addMessage(new Message(role, content, System.currentTimeMillis()));
        }

        /**
         * 从 API 消息追加（P0-5 R5）。
         *
         * <p>Agent 逐轮持久化走本重载，以确保 {@code tool_calls} / {@code tool_call_id}
         * 不丢失——改造前只存 role 与 content，含工具调用的会话 resume 后请求体非法（附录 A5）。</p>
         */
        public void addMessage(ModelMessage message) {
            addMessage(Message.from(message));
        }

        /**
         * 追加一条已持久化形态的消息，并按上限裁剪。
         */
        public void addMessage(Message message) {
            messages.add(message);
            lastActivity = System.currentTimeMillis();
            trimToLimit();
        }

        /**
         * 设置会话消息上限（P0-5 R6）。
         *
         * @param maxMessages 上限；小于 2 的值视为非法并忽略（至少保留一轮问答）
         */
        public void setMaxMessages(int maxMessages) {
            if (maxMessages < 2) {
                return;
            }
            this.maxMessages = maxMessages;
        }

        /** @return 当前生效的消息上限 */
        public int getMaxMessages() {
            return maxMessages;
        }

        /**
         * 按「回合组」裁剪到上限（P0-5 R6）。
         *
         * <p>一个回合组 = 从一条 {@code user} 消息起，到下一条 {@code user} 之前的全部消息
         * （包含中间的 assistant/tool）。改造前按单条 {@code messages.remove(0)} 从头裁剪，
         * 可能把一组切成「只剩孤立 tool 消息」或「assistant 带 tool_calls 但响应被删」，
         * 这样的请求会被服务端以 400 拒绝（附录 A5）。</p>
         *
         * <p>安全底线：若剩余内容已不足一个完整回合组（找不到下一个 user 边界），
         * 则宁可使总量超限也不再裁剪——体积超标只是成本问题，结构非法会直接中断对话。</p>
         */
        private void trimToLimit() {
            while (messages.size() > maxMessages) {
                int nextTurnStart = indexOfNextTurnStart(1);
                if (nextTurnStart <= 0) {
                    // 无法再安全裁剪：只剩一个回合组（或开头就是孤立片段）
                    return;
                }
                messages.subList(0, nextTurnStart).clear();
            }
        }

        /**
         * 从 {@code from} 起向后找下一个回合组起点（下一条 user 消息的下标）。
         *
         * @return 下标；找不到返回 -1
         */
        private int indexOfNextTurnStart(int from) {
            for (int i = Math.max(1, from); i < messages.size(); i++) {
                if ("user".equals(messages.get(i).role())) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 校验消息序列的工具调用配对完整性：每条 tool 消息都有对应 tool_calls，
         * 且每个带 tool_calls 的 assistant 后面跟足数量相同的 tool 响应。
         *
         * <p>供 resume 与裁剪后断言使用（P0-5 AC5/AC6）；包级可见便于单测。</p>
         *
         * @return 不配对的问题描述列表；空表示结构合法
         */
        public List<String> findUnpairedToolMessages() {
            List<String> problems = new ArrayList<>();
            List<String> pendingCallIds = new ArrayList<>();
            for (Message msg : messages) {
                if (msg.hasToolCalls()) {
                    pendingCallIds.clear();
                    for (ToolCall call : msg.toolCalls()) {
                        pendingCallIds.add(call.getId());
                    }
                } else if (msg.isToolMessage()) {
                    if (pendingCallIds.isEmpty()) {
                        problems.add("孤立的 tool 消息（tool_call_id=" + msg.toolCallId() + "）");
                    } else {
                        pendingCallIds.remove(msg.toolCallId());
                    }
                }
            }
            if (!pendingCallIds.isEmpty()) {
                problems.add("悬空的 tool_calls，缺少 " + pendingCallIds.size() + " 条响应: " + pendingCallIds);
            }
            return problems;
        }
        
        public void setMetadata(String key, Object value) {
            metadata.put(key, value);
        }
        
        public Object getMetadata(String key) {
            return metadata.get(key);
        }
        
        public ObjectNode toJson() {
            ObjectNode json = MAPPER.createObjectNode();
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
                // content 可为 null（assistant 仅发起工具调用时），与 ModelMessage.toJsonNode 语义一致地省略该键
                if (msg.content() != null) {
                    msgJson.put("content", msg.content());
                }
                msgJson.put("timestamp", msg.timestamp());
                // P0-5 R5：工具字段仅在非空时写入，旧文件不会产生新键（CC-2 向后兼容）
                if (msg.name() != null) {
                    msgJson.put("name", msg.name());
                }
                if (msg.toolCallId() != null) {
                    msgJson.put("tool_call_id", msg.toolCallId());
                }
                if (msg.hasToolCalls()) {
                    msgJson.set("tool_calls", MAPPER.valueToTree(msg.toolCalls()));
                }
            }
            
            json.set("metadata", MAPPER.valueToTree(metadata));
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
                // 可选字段：旧版会话文件不含这些键，asText(null) 与 isArray 判定保证平滑读取
                String name = msgNode.path("name").asText(null);
                String toolCallId = msgNode.path("tool_call_id").asText(null);
                List<ToolCall> toolCalls = null;
                JsonNode toolCallsNode = msgNode.path("tool_calls");
                if (toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                    try {
                        toolCalls = MAPPER.convertValue(toolCallsNode,
                                new TypeReference<List<ToolCall>>() {});
                    } catch (IllegalArgumentException e) {
                        // 单个会话文件的 tool_calls 结构异常不该让整个 resume 失败：
                        // 降级为只保留文本部分，后续请求仍然合法（只是丢失工具上下文）
                        toolCalls = null;
                    }
                }
                session.messages.add(new Message(
                    msgNode.path("role").asText(),
                    msgNode.path("content").asText(null),
                    msgNode.path("timestamp").asLong(),
                    name, toolCallId, toolCalls
                ));
            }
            // 旧文件可能本就超限（历史上限不同），载入后统一整一次
            session.trimToLimit();
            
            var metadataNode = json.path("metadata");
            metadataNode.fields().forEachRemaining(entry -> {
                // 必须转回自然 Java 类型：直接存 JsonNode 会造成读写不对称——
                // 写入时是 String/Integer（经 setMetadata），读回却变成 TextNode/IntNode，
                // 于是「保存后再载入的会话」上的任何 metadata 相等比较都会静默失败。
                Object value = MAPPER.convertValue(entry.getValue(), Object.class);
                session.metadata.put(entry.getKey(), value);
            });
            
            return session;
        }
    }
    
