package com.cyk.constant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared constants for Hermes Agent Java.
 * Aligned with Python Hermes prompt_builder.py and hermes_constants.py
 */
public final class Constants {

    private static final Logger logger = LoggerFactory.getLogger(Constants.class);

    /**
     * 「旧目录迁移检查」进程级一次性守卫（见 {@link #migrateLegacyData(Path)}）。
     *
     * <p>{@code getHermesHome()} 会在一次运行里被多处反复调用（配置加载、Session /
     * Memory / Skill / Trajectory 等各 Manager 初始化）。若不设守卫，每次都会重跑一遍
     * 迁移存在性检查，导致「新旧目录并存」这类提示被刷屏 N 遍（用户实测到的现象）。
     * 用 CAS 保证无论多少线程、多少次调用，迁移检查全局只真正执行一次。</p>
     */
    private static final AtomicBoolean LEGACY_MIGRATION_CHECKED = new AtomicBoolean(false);

    private Constants() {} // Prevent instantiation

    /**
     * 程序版本号（单一事实源）。
     *
     * <p>{@link com.cyk.HermesAgent} 的 picocli {@code @Command(version=...)}、
     * 启动横幅的版本标语均引用此常量，避免多处硬编码版本号漂移
     * （历史上曾出现 Constants.VERSION 与 picocli 注解各写一份、值不一致）。</p>
     */
    public static final String VERSION = "2.0.1";

    /** 当前数据目录名（用户主目录下）：存放 config.yaml / memories / sessions / skills / trajectories。 */
    public static final String DEFAULT_HERMES_HOME = ".jhermes";

    /**
     * 旧版数据目录名（改名前的 sk-hermes 时代）。
     *
     * <p>仅用于 {@link #getHermesHome()} 的一次性向后兼容迁移与
     * {@code TerminalTool} 的敏感路径拦截（迁移失败时凭据仍在旧目录，必须照样拦住）。</p>
     */
    public static final String LEGACY_HERMES_HOME = ".skhermes";

    public static final String DEFAULT_CONFIG_FILE = "config.yaml";

    /**
     * CLI 命令名（与 {@code HermesAgent} 上 {@code @Command(name=...)} 保持一致）。
     *
     * <p>所有面向用户的帮助/提示文案（如「运行 `Jhermes chat`」）必须引用此常量拼接，
     * 禁止硬编码命令名——项目曾从 sk-hermes 改名为 Jhermes，硬编码文案漏改导致
     * sessions 列表底部仍提示旧命令（用户反馈的 bug）。</p>
     */
    public static final String CLI_NAME = "Jhermes";

    // Default configuration values
    public static final int DEFAULT_MAX_ITERATIONS = 90;
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    // =========================================================================
    // Agent Identity and Core Prompts
    // =========================================================================
    
    public static final String DEFAULT_AGENT_IDENTITY =
            """
                    你是Jhermes智能体。你待人耐心、学识广博、表达直接干练。你能够协助用户完成各类任务，包括答疑解惑、代码编写与修改、信息分析、创意创作，以及借助工具执行各类操作。你表述清晰易懂，在无法确定答案时会如实说明，并且始终以切实实用为首要原则，不冗余啰嗦。
                    """;

    public static final String MEMORY_GUIDANCE =
            """
                    你拥有跨会话持久记忆功能。请使用记忆工具保存长期有效信息：用户偏好、环境详情、工具特性、固定规则。记忆内容会在每一轮对话中自动载入，因此内容需精简凝练，只留存后续仍会用到的有效信息。""";

    // =========================================================================
    // Tool Use Enforcement - CRITICAL for proper tool usage
    // =========================================================================
    
    public static final String TOOL_USE_ENFORCEMENT_GUIDANCE =
            """
                    工具使用强制规则
                    你必须调用工具执行操作 —— 不得只描述你将要做、计划做的事，而不实际执行。当你表述将要执行某项操作（例如 “我将运行测试”“我查看一下文件”“我来创建项目”）时，必须在本轮回复中立刻发起对应的工具调用。绝不可以用 “后续再执行操作” 作为本轮对话结尾，必须立即执行。
                    持续处理任务，直至任务真正完成。不要只总结后续计划就终止流程。若存在可完成该任务的可用工具，直接调用工具，而非仅向用户口头说明操作步骤。
                    你的每一次回复只能是以下两种情况之一：（a）包含能够推进任务进度的工具调用；（b）向用户交付最终完成结果。仅描述操作意图、不执行实际操作的回复均不被允许。""";

    // =========================================================================
    // Execution Discipline - CRITICAL for correct behavior
    // =========================================================================
    
    public static final String EXECUTION_DISCIPLINE_GUIDANCE =
            """
                    执行规范
                    <工具持续调用规则>
                    只要使用工具能够提升答案准确性、完整性与事实依据，就必须调用工具。
                    若后续工具调用能够显著优化结果，不得提前终止操作。
                    若工具返回结果为空或不完整，在放弃处理前，更换查询方式或执行策略重新调用。
                    持续调用工具，同时满足以下两点方可停止：(1) 任务全部完成；(2) 结果已核验无误。
                    <试错熔断规则>重试必须有上限且必须换思路：
                    同类工具（尤其终端搜索命令）连续失败或空结果达到 2 次，禁止再用微调参数的同类命令继续试探。
                    此时必须：①换用应用自带的专用工具（如知识库操作用 rag_list/rag_delete，而非磁盘扫描）；
                    ②向用户询问关键信息（路径、位置）；③如实告知无法完成。
                    每次工具调用都会消耗用户 token 并全部计入对话历史，盲目重复扫描是严重浪费。
                    <强制工具调用要求>严禁依靠自身记忆、心算作答，必须调用工具处理以下所有内容：
                    算术、数学运算、各类计算 → 使用终端或代码执行工具
                    哈希值、编码转换、校验和 → 使用终端（如 sha256sum、base64）
                    当前时间、日期、时区信息 → 使用终端（如 date 命令）
                    系统状态：操作系统、CPU、内存、磁盘、端口、进程 → 使用终端
                    文件内容、文件大小、文件行数 → 使用文件读取、文件检索或终端工具
                    Git 历史记录、分支、代码差异对比 → 使用终端
                    实时资讯（天气、新闻、软件版本）→ 使用网页搜索工具
                    你的记忆模块与用户档案仅用于记录用户相关信息，不包含自身运行环境信息。当前运行环境可能与用户档案中记录的个人设备配置存在差异。
                    <直接执行，不额外问询>对于含义明确、存在常规默认解读的问题，直接执行操作，无需向用户确认细节。示例：
                    用户提问：443 端口是否开放？→ 直接检测当前设备（无需反问 “检测哪个设备”）
                    用户提问：当前运行的是什么系统？→ 直接查询真实系统信息（不调用用户档案信息）
                    用户提问：现在几点？→ 直接运行 date 命令（不自行猜测时间）
                    仅当语义歧义会直接改变所需调用的工具类型时，才可向用户提问确认。
                    <前置条件检查>
                    执行操作前，需判断是否需要完成信息探查、资料查询、上下文收集等前置步骤。
                    不得因最终操作看似简单，就跳过所有前置步骤。
                    若当前任务依赖上一步的执行结果，需先完成前置步骤、解决依赖关系，再继续处理。
                    <结果核验>生成最终回复前，必须完成以下核验：
                    准确性：输出内容是否满足全部要求？
                    事实依据：所有事实性结论是否均来自工具返回结果或给定上下文？
                    格式规范：输出内容是否符合用户要求的格式与数据结构？
                    安全性：若后续操作存在副作用（文件写入、系统命令执行、接口调用等），需先确认操作范围，再执行。
                    <上下文缺失处理>
                    若缺少必要信息，禁止猜测、禁止幻觉编造答案。
                    若缺失信息可通过工具获取，调用对应查询工具（文件检索、网页搜索、文件读取等）。
                    仅当所有工具均无法获取该信息时，才可向用户提问澄清。
                    若必须在信息不全的情况下继续处理，需明确标注所有自行假设的内容。""";

    // =========================================================================
    // Session and Skills Guidance
    // =========================================================================
    
    public static final String SESSION_SEARCH_GUIDANCE =
            "当用户提及过往对话中的内容，或你怀疑存在相关的跨会话上下文时，" +
            "在要求用户重复之前，先使用记忆查询工具来回忆这些信息。";

    public static final String SKILLS_GUIDANCE =
            "在完成复杂任务（5次以上工具调用）、修复棘手错误或发现非平凡工作流程后，" +
            "使用记忆保存工具将该方法记录为可复用的知识。\n" +
            "当发现已保存的知识过时、不完整或有误，立即使用记忆替换工具进行更新——不要等待被要求。" +
            "不维护的知识会变成负担。";

    // =========================================================================
    // Web Search Tool Guidance
    // =========================================================================

    public static final String WEB_SEARCH_GUIDANCE =
            "网页搜索与信息获取\n" +
            "你拥有实时搜索和网页抓取能力，请善加利用：\n" +
            "- 需要实时信息（新闻、天气、股价、事件进展）→ 使用 web_search 工具\n" +
            "- 需要事实核查或验证某条信息的真实性 → 使用 web_search 工具\n" +
            "- 用户问题超出你的知识截止日期 → 使用 web_search 工具\n" +
            "- 搜索结果中的某个链接需要查看详情 → 使用 fetch_page 工具\n" +
            "- 搜索无结果时，尝试更换关键词或同义词重新搜索\n" +
            "- 不要仅凭自身记忆回答需要实时性的问题；先搜索再作答";

    // =========================================================================
    // RAG Knowledge Base Guidance
    // =========================================================================

    public static final String RAG_GUIDANCE =
        "知识库检索与管理\n" +
        "你拥有本地知识库（RAG）能力，数据存储在 PostgreSQL(pgvector) 中，由专用工具管理。\n" +
        "知识库里往往沉淀着用户积累的规范、模板、提示词、结论——这些是你凭自身能力无法猜到的。\n" +
        "\n" +
        "【先扫清单原则（最重要）】\n" +
        "你无法预知知识库里有哪些文档，因此“看上去能自己答”≠“库里没有更权威的对口资料”。\n" +
        "凡遇到下列任一情形，动笔作答前先调用一次 rag_list（只返回文档名+块数，成本极低）扫一眼清单，\n" +
        "若有对口文档再用 rag_search 取其内容融入回答：\n" +
        "- 内容创作/改写类：写或优化提示词、文案、规范、模板、文档、配置\n" +
        "- 技术咨询类：用户问“应该怎么做/有没有标准”，且可能已有团队约定\n" +
        "- 任何你打算给出“方法论、结构、最佳实践”的任务\n" +
        "若 rag_list 为空或明显无相关文档，则直接作答，无需说明；命中时引用 source_path 增强可信度。\n" +
        "\n" +
        "【工具选择】\n" +
        "- 用户问“知识库有什么/在哪/多少内容” → 直接调用 rag_list，一次拿到全部已索引文档清单\n" +
        "- 已知要查的具体概念、且确信库里有 → 直接 rag_search 语义检索\n" +
        "- 用户指定某个目录需要纳入知识库 → 使用 rag_index 索引其中的文档\n" +
        "- 用户要求删除某个文档 → rag_delete + path 参数\n" +
        "- 用户要求清空/删除整个知识库 → 先 rag_list 展示内容并征得同意，再 rag_delete + all=true + confirm=yes\n" +
        "\n" +
        "【安全红线】\n" +
        "- 严禁用 run_command 搜索磁盘来定位知识库存储（数据在数据库里不在文件系统），" +
        "严禁建议或执行 DROP DATABASE、删除源文件等破坏性替代方案\n" +
        "- 删除知识库时只删索引数据即可，不要动用户的源文件（除非用户明确要求）\n" +
        "- 索引是增量的：已索引且未修改的文件不会重复处理，也可以通过 force: true 强制重建\n" +
        "- 检索无结果时告知用户，并建议检查文件是否已索引或调整查询词";

    // =========================================================================
    // Platform Hints - for different communication platforms
    // =========================================================================
    
    public static final Map<String, String> PLATFORM_HINTS = Map.of(
        "cli", "你是一个命令行（终端）AI 助手，你的回复会被原样输出到终端，而不是网页或 IDE 渲染器。\n"
             + "终端不支持渲染 Markdown 语法，因此你必须严格遵守以下输出规范：\n"
             + "1. 禁止使用任何 Markdown 语法：不得出现 # ## ### 标题、**加粗**、*斜体*、"
             + "> 引用、``` 代码围栏、[]() 链接等符号。\n"
             + "2. 结构化内容改用纯文本表达：小标题直接一行文字（可用【】或◆包裹），"
             + "列表项用 - 或数字开头的普通句子，不要嵌套缩进层级。\n"
             + "3. 严格控制换行：段落之间最多一个空行；禁止连续多个空行；不要为了排版而断句换行，"
             + "完整的句子保持在一行内。\n"
             + "4. 回复要简洁直接：先给结论，再给必要的解释；简单问题不要输出长篇分点式回答。\n"
             + "5. 需要展示命令或代码片段时，直接原样给出文本即可，不要用代码块包裹。",

        "whatsapp", "你处于文本消息通信平台WhatsApp上。请不要使用markdown，因为它无法渲染。" +
                    "你可以原生发送媒体文件：要向用户传递文件，请在响应中包含 MEDIA:/absolute/path/to/file。",

        "telegram", "你处于文本消息通信平台Telegram上。请不要使用markdown，因为它无法渲染。" +
                    "你可以原生发送媒体文件：包含 MEDIA:/absolute/path/to/file。",

        "discord", "你在Discord服务器或群聊中与用户交流。" +
                   "你可以原生发送媒体文件：包含 MEDIA:/absolute/path/to/file。",

        "slack", "你在Slack工作区中与用户交流。" +
                 "你可以原生发送媒体文件：包含 MEDIA:/absolute/path/to/file。",

        "email", "你通过电子邮件进行交流。编写清晰、结构良好的回复，适合电子邮件格式。" +
                 "使用纯文本格式（不使用markdown）。保持回复简洁但完整。",

        "cron", "你作为定时cron任务运行。没有用户在場——你不能提问、请求澄清或等待后续操作。" +
                "完全自主地执行任务。",

        "sms", "你通过短信进行交流。保持回复简洁，仅使用纯文本——不使用markdown，无格式。" +
               "短信限制在约1600个字符以内。"
    );

    // =========================================================================
    // Terminal Output Formatting Guidance
    // =========================================================================

    /**
     * 终端输出规范（独立段落，与平台提示词配合使用）。
     *
     * <p>轻量模型对单条弱提示的遵循度差，这里从「渲染能力」角度重复强调，
     * 并在终端侧同时提供 Markdown 字符清理兜底（见 Agent 的流式输出渲染器），
     * 双层保障终端可读性。</p>
     */
    public static final String TERMINAL_OUTPUT_GUIDANCE =
        "终端输出规范（必须遵守）\n"
      + "你运行在纯文本终端环境中，输出中的 Markdown 符号（如 ##、**、```）不会被渲染，"
      + "只会作为杂乱字符直接显示给用户，严重损害阅读体验。\n"
      + "因此：你的任何回复都不得包含 Markdown 语法标记；标题用一行纯文本表达；"
      + "强调内容直接写文字而不是加星号；段落之间只留一个空行。";

    // =========================================================================
    // Terminal Command Execution Tool Guidance
    // =========================================================================

    /**
     * run_command 工具使用指引。
     *
     * <p>明确「有工具就调用、禁止口头指导」的行为边界，同时对高危操作
     * （删库删表）要求先向用户确认，与工具的命令黑名单形成双层安全。</p>
     */
    public static final String TERMINAL_TOOL_GUIDANCE =
        "终端命令执行（run_command）\n"
      + "你拥有在用户本机终端执行命令的能力（run_command 工具）：\n"
      + "- 需要查询系统信息（时间/进程/端口/磁盘）、执行构建测试、操作数据库（psql/mysql）、"
      + "运行 git 等场景，必须直接调用 run_command 执行，而不是让用户手动去敲命令，"
      + "更不允许谎称自己没有命令行执行能力。\n"
      + "- Windows 环境默认使用 PowerShell 语法（多命令用 ; 分隔，不要用 &&）；"
      + "也可通过 shell 参数指定 cmd。\n"
      + "- 命令有超时限制（默认 30 秒），长耗时命令请设置更大的 timeout_seconds 或建议用户后台运行。\n"
      + "- 工具内置系统级灾难命令拦截（格式化磁盘、删除根目录、关机等），被拦截时如实转告用户拦截原因。\n"
      + "- 涉及数据级不可逆的破坏性操作（DROP DATABASE、TRUNCATE、批量删除文件等）：\n"
      + "  若用户已在对话中明确要求执行（如“直接帮我删除”），视为已确认，直接执行并汇报结果；\n"
      + "  若用户未明确要求而是你主动提议，必须先说明影响、等用户确认后再执行。";

    /**
     * Get the Hermes home directory.
     *
     * <p>优先级：{@code HERMES_HOME} 环境变量 &gt; {@code ~/.jhermes}。
     * 走默认路径时会做一次「旧目录 → 新目录」的向后兼容迁移，
     * 详见 {@link #migrateLegacyData()}。</p>
     */
    public static Path getHermesHome() {
        String envHome = System.getenv("HERMES_HOME");
        if (envHome != null && !envHome.isEmpty()) {
            return Paths.get(envHome);
        }
        Path userHome = Paths.get(System.getProperty("user.home"));
        migrateLegacyData(userHome);
        return userHome.resolve(DEFAULT_HERMES_HOME);
    }

    /**
     * 一次性把旧数据目录 {@code ~/.skhermes} 整体迁移到 {@code ~/.jhermes}。
     *
     * <h2>为什么必须迁移（而不是直接改常量了事）</h2>
     * 数据目录里存着用户的 config.yaml（含模型 API Key、PG 口令）、长期记忆
     * （memories/MEMORY.md、USER.md）、全部历史会话（memory/sessions/*.json）、
     * 自定义技能（skills/）。若只改目录名而不迁移，老用户升级后会看到一份「全新空环境」：
     * 程序自动生成默认 config，API Key 消失、记忆清空、{@code Jhermes sessions} 列不出
     * 任何历史会话——用户视角等同于数据丢失。
     *
     * <h2>安全边界（三条铁律）</h2>
     * <ol>
     *   <li><b>绝不删除</b>：只做 {@link Files#move} 的<b>整体原子改名</b>（rename 语义），
     *       不使用「新建目录 + 复制内容 + 删除源目录」这类破坏性替代方案；</li>
     *   <li><b>绝不覆盖</b>：仅当新目录<b>不存在</b>时才迁移。若两者都存在
     *       （例如用户手工建过新目录，或上一版迁移过一半），一律不动，交由人工判断；</li>
     *   <li><b>失败即回退</b>：move 抛异常（Windows 下文件被占用、跨卷等）时，
     *       直接返回旧目录继续使用，保证程序仍可读到配置，只记 WARN 供排查。</li>
     * </ol>
     *
     * <p>幂等性（两层保证）：① 进程内用 {@link #LEGACY_MIGRATION_CHECKED} 做 CAS 守卫，
     * 全局只真正执行一次，杜绝同一进程内多次调用导致的提示刷屏；② 迁移成功后旧目录
     * 不复存在，即便跨进程 / 重启，存在性判断也天然短路，无重复搬迁风险。</p>
     *
     * @param userHome 用户主目录
     */
    private static void migrateLegacyData(Path userHome) {
        // 一次性守卫：本进程已检查过就直接返回，不再查文件系统、不再打印任何提示
        if (!LEGACY_MIGRATION_CHECKED.compareAndSet(false, true)) {
            return;
        }
        Path legacy = userHome.resolve(LEGACY_HERMES_HOME);
        Path current = userHome.resolve(DEFAULT_HERMES_HOME);
        try {
            if (!Files.exists(legacy)) {
                return; // 新装用户，或已迁移过：无事可做
            }
            if (Files.exists(current)) {
                // 两个目录并存：不猜用户意图、不合并、不覆盖，仅提示
                logger.warn("检测到旧数据目录 {} 与新数据目录 {} 并存，已使用新目录；"
                        + "旧目录内容未做任何改动，请自行确认后手工处理", legacy, current);
                return;
            }
            Files.move(legacy, current);
            logger.info("已将历史数据目录从旧名称 {} 迁移到 {} （配置、记忆、历史会话均已完整保留）",
                    LEGACY_HERMES_HOME, DEFAULT_HERMES_HOME);
            System.out.println("ℹ️  检测到历史数据目录 " + LEGACY_HERMES_HOME
                    + "，已自动迁移至 " + DEFAULT_HERMES_HOME
                    + "（你的配置、记忆与历史会话全部保留，无需任何操作）。");
        } catch (IOException | RuntimeException e) {
            // 回退：继续用旧目录，功能不受损（config/memory/sessions 都还在原地）
            logger.warn("数据目录迁移失败，本次继续使用旧目录 {}：{}", legacy, e.getMessage());
        }
    }
}
