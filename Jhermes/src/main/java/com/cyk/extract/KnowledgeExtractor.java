package com.cyk.extract;

import com.cyk.bean.ExtractionResult;
import com.cyk.bean.ModelMessage;
import com.cyk.bean.Skill;
import com.cyk.config.HermesConfig;
import com.cyk.http.ModelClient;
import com.cyk.manager.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 知识提取器
 */
public class KnowledgeExtractor {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeExtractor.class);

    private final MemoryManager memoryManager;
    private final HermesConfig hermesConfig;
    private final ModelClient modelClient;

    // 自动提取开关（读 config.yaml 的 extract.enabled）
    private final boolean autoExtractEnabled;
    // 最大提取数量（读 config.yaml 的 extract.max_insights）
    private final int maxInsightsPerSession;
    // 最小消息数（读 config.yaml 的 extract.min_messages）
    private final int minMessagesForExtraction;

    public KnowledgeExtractor(MemoryManager memoryManager, HermesConfig hermesConfig) {
        this.memoryManager = memoryManager;
        this.hermesConfig = hermesConfig;
        this.modelClient = new ModelClient(hermesConfig);
        // 原先这三个参数是硬编码字段，用户无法关闭自动提取或调整阈值；
        // 现改为从配置读取，缺省时回落到与原硬编码一致的默认值，保证行为不变。
        this.autoExtractEnabled = hermesConfig.isExtractEnabled();
        this.maxInsightsPerSession = hermesConfig.getMaxInsightsPerSession();
        this.minMessagesForExtraction = hermesConfig.getMinMessagesForExtraction();

        logger.debug("KnowledgeExtractor 配置：enabled={}, maxInsights={}, minMessages={}",
                autoExtractEnabled, maxInsightsPerSession, minMessagesForExtraction);
    }


    /**
     * 预判本次会话是否会真正发起知识提取（不消耗任何 LLM 调用）。
     *
     * <p>供 Agent 在退出收尾时决定要不要向用户打印「稍等，正在回顾...」提示——
     * 短会话/已关闭提取时直接静默跳过，避免“稍等”之后没有下文的尴尬。
     * 门槛逻辑与 {@link #onSessionEnd} 完全一致（抽成公共方法防两处漂移）。</p>
     *
     * @param messages 完整对话历史（含 system 消息，内部会过滤）
     * @return true 表示接下来会发起提取调用
     */
    public boolean willExtract(List<ModelMessage> messages) {
        return autoExtractEnabled
                && filterEffectiveMessages(messages).size() >= minMessagesForExtraction;
    }

    /**
     * 会话结束时提取知识
     */
    public ExtractionResult onSessionEnd(String sessionId, List<ModelMessage> messages) {
        if (!autoExtractEnabled) {
            // 用户主动关闭的功能不打 INFO，免得每次退出都多一行噪音
            logger.debug("知识提取已关闭（extract.enabled=false）");
            return ExtractionResult.empty();
        }

        // 只统计真正有效的对话消息（user/assistant 且内容非空）。
        // 旧实现直接看 messages.size()：启动即 exit 的会话历史里也有一条巨大的
        // system 提示词（身份、工具规则、记忆快照全在里面），min_messages=1 直接放行，
        // 结果把「自己发给自己的 system prompt」当对话送去 LLM 提取——
        // 既白白阻塞退出几十秒，还会把提示词规则误存为「用户洞察」回写 MEMORY.md，
        // 形成每轮滚雪球的记忆污染。
        List<ModelMessage> effectiveMessages = filterEffectiveMessages(messages);

        if (effectiveMessages.size() < minMessagesForExtraction) {
            logger.debug("有效对话消息数 {} 不足提取门槛 {}，跳过知识提取",
                    effectiveMessages.size(), minMessagesForExtraction);
            return ExtractionResult.empty();
        }

        // 用户可见的「正在回顾」提示由 Agent.endSession 统一用 System.out 打印（人话），
        // 这里只留 DEBUG 级技术日志，避免退出时双重提示刷屏。
        logger.debug("开始知识提取：有效消息 {} 条（门槛 {}），将发起一次提取模型调用",
                effectiveMessages.size(), minMessagesForExtraction);

        ExtractionResult result = new ExtractionResult();

        //提取有用的数据（基于过滤后的消息，不含 system 提示词）
        List<String> insights = extractInsights(effectiveMessages);
        result.setInsights(insights);

        //将有用的数据保存
        for (String insight : insights) {
            if (shouldSaveToMemory(insight)) {
                memoryManager.addMemory(insight);
                result.addMemorySaved(insight);
            }
        }

        //检查是否可以提取技能（同样基于过滤后的消息）
        Optional<Skill> skillCandidate = extractSkillPattern(effectiveMessages);
        skillCandidate.ifPresent(result::setSkillCandidate);

        return result;
    }

    /**
     * 过滤出真正参与对话的消息：role 为 user/assistant 且内容非空。
     *
     * <p>排除掉：system 提示词（体积巨大且是自说自话）、tool 结果消息、
     * 只有 tool_calls 没有正文的 assistant 消息。</p>
     */
    private List<ModelMessage> filterEffectiveMessages(List<ModelMessage> messages) {
        List<ModelMessage> effective = new ArrayList<>();
        if (messages == null) {
            return effective;
        }
        for (ModelMessage message : messages) {
            String role = message.getRole();
            boolean isChatRole = "user".equals(role) || "assistant".equals(role);
            boolean hasContent = message.getContent() != null && !message.getContent().isBlank();
            if (isChatRole && hasContent) {
                effective.add(message);
            }
        }
        return effective;
    }

    /**
     * 从对话中提取有用信息
     */
    public List<String> extractInsights(List<ModelMessage> messages) {
        //格式化对话数据
        String conversation = formatConversation(messages);

        //构建提示词
        String prompt = buildInsightExtractionPrompt(conversation);

        //调用模型进行信息提取
        String response = callExtractionModel(prompt);

        return parseInsights(response);
    }


    /**
     * 是否需要保存
     */
    private boolean shouldSaveToMemory(String insight) {
        //内容小于30字符不保存
        if (insight.length() < 30) return false;

        String lowerCase = insight.toLowerCase();
        if (lowerCase.contains("summary") || lowerCase.contains("overview")) return false;

        return lowerCase.contains("user") || lowerCase.contains("prefer") || lowerCase.contains("config")
                || lowerCase.contains("important") || lowerCase.contains("note");
    }


    /**
     * 提取技能
     */
    public Optional<Skill> extractSkillPattern(List<ModelMessage> messages) {
        //检查是否满足提取技能的条件
        if (!isSkillWorthyWorkflow(messages)) {
            return Optional.empty();
        }

        //格式化对话
        String conversation = formatConversation(messages);

        //构建提取技能skill提示词
        String prompt = buildSkillExtractionPrompt(conversation);

        //调用模型进行技能提取
        String response = callExtractionModel(prompt);

        if (response == null || response.isBlank()) {
            return Optional.empty();
        }

        // 从模型响应构造 Skill 对象
        Skill skill = new Skill();
        skill.setName("Extracted Workflow");
        skill.setDescription("Auto-extracted skill from conversation");
        skill.setContent(response);
        skill.setType("reference");
        skill.setCreatedAt(Instant.now());
        skill.setUpdatedAt(Instant.now());

        return Optional.of(skill);
    }


    /**
     * 是否值得提取技能
     */
    private boolean isSkillWorthyWorkflow(List<ModelMessage> messages) {
        int toolCalls = 0;
        int userMassages = 0;

        for (ModelMessage message : messages) {
            if ("user".equals(message.getRole())) userMassages++;

            //统计工具调用次数
            if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                toolCalls += message.getToolCalls().size();
            }
        }

        return toolCalls >= 3 && userMassages >= 2 && messages.size() >= 6;
    }


    /**
     * 格式化对话内容
     * USER : 你好，我叫小李，你叫什么？
     * ASSISTANT : 你好，小李。
     */
    private String formatConversation(List<ModelMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ModelMessage message : messages) {
            // 双保险：即使调用方传入了未过滤的列表，这里也绝不把 system 提示词
            // 拼进「对话文本」，防止提示词泄漏给提取模型被当成用户知识。
            if (message.getRole() == null || "system".equals(message.getRole())) {
                continue;
            }
            if (message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            sb.append(message.getRole().toUpperCase())
                    .append(": ")
                    .append(message.getContent())
                    .append("\n\n");
        }

        return sb.toString();
    }


    /**
     * 调用模型
     */
    private String callExtractionModel(String prompt) {
        return modelClient.callExtractionModel(prompt,2000,0.3);
    }



    /**
     * 解析模型输出
     * @param response
     * @return
     */
    private List<String> parseInsights(String response) {
        List<String> insights = new ArrayList<>();

        if (response == null || response.trim().isEmpty()) {
            return insights;
        }

        // 解析编号列表或项目符号列表
        for (String line : response.split("\\n")) {
            line = line.trim();
            // 去掉编号（1. / 1、）、项目符号（- / * / •）以及 Markdown 加粗标记。
            // 旧实现只去掉单个 *，模型返回 "**环境根目录强制基准**：..." 这类加粗文本时
            // 会残留前导 "*"，写入 MEMORY.md 后形成 "*xxx**" 的畸形条目。
            // 成对的 ** 直接全部移除（记忆是纯文本条目，不需要 Markdown 语法）。
            line = line.replaceFirst("^\\d+[.、)]\\s*", "")
                    .replaceFirst("^[-*•]+\\s*", "")
                    .replace("**", "")
                    .trim();
            if (line.length() > 20 && line.length() < 500) {
                insights.add(line);
            }
        }

        return insights.stream()
                .limit(maxInsightsPerSession)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 构建技能提取提示词
     *
     * @param conversation
     * @return
     */
    private String buildSkillExtractionPrompt(String conversation) {
        return """
                This conversation demonstrates a successful workflow.
                Create a skill documentation that captures this pattern.
                
                Include:
                - When to use this approach
                - Step-by-step instructions
                - Common pitfalls or variations
                - Example usage
                
                Format as code documentation.
                
                Conversation demonstrating the workflow:
                %s
                
                Skill documentation:
                """.formatted(conversation);
    }

    /**
     * 构建信息提取提示词
     *
     * @param conversation
     * @return
     */
    private String buildInsightExtractionPrompt(String conversation) {
        return """
                Analyze this conversation and extract 3-5 key facts or insights that should be remembered.
                
                Focus on:
                - User preferences or requirements
                - Important decisions made
                - Configuration details
                - Project-specific information
                - Patterns or workflows that might be reused
                
                Format as a numbered list. Be specific and actionable.
                
                Conversation:
                %s
                
                Key insights to remember:
                """.formatted(conversation);
    }

}
