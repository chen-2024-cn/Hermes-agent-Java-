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
     * 会话结束时提取知识
     */
    public ExtractionResult onSessionEnd(String sessionId, List<ModelMessage> messages) {
        if (!autoExtractEnabled) {
            logger.info("知识提取已关闭（extract.enabled=false）");
            return ExtractionResult.empty();
        }

        if (messages == null || messages.size() < minMessagesForExtraction) {
            logger.debug("消息数 {} 不足提取门槛 {}，跳过知识提取",
                    messages == null ? 0 : messages.size(), minMessagesForExtraction);
            return ExtractionResult.empty();
        }

        ExtractionResult result = new ExtractionResult();

        //提取有用的数据
        List<String> insights = extractInsights(messages);
        result.setInsights(insights);

        //将有用的数据保存
        for (String insight : insights) {
            if (shouldSaveToMemory(insight)) {
                memoryManager.addMemory(insight);
                result.addMemorySaved(insight);
            }
        }

        //检查是否可以提取技能
        Optional<Skill> skillCandidate = extractSkillPattern(messages);
        skillCandidate.ifPresent(result::setSkillCandidate);

        return result;
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
            // Remove numbering (1. or - or *)
            line = line.replaceFirst("^\\d+\\.\\s*", "")
                    .replaceFirst("^[-*]\\s*", "");
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
