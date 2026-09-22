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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Agent {
    private static final Logger logger = LoggerFactory.getLogger(Agent.class);
    private final HermesConfig config;
    private final ModelClient modelClient;//向模型发送http请求

    private List<Map<String, Object>> toolDefinitions;//各个工具的定义
    private ToolRegistry toolRegistry;

    private List<ModelMessage> conversationHistory;//存放对话历史

    private TrajectoryCollector trajectoryCollector;
    private final String sessionId;
    private KnowledgeExtractor knowledgeExtractor;
    private SessionManager sessionManager;
    private MemoryManager memoryManager;
    private SkillMatcher skillMatcher;

    /**
     * 已持久化到 session 的对话历史下标。
     *
     * <p>{@link #persistSession()} 每次只把 {@code conversationHistory} 中该下标之后的
     * 新消息追加进 session，避免重复写入；恢复会话（resume）加载历史后需同步抬高该下标。</p>
     */
    private int persistedIndex = 0;

    /**
     * 会话是否已结束。
     * <p>防止 exit 命令与 EOF 分支重复调用 {@link #endSession(boolean)}（重复调用会二次关闭收集器线程）。</p>
     */
    private boolean sessionClosed = false;

    public Agent(HermesConfig config) {
        this(config, null);
    }

    /**
     * @param config           配置
     * @param resumeSessionId  要恢复的历史会话 id；null 表示新建随机会话
     */
    public Agent(HermesConfig config, String resumeSessionId) {
        this.config = config;
        this.modelClient = new ModelClient(config);
        this.toolRegistry = ToolRegistry.getInstance();

        initializeTool();//初始化 ToolRegistry（注册文件、记忆、技能、搜索、抓取等工具），初始化toolDefinitions
        this.conversationHistory = new ArrayList<>();

        // 会话 id：resume 则沿用指定 id（继续往同一份文件累积），否则新建随机 id
        this.sessionId = (resumeSessionId != null && !resumeSessionId.isBlank())
                ? resumeSessionId
                : "cli" + UUID.randomUUID().toString().substring(0, 8);

        this.sessionManager = new SessionManager(Constants.getHermesHome());

        // resume 场景：先把磁盘上的历史消息载入对话上下文，并把 persistedIndex 抬高到已加载的数量，
        // 这样后续 persistSession 不会把这批历史再追加一遍
        loadResumedHistory(resumeSessionId);

        this.memoryManager = MemoryManager.getInstance();//初始化 MemoryManager（加载 MEMORY.md / USER.md）
        this.skillMatcher = new SkillMatcher();

        initializeLearningComponents();
    }

    /**
     * 恢复指定会话的历史消息到 {@link #conversationHistory}。
     *
     * @param resumeSessionId 会话 id；null/空白或文件不存在时不做任何操作
     */
    private void loadResumedHistory(String resumeSessionId) {
        if (resumeSessionId == null || resumeSessionId.isBlank()) {
            return;
        }
        if (!sessionManager.sessionExists(resumeSessionId)) {
            logger.warn("会话 {} 不存在，将以新会话开始", resumeSessionId);
            return;
        }
        Session session = sessionManager.getSession(resumeSessionId);
        for (Message msg : session.messages) {
            conversationHistory.add(new ModelMessage(msg.role(), msg.content()));
        }
        // 这些历史已经持久化过，抬高下标避免重复写入
        this.persistedIndex = conversationHistory.size();
        logger.info("已恢复会话 {}，载入 {} 条历史消息", resumeSessionId, conversationHistory.size());
    }

    //初始化学习组件
    private void initializeLearningComponents() {
        trajectoryCollector = new TrajectoryCollector();
        knowledgeExtractor = new KnowledgeExtractor(memoryManager, config);

        trajectoryCollector.startSession(sessionId, config.getCurrentModel());

    }

    /**
     * 将session保存到磁盘
     *
     * <p>增量持久化：只追加 {@link #persistedIndex} 之后的新消息，重复调用不会写入重复历史。
     * 同时过滤 system 提示词（每轮都会重建，无需持久化，且体积大会挤占 session 容量）。</p>
     */
    private void persistSession() {
        try {
            Session session = sessionManager.getSession(sessionId);

            //将「尚未持久化」的聊天历史增量保存到session，并跳过 system 消息
            for (int i = persistedIndex; i < conversationHistory.size(); i++) {
                ModelMessage modelMessage = conversationHistory.get(i);
                if (modelMessage.getRole() == null || modelMessage.getContent() == null) {
                    continue;
                }
                if ("system".equals(modelMessage.getRole())) {
                    continue;
                }
                session.addMessage(modelMessage.getRole(), modelMessage.getContent());
            }

            // 抬高水位下标，记录已持久化到的位置
            persistedIndex = conversationHistory.size();

            sessionManager.saveSession(session);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 结束会话session
     */
    public void endSession(boolean completed) {
        if (sessionClosed) {
            logger.debug("会话 {} 已结束，跳过重复的 endSession 调用", sessionId);
            return;
        }
        sessionClosed = true;

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
     * <p>工具已由 {@link ToolRegistry#initialize(HermesConfig)} 统一注册（含 SkillTool 与各 RAG 工具），
     * 传入当前 config 复用（避免注册中心内部重复加载配置文件），
     * 此处只负责生成暴露给模型的工具定义列表。</p>
     */
    private void initializeTool() {
        ToolRegistry.initialize(config);
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
                // readLine() 返回 null 表示输入流已关闭（管道输入读完、Ctrl-D/Ctrl-Z、CI 非交互调用）。
                // 原实现在此处 continue，会导致 CPU 空转刷屏无限循环；此处必须终止会话并退出。
                if (input == null) {
                    System.out.println();
                    logger.info("检测到输入流结束（EOF），结束当前会话");
                    break;
                }
                if (input.equalsIgnoreCase("exit")) {
                    break;
                }

                //处理用户输入问题
                processUserMessage(input);

            }
            // 统一在此收尾：无论是 exit、EOF 还是循环异常退出，都保证会话被正确结束与持久化
            endSession(true);
            System.out.println("goodbye！😊");

        } catch (IOException e) {
            // I/O 异常属于非正常结束，标记 completed=false，便于轨迹区分成功/失败会话
            endSession(false);
            throw new RuntimeException(e);
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理：进程即将退出
            }
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
            //将工具定义，历史消息一起发送给模型；是否流式取决于配置/CLI 开关
            boolean streaming = config.isStreamEnabled();
            ChatCompletionResponse response;
            if (streaming) {
                // 流式：文本增量实时打印（前缀在流开始前统一打一次），工具调用增量在内部拼装
                System.out.print("\nAssistant:");
                response = modelClient.chatCompletionStream(conversationHistory, toolDefinitions, piece -> {
                    System.out.print(piece);
                    System.out.flush();
                });
                // 流结束后补一个换行，避免后续输出与正文粘连
                System.out.println();
            } else {
                response = modelClient.chatCompletion(conversationHistory, toolDefinitions, false);
            }
            //获取模型回答
            ModelMessage assistantMessage = response.getMessage();
            if (assistantMessage == null) {
                break;
            }
            //将回答加入聊天历史
            conversationHistory.add(assistantMessage);

            //判断模型是否需要调用工具
            if (response.hasToolCalls()) {
                //非流式时才需要整段打印（流式已在回调里实时输出过）
                if (!streaming && assistantMessage.getContent() != null) {
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
                //非流式时才整段打印（流式已实时输出）
                if (!streaming && content != null) {
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
     * 构建「当前工作环境」上下文（Claude Code 式目录感知）。
     *
     * <p>JVM 的 {@code user.dir} 就是用户启动终端时所在的目录。把它连同顶层文件/目录
     * 快照注入 system prompt，模型就能：
     * <ul>
     *   <li>知道用户正在哪个项目里提问，主动用 search_files / read_file 探查而非凭空猜测；</li>
     *   <li>正确生成相对路径（FileTool 的路径解析同样基于 user.dir）。</li>
     * </ul>
     * 快照限制条数，避免大目录撑爆 prompt。</p>
     */
    private String buildWorkingDirectoryContext() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前工作环境\n");
        sb.append("- 工作目录（当前项目根目录）: ").append(cwd).append('\n');
        sb.append("- 操作系统: ").append(System.getProperty("os.name")).append('\n');
        sb.append("- 用户主目录: ").append(System.getProperty("user.home")).append('\n');
        sb.append("- 文件工具中的相对路径均相对上述工作目录解析。")
          .append("回答与本项目相关的问题前，优先用文件搜索/读取工具探查实际代码，禁止凭空猜测项目结构。\n");

        List<String> entries = listTopLevelEntries(cwd, 50);
        if (!entries.isEmpty()) {
            sb.append("- 工作目录顶层内容:\n");
            for (String entry : entries) {
                sb.append("    ").append(entry).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 列出目录顶层条目（目录名带 {@code /} 后缀），最多 {@code max} 条，超出时追加省略标记。
     * 目录不可读（权限等）时返回空列表，绝不让快照失败影响对话启动。
     */
    private static List<String> listTopLevelEntries(Path dir, int max) {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        if (names.size() <= max) {
                            names.add(p.getFileName() + (Files.isDirectory(p) ? "/" : ""));
                        }
                    });
        } catch (IOException e) {
            logger.warn("无法列出工作目录 {}: {}", dir, e.getMessage());
            return List.of();
        }
        if (names.size() > max) {
            List<String> truncated = new ArrayList<>(names.subList(0, max));
            truncated.add("...（共 " + names.size() + "+ 项，已截断，可用 search_files 进一步探查）");
            return truncated;
        }
        return names;
    }

    /**
     * 构建提示词
     */
    private String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();

        // 智能体提示词
        prompt.append(Constants.DEFAULT_AGENT_IDENTITY).append("\n\n");

        // 工作目录上下文（Claude Code 式：让模型感知用户启动终端时所在的项目目录）
        prompt.append(buildWorkingDirectoryContext()).append("\n\n");

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

