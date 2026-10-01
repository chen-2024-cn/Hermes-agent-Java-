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
import com.cyk.util.ConsoleUi;
import com.cyk.util.TokenUsageTracker;
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
     * 本次运行开始时已载入的历史消息数（resume 基线）。
     *
     * <p>新建会话恒为 0；resume 会话等于从磁盘载入的历史消息条数，且此后不再变化
     * （区别于会随持久化移动的 {@link #persistedIndex}）。</p>
     *
     * <p>用途：会话收尾时的「知识提取」与「轨迹上报」都只应处理
     * {@code conversationHistory} 中下标 {@code >= resumedMessageCount} 的<b>本次新增</b>消息。
     * 否则会出现两个 bug：① resume 后一句话不说直接 exit，仍会拿旧历史去触发一次
     * LLM 知识提取（用户反馈）；② resume 的历史被重复写入轨迹样本，污染自我进化数据。
     * 这批旧消息在它们产生的那一轮运行里早已被提取/上报过。</p>
     */
    private int resumedMessageCount = 0;

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
        // 同时记录「本次运行的起点」：后续知识提取/轨迹上报只处理这之后的新消息，
        // 避免 resume 后未产生任何新对话也重复提取旧内容（用户反馈的 bug）
        this.resumedMessageCount = conversationHistory.size();
        logger.info("已恢复会话 {}，载入 {} 条历史消息", resumeSessionId, conversationHistory.size());
    }

    /**
     * 本次运行<b>新增</b>的消息（resume 载入的旧历史不在其中）。
     *
     * <p>返回独立副本而非 subList 视图，防止收尾流程中意外修改原历史。</p>
     */
    private List<ModelMessage> newMessagesThisRun() {
        int from = Math.min(resumedMessageCount, conversationHistory.size());
        return new ArrayList<>(conversationHistory.subList(from, conversationHistory.size()));
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

        // ① 先持久化会话（纯本地 IO，瞬时完成）：保证聊天记录先安全落盘，
        //    后面的慢操作即使失败也不丢数据
        persistSession();

        // ② 将聊天数据放入轨迹中（入队即返回，后台线程异步写盘）
        //    只上报本次运行新增的消息：resume 载入的旧历史在上一轮运行中已上报过，
        //    全量重放会让同一份对话重复写入轨迹样本，污染自我进化数据
        List<ModelMessage> newMessages = newMessagesThisRun();
        for (ModelMessage modelMessage : newMessages) {
            trajectoryCollector.addMessage(sessionId, modelMessage);
        }
        trajectoryCollector.endSession(sessionId, completed);

        // ③ 提取知识放最后：这一步包含 LLM 网络调用，可能耗时数十秒。
        //    旧实现把它放在 persistSession 之前且异常会直接冒泡，导致 exit 卡死体验差；
        //    现在 goodbye 已先行打印（见 run()），且提取失败不影响退出流程。
        //    用户可见输出一律是「人话」短提示（System.out）；
        //    完整 insights/记忆清单属机器细节，降到 DEBUG（HERMES_LOG_LEVEL=DEBUG 可查）。
        //    注意「📝 稍等」提示只在会话确实达到提取门槛时打印——短会话直接静默跳过，
        //    否则用户会看到“稍等”之后没有任何下文（KnowledgeExtractor 内部按门槛返回 empty）。
        //    提取对象同样只是本次新增消息（修复：resume 后直接 exit 不再重复提取旧历史）
        if (completed && knowledgeExtractor.willExtract(newMessages)) {
            try {
                System.out.println("📝 稍等，正在回顾本次对话、沉淀值得记住的经验...");
                ExtractionResult result = knowledgeExtractor.onSessionEnd(sessionId, newMessages);

                int insightCount = result.getInsights().size();
                int savedCount = result.getMemoriesSaved().size();
                if (insightCount > 0) {
                    logger.debug("Extracted insights： {}", result.getInsights());
                    logger.debug("Saved memory： {}", result.getMemoriesSaved());
                    if (savedCount > 0) {
                        System.out.println("✨ 本次总结了 " + insightCount + " 条经验，其中 "
                                + savedCount + " 条已存入长期记忆，下次见面我还记得。");
                    } else if (result.hasSkillCandidate()) {
                        System.out.println("✨ 本次总结了 " + insightCount + " 条经验，并沉淀了一个可复用技能："
                                + result.getSkillCandidate().getName());
                    } else {
                        System.out.println("✨ 本次对话没有需要长期记住的新内容。");
                    }
                } else {
                    System.out.println("✨ 本次对话没有需要长期记住的新内容。");
                }
            } catch (Exception e) {
                logger.warn("知识提取失败（不影响会话保存与退出）: {}", e.getMessage());
            }
        }

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
        boolean firstPrompt = true;
        try {
            while (true) {
                // 醒目的用户提示符：分隔线 + 绿色「▶ 你:」前缀（ConsoleUi 统一渲染，
                // 支持 ANSI 的终端显示彩色，老终端自动降级为纯符号，绝不乱码）。
                // 首轮不打分隔线，避免与启动横幅重叠。
                System.out.print(ConsoleUi.userPrompt(!firstPrompt));
                System.out.flush();
                firstPrompt = false;
                String input = reader.readLine();
                // readLine() 返回 null 表示输入流已关闭（管道输入读完、Ctrl-D/Ctrl-Z、CI 非交互调用）。
                // 原实现在此处 continue，会导致 CPU 空转刷屏无限循环；此处必须终止会话并退出。
                if (input == null) {
                    System.out.println();
                    logger.info("检测到输入流结束（EOF），结束当前会话");
                    break;
                }
                // 输入为空白时不发请求（避免空转一轮 LLM 调用），继续提示输入
                if (input.isBlank()) {
                    continue;
                }
                if (input.trim().equalsIgnoreCase("exit")) {
                    break;
                }

                //处理用户输入问题
                processUserMessage(input);

            }
            // 先打印 goodbye 再做收尾：endSession 内部的知识提取涉及 LLM 网络调用（可能数十秒），
            // 旧实现把 goodbye 放在提取之后，导致用户输入 exit 后面对长时间无响应的黑屏。
            // 统一在此收尾：无论是 exit、EOF 还是循环异常退出，都保证会话被正确结束与持久化
            System.out.println("goodbye！😊");
            endSession(true);

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

        // 本轮 token 用量单独记账：一次提问在工具调用循环里可能触发多次模型请求，
        // 全部累加后才在轮末展示（只统计最后一次会严重低报）。
        // 每轮 new 一个实例 = 下轮自动归零，无需手动 reset。
        TokenUsageTracker turnUsage = new TokenUsageTracker();

        boolean continueLoop = true;
        int turnCount = 0;//轮次数量
        while (continueLoop) {
            turnCount++;
            //将工具定义，历史消息一起发送给模型；是否流式取决于配置/CLI 开关
            boolean streaming = config.isStreamEnabled();
            ChatCompletionResponse response;
            if (streaming) {
                // 流式：懒打印前缀——只有真正收到第一个文本增量才打印助手标识。
                // 旧实现无条件先打前缀：当模型这一轮选择调工具而不是说话时，
                // 屏幕上会留下一个空的 "Assistant:" 行（用户反馈的体验 bug）。
                // 增量不直接裸打印，而是经过 ConsoleUi.StreamingRenderer 做跨 token 安全的
                // 空白规范化：连续换行压缩为最多一个空行、行尾/开头空白清除（用户反馈的
                // 「回答很多换行且不美观」的终端侧兜底；提示词侧已同步约束模型输出）。
                boolean[] prefixPrinted = {false};
                ConsoleUi.StreamingRenderer renderer = ConsoleUi.newStreamingRenderer(text -> {
                    System.out.print(text);
                    System.out.flush();
                });
                response = modelClient.chatCompletionStream(conversationHistory, toolDefinitions, piece -> {
                    if (!prefixPrinted[0]) {
                        System.out.print(ConsoleUi.assistantPrefix());
                        prefixPrinted[0] = true;
                    }
                    renderer.accept(piece);
                });
                // 只有输出过正文才补换行，避免后续输出与正文粘连；纯工具轮不留空行
                if (prefixPrinted[0] && renderer.finish()) {
                    System.out.println();
                }
            } else {
                response = modelClient.chatCompletion(conversationHistory, toolDefinitions, false);
            }

            // 用量累加：流式（stream_options.include_usage 尾 chunk）与非流式（根节点 usage）
            // 都在此处汇合，两条路径共用一套记账，不会漏算也不会重复算。
            // usage 为 null（部分兼容层不回传）时 record 内部静默跳过。
            turnUsage.record(response.getUsage());
            TokenUsageTracker.session().record(response.getUsage());

            //获取模型回答
            ModelMessage assistantMessage = response.getMessage();
            if (assistantMessage == null) {
                break;
            }
            //将回答加入聊天历史
            conversationHistory.add(assistantMessage);

            //判断模型是否需要调用工具
            if (response.hasToolCalls()) {
                //非流式时才需要整段打印（流式已在回调里实时输出过）；
                // 打印前去除 Markdown 痕迹并规范空行（与流式渲染器同一套视觉标准）
                if (!streaming && assistantMessage.getContent() != null
                        && !assistantMessage.getContent().isBlank()) {
                    System.out.println(ConsoleUi.assistantPrefix()
                            + ConsoleUi.stripMarkdown(assistantMessage.getContent()));
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
                //非流式时才整段打印（流式已实时输出）；空白内容不打；打印前做 Markdown 清理
                if (!streaming && content != null && !content.isBlank()) {
                    System.out.println(ConsoleUi.assistantPrefix() + ConsoleUi.stripMarkdown(content));
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

        // 本轮收尾：在回复末尾附加用量汇总（工具调用轮与纯文本轮同样适用，
        // 用户可据此看清一次提问背后走了几趟模型请求）
        printUsageSummary(turnUsage);

    }

    /**
     * 在本轮回复末尾打印 token 用量汇总（本轮小计 + 会话累计）。
     *
     * <p>服务端未回传 usage 时（{@code requests == 0}）直接跳过，
     * 宁可不显示也不打一排 0 误导用户以为本轮没花钱。</p>
     *
     * <p>刻意不放在 {@code try/finally} 里：请求抛异常时进程正在报错退出，
     * 此时插入一行用量统计只会打乱错误输出的主次。</p>
     *
     * @param turnUsage 当轮计量器
     */
    private void printUsageSummary(TokenUsageTracker turnUsage) {
        String summary = ConsoleUi.usageLine(turnUsage.snapshot(), TokenUsageTracker.session().snapshot());
        if (summary.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println(summary);
        System.out.flush();
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

        // 终端输出格式规范（禁 Markdown、控换行——用户反馈的排版问题在提示词侧的约束）
        prompt.append(Constants.TERMINAL_OUTPUT_GUIDANCE).append("\n\n");

        // 执行规范
        prompt.append(Constants.EXECUTION_DISCIPLINE_GUIDANCE).append("\n\n");

        // 终端命令执行工具指引（run_command 已注册时才有意义，未启用则不注入避免误导模型）
        if (config.isTerminalToolEnabled()) {
            prompt.append(Constants.TERMINAL_TOOL_GUIDANCE).append("\n\n");
        }

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

