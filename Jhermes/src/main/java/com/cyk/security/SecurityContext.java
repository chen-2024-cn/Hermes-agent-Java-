package com.cyk.security;

import com.cyk.config.HermesConfig;
import com.cyk.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <h1>进程级命令安全上下文（JH-SPEC-001 P0-2）</h1>
 *
 * <p>{@code TerminalTool} 是静态 handler 架构（{@code dispatch} 只传 {@code name+args}，
 * 不携带会话上下文），而命令门禁需要策略、确认闸门、审计器、会话 id、{@code --yolo}/审计模式等。
 * 本类作为进程级持有者把它们收口到一处——与 {@code TerminalTool} 已有的静态计数表
 * （{@code COMMAND_COUNTS}）同一风格。Jhermes 是单进程单会话 CLI，此假设成立。</p>
 *
 * <h2>初始化时序</h2>
 * <ol>
 *   <li>{@link #init(HermesConfig)}：由 {@code TerminalTool.register(config)} 调用，
 *       构建策略与确认闸门（此时会话 id 尚未知）；</li>
 *   <li>{@link #setSessionId(String)}：由 {@code Agent} 构造后调用，
 *       用真实会话 id 重建审计器，使审计记录的 session_id 正确。</li>
 * </ol>
 *
 * <p><b>安全默认</b>：未经 {@link #init} 时（例如直接静态调用 {@code checkDangerous} 的测试），
 * {@link #policy()} 惰性构建一个「内置规则 + 默认工作区 + 非审计模式」的策略，
 * 确保任何路径下都有防护，绝不会拿到 null 策略而放行。</p>
 */
public final class SecurityContext {

    private static final Logger logger = LoggerFactory.getLogger(SecurityContext.class);

    private static volatile CommandPolicy policy;
    private static volatile ConfirmationProvider confirmation;
    private static volatile SessionAllowList allowList;
    private static volatile AuditLogger auditLogger;
    private static volatile String sessionId;
    private static volatile boolean yolo;
    private static volatile boolean auditOnly;
    private static volatile boolean auditEnabled;
    private static volatile int auditRetentionDays = AuditLogger.DEFAULT_RETENTION_DAYS;
    private static volatile boolean initialized;

    private SecurityContext() {
    }

    /**
     * 用配置初始化安全上下文。幂等：多次调用以最后一次为准。
     *
     * @param config 运行配置（含 CLI 注入的 yolo / auditPolicy 内存覆盖）
     */
    public static synchronized void init(HermesConfig config) {
        allowList = new SessionAllowList();
        yolo = config != null && config.isYolo();
        auditOnly = config != null && config.isAuditPolicyOnly();

        SecurityRuleSet ruleSet = SecurityRuleSet.load(config);
        policy = new CommandPolicy(ruleSet, auditOnly);

        // 确认闸门：交互模式用 InteractiveConfirmation（走共享 UserInput），
        // 具体是否阻塞由闸门内部的 isInteractive() 判定（非交互自动 DENY）
        confirmation = new InteractiveConfirmation(allowList);

        auditRetentionDays = config != null ? config.getAuditRetentionDays()
                : AuditLogger.DEFAULT_RETENTION_DAYS;
        auditEnabled = config == null || config.isAuditEnabled();
        auditLogger = buildAuditLogger(sessionId);
        initialized = true;
        logger.debug("安全上下文已初始化: yolo={}, auditOnly={}, auditEnabled={}, rules={}",
                yolo, auditOnly, auditEnabled, ruleSet.size());
    }

    /** 按当前开关建审计器；关闭或失败返回 null（调用方 null 安全）。 */
    private static AuditLogger buildAuditLogger(String sessId) {
        if (!auditEnabled) {
            return null;
        }
        try {
            return new AuditLogger(Constants.getHermesHome().resolve("logs"),
                    sessId, auditRetentionDays);
        } catch (RuntimeException e) {
            logger.warn("审计日志器初始化失败，将以降级模式运行（命令仍受策略保护）: {}", e.toString());
            return null;
        }
    }

    /**
     * 设置会话 id 并据此（重）建审计器。由 {@code Agent} 在分配 sessionId 后调用。
     *
     * <p>{@link #init} 先于 sessionId 赋值发生（Agent 构造中 initializeTool 在前），
     * 因此此处用真实会话 id 重建审计器，使记录的 session_id 正确。</p>
     */
    public static synchronized void setSessionId(String id) {
        sessionId = id;
        if (initialized) {
            auditLogger = buildAuditLogger(id);
        }
    }

    /**
     * @return 命令策略；未初始化时惰性构建安全默认（内置规则+默认工作区+非审计）。
     */
    public static CommandPolicy policy() {
        CommandPolicy p = policy;
        if (p == null) {
            synchronized (SecurityContext.class) {
                if (policy == null) {
                    policy = new CommandPolicy(SecurityRuleSet.loadDefaultOnly());
                }
                p = policy;
            }
        }
        return p;
    }

    /** @return 确认闸门；未初始化时惰性用非交互闸门（保守：需确认即拒）。 */
    public static ConfirmationProvider confirmation() {
        ConfirmationProvider c = confirmation;
        if (c == null) {
            synchronized (SecurityContext.class) {
                if (confirmation == null) {
                    confirmation = new ConfirmationProvider.NonInteractive();
                }
                c = confirmation;
            }
        }
        return c;
    }

    /** @return 会话放行表；未初始化时惰性新建空表。 */
    public static SessionAllowList allowList() {
        SessionAllowList a = allowList;
        if (a == null) {
            synchronized (SecurityContext.class) {
                if (allowList == null) {
                    allowList = new SessionAllowList();
                }
                a = allowList;
            }
        }
        return a;
    }

    /** @return 审计日志器；未启用/未初始化时为 null（调用方 null 安全）。 */
    public static AuditLogger auditLogger() {
        return auditLogger;
    }

    /** @return 当前会话 id（可能为 null）。 */
    public static String sessionId() {
        return sessionId;
    }

    /** @return --yolo 是否开启（跳过 WRITE 确认，不跳 DANGEROUS/BLOCKED）；仅内存，不读文件/环境变量。 */
    public static boolean isYolo() {
        return yolo;
    }

    /** @return 是否审计模式（只分类记录、不拦截）；仅内存，不读文件/环境变量。 */
    public static boolean isAuditOnly() {
        return auditOnly;
    }

    /** @return 审计开关是否开启（config.security.audit_enabled，缺省 true）。 */
    public static boolean isAuditEnabled() {
        return auditEnabled;
    }

    /** 测试隔离：重置为未初始化状态，使惰性默认重新生效。 */
    public static synchronized void resetForTest() {
        policy = null;
        confirmation = null;
        allowList = null;
        auditLogger = null;
        sessionId = null;
        yolo = false;
        auditOnly = false;
        initialized = false;
    }
}
