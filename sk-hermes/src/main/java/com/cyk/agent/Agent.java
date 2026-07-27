package com.cyk.agent;

import com.cyk.HermesAgent;
import com.cyk.bean.*;
import com.cyk.config.HermesConfig;
import com.cyk.constant.Constants;
import com.cyk.controller.TrajectoryCollector;
import com.cyk.extract.KnowledgeExtractor;
import com.cyk.http.ModelClient;
import com.cyk.manager.MemoryManager;
import com.cyk.manager.SessionManager;
import com.cyk.tool.ToolRegistry;
import com.cyk.tool.SkillTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

public class Agent {
    private static final Logger logger = LoggerFactory.getLogger(Agent.class);
    private final HermesConfig config;
    private final ModelClient modelClient;//向模型发送http请求

    private List<Map<String, Object>> toolDefinitions;//各个工具的定义
    private ToolRegistry toolRegistry;

    private List<ModelMessage> conversationHistory;//存放对话历史

    private TrajectoryCollector trajectoryCollector;
    private final String sessionId = "cli" + UUID.randomUUID().toString().substring(0, 8);
    private KnowledgeExtractor knowledgeExtractor;
    private SessionManager sessionManager;
    private MemoryManager memoryManager;
    private SkillMatcher skillMatcher;

    public Agent(HermesConfig config) {
        this.config = config;
        this.modelClient = new ModelClient(config);
        this.toolRegistry = ToolRegistry.getInstance();

        initializeTool();//初始化 ToolRegistry（注册 FileTool 4个 + MemoryTool 5个工具），初始化toolDefinitions
        this.conversationHistory = new ArrayList<>();

        this.sessionManager = new SessionManager(Constants.getHermesHome());

        this.memoryManager = MemoryManager.getInstance();//初始化 MemoryManager（加载 MEMORY.md / USER.md）
        this.skillMatcher = new SkillMatcher();

        initializeLearningComponents();
    }

    //初始化学习组件
    private void initializeLearningComponents() {
        trajectoryCollector = new TrajectoryCollector();
        knowledgeExtractor = new KnowledgeExtractor(memoryManager, config);

        trajectoryCollector.startSession(sessionId, config.getCurrentModel());

    }

    /**
     * 将session保存到磁盘
     */
    private void persistSession() {
        try {
            Session session = sessionManager.getSession(sessionId);

            //将聊天历史保存到session
            for (ModelMessage modelMessage : conversationHistory) {
                if (modelMessage.getRole() != null && modelMessage.getContent() != null) {
                    session.addMessage(modelMessage.getRole(), modelMessage.getContent());
                }
            }

            sessionManager.saveSession(session);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 结束会话session
     */
    public void endSession(boolean completed) {
        //将聊天数据放入轨迹中
        for (ModelMessage modelMessage : conversationHistory) {
            trajectoryCollector.addMessage(sessionId, modelMessage);
        }
        trajectoryCollector.endSession(sessionId, completed);

        //提取知识
        if (completed) {
            ExtractionResult result = knowledgeExtractor.onSessionEnd(sessionId, conversationHistory);
            if (!result.getInsights().isEmpty()) {
                logger.info("Extracted insights： {}", result.getInsights());
            }

            if (!result.getMemoriesSaved().isEmpty()) {
                logger.info("Saved memory： {}", result.getMemoriesSaved());
            }

            if (result.hasSkillCandidate()) {
                logger.info("Skill extracted and saved: {}", result.getSkillCandidate().getName());
            }
        }
        //持久化
        persistSession();

        //关闭轨迹收集
        trajectoryCollector.shutdown();

    }

    /**
     * tool初始化
     */
    private void initializeTool() {
        ToolRegistry.initialize();
        //SkillTool.register(toolRegistry);
        toolDefinitions = buildToolDefinitions();
    }

    /**
     * 获取工具的定义
     *
     * @return
     */
    private List<Map<String, Object>> buildToolDefinitions() {
        List<String> allToolName = toolRegistry.getAllToolName();
        Set<String> allTool = new HashSet<>(allToolName);
        return toolRegistry.getDefinitions(allTool);
    }


    public void run() {
        conversationHistory.add(ModelMessage.system(buildSystemPrompt()));
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        try {
            while (true) {
                System.out.println("You:");
                String input = reader.readLine();
                if (input == null) {
                    continue;
                }
                if (input.equalsIgnoreCase("exit")) {
                    endSession(true);
                    break;
                }

                //处理用户输入问题
                processUserMessage(input);

            }
            System.out.println("goodbye！😊");

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 处理用户输入的内容
     *
     * @param input
     */
    private void processUserMessage(String input) {
        conversationHistory.add(ModelMessage.user(input));//将用户输入的内容添加到聊天内容

        boolean continueLoop = true;
        int turnCount = 0;//轮次数量
        while (continueLoop) {
            turnCount++;
            //将工具定义，历史消息一起发送给模型
            ChatCompletionResponse response = modelClient.chatCompletion(conversationHistory, toolDefinitions, false);
            //获取模型回答
            ModelMessage assistantMessage = response.getMessage();
            if (assistantMessage == null) {
                break;
            }
            //将回答加入聊天历史
            conversationHistory.add(assistantMessage);

            //判断模型是否需要调用工具
            if (response.hasToolCalls()) {
                //打印模型的回答
                if (assistantMessage.getContent() != null) {
                    System.out.println("\nAssistant:" + assistantMessage.getContent());
                }

                //执行工具调用
                List<ToolCall> toolCalls = assistantMessage.getToolCalls();
                for (ToolCall toolCall : toolCalls) {
                    String res = executeToolCall(toolCall);//调用工具
                    //将工具调用的结果也加入聊天记录
                    conversationHistory.add(ModelMessage.tool(res, toolCall.getId()));
                }

                continueLoop = true;
            } else {
                //没有工具调用
                String content = assistantMessage.getContent();
                if (content != null) {
                    System.out.println("\nAssistant:" + content);
                }

                continueLoop = false;
            }

            //判断对话次数有没有达到最大限制
            if (turnCount >= config.getMaxTurns()) {
                conversationHistory.add(ModelMessage.assistant(
                        "已达到最大对话轮次限制(" + config.getMaxTurns() + ")，当前任务终止。"));
                continueLoop = false;
                break;
            }

        }

    }

    /**
     * 调用工具
     *
     * @param toolCall
     * @return
     */
    private String executeToolCall(ToolCall toolCall) {
        try {
            String name = toolCall.getFunction().getName();
            String arguments = toolCall.getFunction().getArguments();

            //解析参数
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new ObjectMapper().readValue(arguments, Map.class);

            //调用工具
            return toolRegistry.dispatch(name, args);

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 构建提示词
     */
    private String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();

        // 智能体提示词
        prompt.append(Constants.DEFAULT_AGENT_IDENTITY).append("\n\n");

        // 记忆提示词
        prompt.append(Constants.MEMORY_GUIDANCE).append("\n\n");

        // 工具强制使用提示词
        prompt.append(Constants.TOOL_USE_ENFORCEMENT_GUIDANCE).append("\n\n");

        // 执行规范
        prompt.append(Constants.EXECUTION_DISCIPLINE_GUIDANCE).append("\n\n");

        // 会话回忆
        prompt.append(Constants.SESSION_SEARCH_GUIDANCE).append("\n\n");

        // Skills 使用指引
        prompt.append(Constants.SKILLS_GUIDANCE).append("\n\n");

        // 网页搜索指引
        prompt.append(Constants.WEB_SEARCH_GUIDANCE).append("\n\n");

        // 知识库检索指引
        prompt.append(Constants.RAG_GUIDANCE).append("\n\n");

        // 平台提示（命令行模式）
        String platformHint = Constants.PLATFORM_HINTS.get("cli");
        if (platformHint != null) {
            prompt.append(platformHint).append("\n\n");
        }

        // 记忆上下文
        String memoryContext = memoryManager.getSystemPromptSnapshot();
        if (!memoryContext.isEmpty()) {
            prompt.append(memoryContext).append("\n\n");
        }

        // 可用工具列表
        prompt.append("## Available Tools\n\n");
        for (Map<String, Object> tool : toolDefinitions) {
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            if (function != null) {
                prompt.append("- ").append(function.get("name"));
                if (function.containsKey("description")) {
                    String desc = (String) function.get("description");
                    // 修剪长描述
                    if (desc.length() > 200) {
                        desc = desc.substring(0, 200) + "...";
                    }
                    prompt.append(": ").append(desc);
                }
                prompt.append("\n");
            }
        }

        // 添加匹配到的可用技能
        String skillContext = buildSkillContext();
        if (!skillContext.isEmpty()) {
            prompt.append("\n").append(skillContext).append("\n");
        }

        return prompt.toString();
    }

    private String buildSkillContext() {
        String lastUserInput = "";
        Set<String> recentTags = new HashSet<>();
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            ModelMessage msg = conversationHistory.get(i);
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                if (lastUserInput.isEmpty()) {
                    lastUserInput = msg.getContent();
                }
                if (msg.getContent() != null) {
                    for (String word : msg.getContent().toLowerCase().split("\\s+")) {
                        if (word.length() >= 3) {
                            recentTags.add(word);
                        }
                    }
                }
            }
        }

        if (lastUserInput.isEmpty()) {
            return "";
        }

        List<SkillMatcher.Match> matches = skillMatcher.match(lastUserInput, recentTags);
        return skillMatcher.buildSkillContext(matches);
    }

}

