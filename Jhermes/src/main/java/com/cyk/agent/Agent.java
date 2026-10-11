package com.cyk.agent;

import com.cyk.HermesAgent;
import com.cyk.bean.*;
import com.cyk.config.HermesConfig;
import com.cyk.constant.Constants;
import com.cyk.controller.TrajectoryCollector;
import com.cyk.extract.KnowledgeExtractor;
import com.cyk.http.LlmClient;
import com.cyk.http.LlmRetryPolicy;
import com.cyk.http.LlmTransportException;
import com.cyk.http.ModelClient;
import com.cyk.capability.CapabilityRegistry;
import com.cyk.manager.MemoryManager;
import com.cyk.manager.SessionManager;
import com.cyk.manager.SkillManager;
import com.cyk.prompt.ComposedPrompt;
import com.cyk.prompt.PromptComposer;
import com.cyk.prompt.PromptConsistencyChecker;
import com.cyk.prompt.PromptContext;
import com.cyk.prompt.WorkingDirSnapshot;
import com.cyk.tool.ToolRegistry;
import com.cyk.tool.SkillTool;
import com.cyk.ui.Ui;
import com.cyk.ui.UserInput;
import com.cyk.util.ConsoleUi;
import com.cyk.util.TokenUsageTracker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

public class Agent {
    private static final Logger logger = LoggerFactory.getLogger(Agent.class);

    /** 重发上一个失败回合的斜杠命令（P0-5 R2）。 */
    static final String RETRY_COMMAND = "/retry";

    private final HermesConfig config;
    /**
     * LLM 调用抽象（P0-5 R0）：生产为 {@link ModelClient}，单测可注入手写桩
     * 以在不发起网络请求的前提下验证重试与错误边界。
     */
    private final LlmClient llmClient;
    /** 指数退避重试策略（P0-5 R3）：UI 提示经下方回调打印，不污染 stdout 以外通道。 */
    private final LlmRetryPolicy retryPolicy;

    private List<Map<String, Object>> toolDefinitions;//各个工具的定义
    private ToolRegistry toolRegistry;

    private List<ModelMessage> conversationHistory;//存放对话历史

    private TrajectoryCollector trajectoryCollector;
    private final String sessionId;
    private KnowledgeExtractor knowledgeExtractor;
    private SessionManager sessionManager;
    private MemoryManager memoryManager;

    /**
     * 系统提示词装配器（P0-3）：提示词分段、requires 判定与模板渲染全部下沉到
     * prompt 包，Agent 只负责供给装配上下文（注册实况/目录快照/记忆/技能）。
     * 无状态可共享：每回合调用 compose()，输入相同则输出逐字节相同（P0-4 R8 前缀稳定）。
     */
    private final PromptComposer promptComposer = new PromptComposer();

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

    /**
     * 上一个因传输错误失败的用户输入，供 {@code /retry} 重发（P0-5 R2）。
     *
     * <p>成功回合会清空；重试再次失败则保留，使用户可连续重试。
     * 重发不重新入队 user 消息——回滚时该条 user 消息被有意保留在历史里。</p>
     */
    private String lastFailedInput;

    /** 本轮流式输出是否已吐过文本：重试时用于提示「以下为完整回答」，避免用户误以为重复输出。 */
    private boolean streamEmitted;

    public Agent(HermesConfig config) {
        this(config, null);
    }

    /**
     * @param config           配置
     * @param resumeSessionId  要恢复的历史会话 id；null 表示新建随机会话
     */
    public Agent(HermesConfig config, String resumeSessionId) {
        this(config, resumeSessionId, new ModelClient(config));
    }

    /**
     * 测试专用构造（P0-5 R0）：注入 {@link LlmClient} 桩以验证错误边界与重试行为。
     * 包级可见，不进入公共 API。
     */
    Agent(HermesConfig config, String resumeSessionId, LlmClient llmClient) {
        this(config, resumeSessionId, llmClient,
                new SessionManager(Constants.getHermesHome(), config.getSessionMaxMessages()), true);
    }

    /**
     * 完整接缝构造（P0-5 测试专用，包级可见）。
     *
     * <p>为何需要：{@code Agent} 常规路径会在真实 {@code ~/.jhermes} 下创建会话文件、
     * 轨迹目录并拉起后台守护线程，单测直接跑会污染用户数据。本构造允许：</p>
     * <ul>
     *   <li>{@code sessionManager} 指向临时目录，保证会话读写封闭在测试内；</li>
     *   <li>{@code learningEnabled=false} 跳过轨迹收集与知识提取（不建目录、不起线程、
     *       退出时不调 LLM），使 AC1–AC7 能在毫秒级完成。</li>
     * </ul>
     *
     * <p>这是显式的测试接缝，而非给生产代码加全局数据目录覆盖开关——
     * 后者会多出一条可被外部进程利用的重定向通道（安全面），而接缝只存在于包内。</p>
     */
    Agent(HermesConfig config, String resumeSessionId, LlmClient llmClient,
          SessionManager sessionManager, boolean learningEnabled) {
        this.config = config;
        this.llmClient = llmClient;
        this.retryPolicy = new LlmRetryPolicy(this::onRetryNotice);
        this.toolRegistry = ToolRegistry.getInstance();

        initializeTool();//初始化 ToolRegistry（注册文件、记忆、技能、搜索、抓取等工具），初始化toolDefinitions
        this.conversationHistory = new ArrayList<>();

        // 会话 id：resume 则沿用指定 id（继续往同一份文件累积），否则新建随机 id
        this.sessionId = (resumeSessionId != null && !resumeSessionId.isBlank())
                ? resumeSessionId
                : "cli" + UUID.randomUUID().toString().substring(0, 8);

        // P0-2 R10：把真实会话 id 交给安全上下文，使审计记录的 session_id 正确。
        // （initializeTool 触发 SecurityContext.init 时 sessionId 尚未赋值，此处补齐）
        com.cyk.security.SecurityContext.setSessionId(this.sessionId);

        // R6：会话消息上限可配（缺省 100，与改造前一致），由 SessionManager 注入到每个 Session
        this.sessionManager = sessionManager;

        // resume 场景：先把磁盘上的历史消息载入对话上下文，并把 persistedIndex 抬高到已加载的数量，
        // 这样后续 persistSession 不会把这批历史再追加一遍
        loadResumedHistory(resumeSessionId);

        this.memoryManager = MemoryManager.getInstance();//初始化 MemoryManager（加载 MEMORY.md / USER.md）

        if (learningEnabled) {
            initializeLearningComponents();
        }
    }

    /**
     * 把系统提示词装入历史首位（P0-5 R7，供 {@link #run()} 与单测共用）。
     *
     * <p>使用 {@code add(0, ...)} 而非 {@code add(...)}：resume 场景下历史已从磁盘载入，
     * 追加到末尾会让服务端把 system 当成一轮普通对话（附录 A6），
     * 也会破坏前缀缓存所需的前缀稳定（P0-4 R8）。</p>
     */
    void installSystemPrompt() {
        conversationHistory.add(0, ModelMessage.system(buildSystemPrompt()));
    }

    /** 测试用：当前对话历史只读视图（包级可见）。 */
    List<ModelMessage> conversationHistory() {
        return Collections.unmodifiableList(conversationHistory);
    }

    /** 测试用：当前会话 id（包级可见）。 */
    String sessionId() {
        return sessionId;
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
            // R5：完整还原为 ModelMessage（含 tool_calls / tool_call_id / name）。
            // 改造前只搬 role 与 content，含工具调用的会话 resume 后 tool 消息丢失
            // tool_call_id，请求体会被服务端以 400 拒绝（AC5 要验证的就是这一点）。
            conversationHistory.add(msg.toModelMessage());
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
     *
     * <p>R4：本方法现在每个回合结束都调一次（而非仅退出时），并作为关闭钩子的入口，
     * 因此必须：<b>synchronized</b>（钩子可能与主线程并发）、<b>不抛异常</b>
     * （磁盘满、Windows 杀毒软件瞬时锁文件等 IO 故障不应终结对话）。得益于
     * {@code saveSession} 写的是全量快照，本轮落盘失败时下轮成功即可自动补齐。</p>
     */
    synchronized void persistSession() {
        try {
            Session session = sessionManager.getSession(sessionId);

            //将「尚未持久化」的聊天历史增量保存到session，并跳过 system 消息
            for (int i = persistedIndex; i < conversationHistory.size(); i++) {
                ModelMessage modelMessage = conversationHistory.get(i);
                if (modelMessage.getRole() == null) {
                    continue;
                }
                if ("system".equals(modelMessage.getRole())) {
                    continue;
                }
                // R5：搬运完整消息（含 tool_calls / tool_call_id / name），
                // 只存 role+content 会让含工具调用的会话 resume 后请求体非法（附录 A5）
                session.addMessage(modelMessage);
            }

            // 抬高水位下标，记录已持久化到的位置。刻意在 save 之前抬高：
            // 内存 Session 对象已持有这批消息，水位与之一致才能保证不重复追加；
            // 若 save 失败，下轮的全量快照会把本轮内容一并写回。
            persistedIndex = conversationHistory.size();

            sessionManager.saveSession(session);
        } catch (Exception e) {
            logger.warn("会话持久化失败（对话仍在内存中继续，下次落盘会自动补齐）: {}", e.getMessage());
        }

    }

    /**
     * 重试前的用户提示（{@link LlmRetryPolicy.RetryNotifier} 实现）。
     *
     * <p>告诉用户程序没卡死、在等什么、要等多久。若本次失败发生在流式输出中途
     * （已吐过半截文本），额外提示一句，避免重试后的完整回答被误认为输出重复。</p>
     */
    private void onRetryNotice(int attempt, int maxRetries, long backoffMillis, LlmTransportException error) {
        String cause = error.statusCode() > 0 ? "HTTP " + error.statusCode() : "网络异常";
        System.out.println();
        System.out.println(ConsoleUi.dimPrefix() + "模型调用失败（" + cause + "），"
                + formatSeconds(backoffMillis) + " 后重试(" + attempt + "/" + maxRetries + ")...");
        if (streamEmitted) {
            System.out.println(ConsoleUi.dimPrefix() + "上一段输出不完整，重试后重新给出完整回答。");
        }
    }

    /** 把毫秒格式化为可读秒数（500ms 显示 0.5s，2000ms 显示 2s）。 */
    private static String formatSeconds(long millis) {
        return millis >= 1000 && millis % 1000 == 0 ? (millis / 1000) + "s" : (millis / 1000.0) + "s";
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
        // R8：告知用户会话已安全保存，并直接给出可复制的恢复命令（免去先跑 sessions 找 id）
        announceSessionSaved();

        // 测试构造下轨迹收集与知识提取未启用，直接走关闭流程（避免 NPE）
        boolean learningDisabled = trajectoryCollector == null || knowledgeExtractor == null;

        // ② 将聊天数据放入轨迹中（入队即返回，后台线程异步写盘）
        //    只上报本次运行新增的消息：resume 载入的旧历史在上一轮运行中已上报过，
        //    全量重放会让同一份对话重复写入轨迹样本，污染自我进化数据
        List<ModelMessage> newMessages = newMessagesThisRun();
        if (!learningDisabled) {
            for (ModelMessage modelMessage : newMessages) {
                trajectoryCollector.addMessage(sessionId, modelMessage);
            }
            trajectoryCollector.endSession(sessionId, completed);
        }

        // ③ 提取知识放最后：这一步包含 LLM 网络调用，可能耗时数十秒。
        //    旧实现把它放在 persistSession 之前且异常会直接冒泡，导致 exit 卡死体验差；
        //    现在 goodbye 已先行打印（见 run()），且提取失败不影响退出流程。
        //    用户可见输出一律是「人话」短提示（System.out）；
        //    完整 insights/记忆清单属机器细节，降到 DEBUG（HERMES_LOG_LEVEL=DEBUG 可查）。
        //    注意「📝 稍等」提示只在会话确实达到提取门槛时打印——短会话直接静默跳过，
        //    否则用户会看到“稍等”之后没有任何下文（KnowledgeExtractor 内部按门槛返回 empty）。
        //    提取对象同样只是本次新增消息（修复：resume 后直接 exit 不再重复提取旧历史）
        if (!learningDisabled && completed && knowledgeExtractor.willExtract(newMessages)) {
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

        //关闭轨迹收集（仅开启自进化组件时存在）
        if (!learningDisabled) {
            trajectoryCollector.shutdown();
        }

    }

    /**
     * 输出「会话已保存」提示（P0-5 R8，文案对齐规范 §14.1）。
     *
     * <p>形式：{@code 会话已保存（id: cli3f9a1c2e，12 条消息）· 恢复: Jhermes resume cli3f9a1c2e}。
     * 旧实现退出时不告知会话去向，用户不知道可以用 resume 接续（附录 C-03 指出的体验缺口）。</p>
     *
     * <p>无消息（用户启动后直接 exit）时静默跳过，避免为一场空会话刷无用提示。</p>
     */
    private void announceSessionSaved() {
        try {
            int messageCount = sessionManager.getSession(sessionId).messages.size();
            if (messageCount == 0) {
                return;
            }
            System.out.println("会话已保存（id: " + sessionId + "，" + messageCount + " 条消息）"
                    + "· 恢复: " + Constants.CLI_NAME + " resume " + sessionId);
        } catch (RuntimeException e) {
            logger.debug("输出会话保存提示失败: {}", e.getMessage());
        }
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
        // R7：system 提示词置于历史下标 0（对新会话与 resume 场景语义一致，见方法注释）
        installSystemPrompt();

        // R4：注册 JVM 关闭钩子作为持久化兜底，覆盖 Ctrl+C / SIGTERM / 未捕获异常退出，
        // 使「长任务中途失败则整场对话丢失」（附录 A4）不再发生。
        registerPersistenceHook();

        // P0-2 R0：复用 Ui 层的<b>全局唯一</b>输入通道，而不是再 new 一个
        // BufferedReader(System.in)——确认闸门与本 REPL 必须共用同一个 reader，
        // 否则 BufferedReader 的预读缓冲会把用户接下来要敲的 y/n 吞掉，
        // 闸门永久阻塞（现象是「确认提示出来后敲什么都没反应」）。
        UserInput userInput = Ui.userInput();
        boolean firstPrompt = true;
        try {
            while (true) {
                // 醒目的用户提示符：分隔线 + 绿色「▶ 你:」前缀（ConsoleUi 统一渲染，
                // 支持 ANSI 的终端显示彩色，老终端自动降级为纯符号，绝不乱码）。
                // 首轮不打分隔线，避免与启动横幅重叠——故先取值再翻转标志。
                String prompt = ConsoleUi.userPrompt(!firstPrompt);
                firstPrompt = false;
                String input = userInput.readLine(prompt);
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
                String trimmed = input.trim();
                if (trimmed.equalsIgnoreCase("exit")) {
                    break;
                }
                // R2：重发上一个因传输错误失败的回合（无需用户重新敲一遍问题）
                if (trimmed.equalsIgnoreCase(RETRY_COMMAND)) {
                    retryLastTurn();
                    continue;
                }

                //处理用户输入问题
                processUserMessage(input);

            }
            // 先打印告别语再做收尾：endSession 内部的知识提取涉及 LLM 网络调用（可能数十秒），
            // 旧实现把告别语放在提取之后，导致用户输入 exit 后面对长时间无响应的黑屏。
            // 统一在此收尾：无论是 exit、EOF 还是循环异常退出，都保证会话被正确结束与持久化
            System.out.println("再见。");
            endSession(true);

        } catch (RuntimeException e) {
            // 非正常结束：标记 completed=false，便于轨迹区分成功/失败会话。
            // 输入层的 IOException 已由 UserInput 实现内部吸收（返回 null 走 EOF 收尾），
            // 这里只剩真正的意外（如轨迹/记忆组件抛错），必须落盘后再交回 picocli。
            endSession(false);
            throw e;
        } finally {
            // 不关闭共享 reader：确认闸门可能仍在等待输入，
            // 且关闭 System.in 后无法恢复（见 UserInput.close() 的设计说明）
            userInput.close();
        }
    }

    /**
     * 注册 JVM 关闭钩子：进程被信号终止时仍把已完成的回合落盘（P0-5 R4）。
     *
     * <p>钩子内<b>只做本地 IO</b>，绝不调用 LLM——规范要求如此，否则用户按 Ctrl+C
     * 退出时还要干等知识提取的网络往返。{@link #persistSession()} 依赖持久化水位做增量，
     * 与正常退出路径重复调用是幂等的。</p>
     */
    private void registerPersistenceHook() {
        try {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(this::persistSessionQuietly, "jhermes-session-saver"));
        } catch (IllegalStateException e) {
            // JVM 已在关闭流程中（极端时序）：钩子注册失败不影响正常路径，主流程已逐轮持久化
            logger.debug("注册持久化关闭钩子失败: {}", e.getMessage());
        }
    }

    /** 关闭钩子入口：吞掉异常，因为钩子里抛出会打断 JVM 退出并在屏幕上留下刺眼堆栈。 */
    private void persistSessionQuietly() {
        try {
            persistSession();
            logger.debug("关闭钩子已完成会话持久化");
        } catch (Exception e) {
            logger.warn("关闭钩子持久化会话失败: {}", e.getMessage());
        }
    }

    /**
     * 处理用户输入的内容
     *
     * @param input
     */
    void processUserMessage(String input) {
        conversationHistory.add(ModelMessage.user(input));//将用户输入的内容添加到聊天内容
        lastFailedInput = input;//先登记，回合失败时供 /retry 重发

        runTurn();
        persistSession();//R4 逐轮持久化：每个回合（含工具循环）结束后落盘
    }

    /**
     * {@code /retry}：重发上一个失败回合的用户输入（P0-5 R2）。
     *
     * <p>不重新入队 user 消息——{@link #rollbackTurnTo} 回滚时刻意保留了该条消息，
     * 再入队会让历史里出现两条相同的 user 消息并重复计费。</p>
     */
    void retryLastTurn() {
        if (lastFailedInput == null) {
            System.out.println(ConsoleUi.dimPrefix()
                    + "当前没有可重试的失败回合（" + RETRY_COMMAND + " 仅在请求失败后可用）。");
            return;
        }
        System.out.println(ConsoleUi.dimPrefix() + "正在重发上一轮请求…");
        runTurn();
        persistSession();
    }

    /**
     * 执行一个「回合」：从已入队的 user 消息出发跑工具调用循环，直到模型给出最终文本回复。
     *
     * <p>R2 回合级错误边界：LLM 调用失败不再穿透到 REPL 主循环把进程带崩（附录 A1），
     * 而是①回滚本回合的半截消息②向用户展示可读错误③保持 REPL 存活④登记 {@code /retry}。</p>
     */
    private void runTurn() {
        // 本轮 token 用量单独记账：一次提问在工具调用循环里可能触发多次模型请求，
        // 全部累加后才在轮末展示（只统计最后一次会严重低报）。
        // 每轮 new 一个实例 = 下轮自动归零，无需手动 reset。
        TokenUsageTracker turnUsage = new TokenUsageTracker();
        // 回滚锚点：本回合开始时（user 消息已入队）的历史长度。
        int safetyMark = conversationHistory.size();

        try {
            turnLoop(turnUsage);
        } catch (LlmTransportException e) {
            // 重试耗尽或不可重试的传输错误：可读错误 + 排查指引已由异常消息承载
            rollbackTurnTo(safetyMark);
            reportTurnFailure(e);
            return;
        } catch (RuntimeException e) {
            // 兜住其余运行时异常（响应结构异常、工具注册表状态异常等），
            // 保证任何单轮故障都不终结会话——这是 P0-5 的核心目标
            rollbackTurnTo(safetyMark);
            logger.warn("回合处理异常", e);
            reportTurnFailure(e);
            return;
        }

        // 回合成功：清除失败登记，打印用量汇总
        lastFailedInput = null;
        printUsageSummary(turnUsage);
    }

    /**
     * 回合内的工具调用循环（原 processUserMessage 主体）。
     *
     * <p>刻意不做 catch：异常统一由 {@link #runTurn()} 处置，使「循环逻辑」与「错误边界」分离。</p>
     */
    private void turnLoop(TokenUsageTracker turnUsage) {
        boolean continueLoop = true;
        int turnCount = 0;//轮次数量
        while (continueLoop) {
            turnCount++;
            // 流式模式下是否已吐过文本，供重试提示判断（见 onRetryNotice）
            streamEmitted = false;
            boolean streaming = config.isStreamEnabled();
            ChatCompletionResponse response;
            if (streaming) {
                // 流式：懒打印前缀——只有真正收到第一个文本增量才打印助手标识。
                // 旧实现无条件先打前缀：当模型这一轮选择调工具而不是说话时，
                // 屏幕上会留下一个空的 "Assistant:" 行（用户反馈的体验 bug）。
                // 增量不直接裸打印，而是经过 ConsoleUi.StreamingRenderer 做跨 token 安全的
                // 空白规范化：连续换行压缩为最多一个空行、行尾/开头空白清除（用户反馈的
                // 「回答很多换行且不美观」的终端侧兜底；提示词侧已同步约束模型输出）。
                boolean[] prefixPrinted = {false};//是否打印过前缀，因为在lambda中不能修改外部局部变量，只能用数组绕过
                ConsoleUi.StreamingRenderer renderer = ConsoleUi.newStreamingRenderer(text -> {
                    System.out.print(text);
                    System.out.flush();
                });
                response = callLlm(piece -> {
                    if (!prefixPrinted[0]) {
                        System.out.print(ConsoleUi.assistantPrefix());
                        prefixPrinted[0] = true;
                    }
                    streamEmitted = true;
                    renderer.accept(piece);
                });
                // 只有输出过正文才补换行，避免后续输出与正文粘连；纯工具轮不留空行
                if (prefixPrinted[0] && renderer.finish()) {
                    System.out.println();
                }
            } else {
                response = callLlm(null);
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
    }

    /**
     * 经重试策略调用 LLM（流式与非流式的统一入口，P0-5 R3）。
     *
     * <p>是否流式取决于配置/CLI 开关；调用方无需关心重试——可重试错误由
     * {@link LlmRetryPolicy} 内部指数退避重发，不可重试错误立即抛出由 runTurn 处置。</p>
     *
     * @param onContent 流式文本增量回调；非流式或未使用时传 null
     */
    private ChatCompletionResponse callLlm(Consumer<String> onContent) {
        return retryPolicy.call(() -> config.isStreamEnabled()
                ? llmClient.chatCompletionStream(conversationHistory, toolDefinitions, onContent)
                : llmClient.chatCompletion(conversationHistory, toolDefinitions, false));
    }

    /**
     * 回滚本回合产生的半截消息（P0-5 R2）。
     *
     * <p>为什么必须回滚：一次请求失败可能把历史停在「assistant 带 2 个 tool_calls，
     * 但只有 1 条 tool 响应」的非法状态。这样的历史再发给服务端会被以 400 拒绝，
     * 且该会话一旦被持久化，resume 后同样无法继续（附录 A5 同类问题）。</p>
     *
     * <p>回滚目标锚点包含 user 消息——用户的问题被保留，{@code /retry} 才能免去重新输入。
     * 代价是本回合已取得的工具结果被丢弃，重试会重新获取；这是正确性与成本的取舍：
     * 保留半截结果换取「可能非法的历史」不可接受。</p>
     *
     * <p>同步回退持久化水位：否则磁盘上已写入的消息与被截断的内存历史会分叉，
     * 下一次增量持久化把这些消息再追加一遍造成重复。</p>
     */
    private void rollbackTurnTo(int mark) {
        int size = conversationHistory.size();
        if (size <= mark) {
            return;
        }
        conversationHistory.subList(mark, size).clear();
        if (persistedIndex > mark) {
            persistedIndex = mark;
        }
        logger.warn("回合失败，已回滚 {} 条半截消息（历史长度 {} → {}）", size - mark, size, mark);
    }

    /**
     * 向用户展示回合失败原因，并给出下一步动作（P0-5 R2）。
     *
     * <p>文案区分传输错误与其他异常：前者带状态码、是否已重试、以及异常自带的排查指引
     * （api_key/base_url 等具体修复动作），后者只给一行摘要 + 引导查日志，
     * 避免把整条堆栈刷到用户脸上（完整堆栈已在 WARN 日志）。</p>
     */
    private void reportTurnFailure(Exception e) {
        System.out.println();
        if (e instanceof LlmTransportException lte) {
            String cause = lte.statusCode() > 0 ? "HTTP " + lte.statusCode() : "网络错误";
            String retried = lte.isRetryable()
                    ? "，已自动重试 " + LlmRetryPolicy.MAX_RETRIES + " 次仍失败" : "，此类错误不自动重试";
            System.out.println(ConsoleUi.dimPrefix() + "本轮请求失败（" + cause + retried + "）");
            System.out.println(e.getMessage());
        } else {
            System.out.println(ConsoleUi.dimPrefix() + "本轮处理出现异常：" + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
        System.out.println(ConsoleUi.dimPrefix() + "会话仍然可用：输入 " + RETRY_COMMAND
                + " 重发本轮，或直接提新问题。");
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
            // 附录 A3：改造前此处直接 throw RuntimeException，模型给出一次畸形 JSON
            // （轻量模型常见：参数串被截断、引号未闭合）就会让整个进程退出且会话丢失。
            // 正确处置是把错误回传给模型——它看到「参数不是合法 JSON」通常会自行纠正重发。
            String name = toolCall.getFunction() != null ? toolCall.getFunction().getName() : "?";
            logger.warn("工具 {} 的 arguments 不是合法 JSON: {}", name, e.getOriginalMessage());
            return ToolRegistry.toolError("工具 " + name + " 的 arguments 参数不是合法 JSON，无法解析。"
                    + "请重新调用该工具并确保 arguments 是严格的 JSON 对象（注意引号闭合与转义）。");
        }

    }

    /**
     * 构建系统提示词（P0-3：委托 {@link PromptComposer}）。
     *
     * <p>改造前这里是 90 行硬编码拼接：顺序靠代码行序、条件判据是配置项、
     * 工具清单与 tools 数组重复、技能注入依赖 SkillMatcher 的历史文本匹配
     *（新会话恒为空，附录 A19）。现在 Agent 只供给四项装配上下文：</p>
     * <ul>
     *   <li>{@code CapabilityRegistry.from(toolRegistry)} —— 注册实况：requires 判定与
     *       capabilities 渲染同源（R7，修复「配置开着但工具没注册上、指引照样注入」的 C-01）；</li>
     *   <li>{@code WorkingDirSnapshot.capture()} —— 目录感知（原样迁移，期 4 叠加 R8 过滤）；</li>
     *   <li>记忆快照 —— MemoryManager 预渲染文本，composer 作动态尾段；</li>
     *   <li>技能清单 —— composer 经 SkillIndexBuilder 渲染紧凑索引（R9，正文按需 get）。</li>
     * </ul>
     *
     * <p>同一上下文多次调用结果逐字节相同（P0-4 R8 前缀稳定的基础）。</p>
     */
    private String buildSystemPrompt() {
        CapabilityRegistry capabilities = CapabilityRegistry.from(toolRegistry);
        ComposedPrompt composed = promptComposer.compose(new PromptContext(
                capabilities,
                WorkingDirSnapshot.capture(),
                memoryManager.getSystemPromptSnapshot(),
                SkillManager.getInstance().listAll()));
        // R6 一致性自检：提示词中出现「疑似工具名但未注册」→ WARN（不阻断会话）。
        // 只扫静态创作段（记忆尾段属用户数据，见 checker 说明）；
        // installSystemPrompt 每次运行只调一次，不会刷屏。
        PromptConsistencyChecker.warnIfInconsistent(composed, capabilities);
        return composed.text();
    }

}

