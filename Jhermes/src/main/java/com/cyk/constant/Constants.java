package com.cyk.constant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Shared constants for Hermes Agent Java.
 * Aligned with Python Hermes prompt_builder.py and hermes_constants.py
 */
public final class Constants {

    private static final Logger logger = LoggerFactory.getLogger(Constants.class);

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
     * <p>优先级：{@code HERMES_HOME} 环境变量 &gt; {@code ~/.jhermes}。</p>
     */
    public static Path getHermesHome() {
        String envHome = System.getenv("HERMES_HOME");
        if (envHome != null && !envHome.isEmpty()) {
            return Paths.get(envHome);
        }
        return Paths.get(System.getProperty("user.home")).resolve(DEFAULT_HERMES_HOME);
    }
}
