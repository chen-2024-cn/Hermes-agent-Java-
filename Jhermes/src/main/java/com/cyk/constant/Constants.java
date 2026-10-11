package com.cyk.constant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
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
     * 程序版本号（JH-SPEC-001 P0-1 R2：单一事实源 = pom {@code <version>}）。
     *
     * <p>构建期 Maven resource filtering 把 pom 版本写入 classpath 根下的
     * {@code version.properties}，本静态字段在类加载时读取该文件。</p>
     *
     * <p>{@link com.cyk.HermesAgent} 的 picocli {@code versionProvider} 与
     * 启动横幅的版本标语均引用此值，彻底消除「Constants / pom / 发布 tag
     * 三处各写一份」的版本漂移（历史上曾出现 1.0-SNAPSHOT vs 2.1.0）。</p>
     *
     * <p>注意：不再是编译期常量，因此 picocli 注解里不能直接引用
     * （注解属性要求 compile-time constant），改用 {@code IVersionProvider}。</p>
     */
    public static final String VERSION = loadBuildVersion();

    /** 读取不到构建产物 version.properties 时的兜底值（仅 IDE 直跑未经 Maven 构建的场景）。 */
    private static final String VERSION_FALLBACK = "dev";

    /**
     * 从 classpath 的 {@code version.properties} 加载构建期注入的版本号。
     *
     * <p>静默降级：读取失败（文件缺失、IO 异常、键缺失）一律返回
     * {@link #VERSION_FALLBACK} 并记 WARN，绝不让版本号问题阻断启动。</p>
     */
    private static String loadBuildVersion() {
        try (InputStream in = Constants.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String version = props.getProperty("version");
                if (version != null && !version.isBlank()) {
                    return version.trim();
                }
            }
        } catch (IOException e) {
            logger.warn("读取 version.properties 失败，使用兜底版本号: {}", e.getMessage());
        }
        logger.warn("classpath 缺少 version.properties（未经 Maven 构建？），版本号降级为 {}", VERSION_FALLBACK);
        return VERSION_FALLBACK;
    }

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

    /**
     * 技能脚本执行超时（秒）缺省值（JH-SPEC-001 P0-5 R10）。
     *
     * <p>改造前 {@code SkillTool} 执行脚本完全无超时：一个挂死的技能脚本会把整个
     * 会话永久阻塞在此处（且无进程树清理）。置于 Constants 作为单一缺省值来源，
     * 由 {@code HermesConfig} 与 {@code SkillTool} 共同引用，避免两边各写一份 60。</p>
     */
    public static final int DEFAULT_SKILL_SCRIPT_TIMEOUT_SECONDS = 60;

    // =========================================================================
    // 系统提示词（P0-3 已全部外置，本类不再持有任何长文本提示词）
    // =========================================================================
    // 身份、工作目录模板、执行规范、终端输出规范，以及记忆/终端/网页搜索/
    // 知识库/技能各工具指引与平台提示，已全部迁至 resources/prompts/*.md，
    // 段序与注入条件由同目录 manifest.yaml 单点声明，
    // 运行期由 com.cyk.prompt.PromptLoader / PromptComposer 装配。
    // 迁移的逐字节保真已由 PromptLoaderTest 在期 2 验证后锁档。
    // 本类自此只保留「真常量」：版本、目录名、文件名、CLI 名、超时缺省值
    //（AC5 有静态扫描与反射双重断言把守，防止长文本回流）。

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
