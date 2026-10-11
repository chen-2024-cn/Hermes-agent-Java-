package com.cyk.prompt;

import com.cyk.constant.Constants;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <h1>提示词分段加载器（JH-SPEC-001 P0-3 R1）</h1>
 *
 * <p>从 classpath {@code /prompts/} 读取 manifest 与各段 md 文件，产出
 * <b>有序</b>的 {@link PromptSegment} 列表（顺序 = manifest 声明顺序 = 提示词前缀顺序，
 * P0-4 R8 前缀缓存依赖此稳定性）。</p>
 *
 * <h2>用户级覆盖（决策 D-3）</h2>
 * <p>与 {@code security/rules.yaml} 覆盖同一模式：{@code ~/.jhermes/prompts/} 下的
 * 同名文件（含 manifest.yaml 本身）优先于 classpath 内置版——用户不改代码即可调提示词。
 * 覆盖文件不可读 / manifest YAML 损坏 → WARN 并回退 classpath 版本。
 * 该目录已纳入 {@code SensitivePathGuard} 写保护，模型的任何工具都触达不了它，
 * 覆盖通道只有用户手工编辑，不存在间接注入面。</p>
 *
 * <h2>容错铁律：绝不因提示词问题阻断启动</h2>
 * <ul>
 *   <li>manifest 缺失/损坏（连 classpath 版都读不到，属打包事故）→ ERROR + 返回空列表，
 *       composer 将装配出最小提示词，会话仍可继续；</li>
 *   <li>单个段文件缺失/不可读 → WARN + 跳过该段，其余段照常；</li>
 *   <li>manifest 条目非法（id/file 空白、id 重复）→ WARN + 跳过该条目。</li>
 * </ul>
 *
 * <h2>缓存</h2>
 * <p>{@code buildSystemPrompt} 每个用户回合都会触发装配，而分段内容在进程生命周期内
 * 不变——首次 {@link #load()} 后缓存结果，后续调用零 IO。测试/运行期需要换内容时
 * 新建实例即可（实例极轻）。</p>
 */
public final class PromptLoader {

    private static final Logger logger = LoggerFactory.getLogger(PromptLoader.class);

    /** classpath 下的提示词目录名（src/main/resources/prompts/）。 */
    static final String PROMPTS_DIR = "prompts";

    /** 装配清单文件名（classpath 与用户覆盖目录同名）。 */
    static final String MANIFEST_FILE = "manifest.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            // 用户手改 manifest 多写了未知键不该让加载失败：宽容解析
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 用户级覆盖目录（可为 null = 禁用覆盖，测试注入用）。 */
    private final Path userOverrideDir;

    /** 首次加载后的不可变缓存（volatile：Agent 单线程使用，此为保证可见性的最简手段）。 */
    private volatile List<PromptSegment> cached;

    /** 生产入口：覆盖目录固定为 {@code ~/.jhermes/prompts}。 */
    public PromptLoader() {
        this(defaultUserOverrideDir());
    }

    /**
     * 测试/定制入口：显式指定覆盖目录。
     *
     * @param userOverrideDir 用户覆盖目录；null 表示禁用覆盖（纯 classpath）
     */
    public PromptLoader(Path userOverrideDir) {
        this.userOverrideDir = userOverrideDir;
    }

    /** 默认用户覆盖目录（不触发目录创建——不存在即视为「用户未定制」）。 */
    public static Path defaultUserOverrideDir() {
        return Constants.getHermesHome().resolve(PROMPTS_DIR);
    }

    /**
     * 加载全部提示词分段（带缓存）。
     *
     * @return 有序分段列表（不可变）；manifest 不可用时返回空列表而非抛异常
     */
    public List<PromptSegment> load() {
        List<PromptSegment> local = cached;
        if (local == null) {
            local = doLoad();
            cached = local;
        }
        return local;
    }

    private List<PromptSegment> doLoad() {
        // manifest 两级回退（D-3 容错铁律）：用户覆盖版损坏/解析失败 → 回退 classpath
        // 内置版；内置版也缺失（打包事故）→ ERROR + 空清单，会话降级但不瘫瘓。
        List<ManifestSegment> entries = List.of();
        String userManifest = readUserOverride(MANIFEST_FILE);
        if (userManifest != null) {
            entries = parseManifest(userManifest, true);
        }
        if (entries.isEmpty()) {
            String builtinManifest = readClasspath(MANIFEST_FILE);
            if (builtinManifest == null) {
                logger.error("提示词清单 {}/{} 缺失且无可用的用户覆盖，系统提示词将为空——请检查打包",
                        PROMPTS_DIR, MANIFEST_FILE);
                return List.of();
            }
            entries = parseManifest(builtinManifest, false);
        }
        if (entries.isEmpty()) {
            return List.of();
        }

        List<PromptSegment> segments = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (ManifestSegment entry : entries) {
            if (entry.id() == null || entry.id().isBlank()
                    || entry.file() == null || entry.file().isBlank()) {
                logger.warn("manifest 条目缺少 id/file，已跳过：{}", entry);
                continue;
            }
            if (!seenIds.add(entry.id())) {
                logger.warn("manifest 段 id '{}' 重复，仅首个生效", entry.id());
                continue;
            }
            String content = readWithOverride(entry.file());
            if (content == null) {
                logger.warn("提示词段 {}（文件 {}）缺失或不可读，已跳过该段——其余段照常装配",
                        entry.id(), entry.file());
                continue;
            }
            segments.add(new PromptSegment(entry.id(), entry.file(),
                    normalize(content), toRequirements(entry.requires())));
        }
        logger.debug("提示词加载完成：{} 段（manifest 声明 {} 条）{}，覆盖目录={}",
                segments.size(), entries.size(),
                segments.size() < entries.size() ? "，有段被跳过" : "",
                userOverrideDir);
        return List.copyOf(segments);
    }

    // =========================================================================
    // 读取：用户覆盖优先，classpath 兜底
    // =========================================================================

    /**
     * 按「用户覆盖 → classpath」次序读取一个提示词文件。
     *
     * <p>文件名只取最后一段（{@code getFileName()}）：manifest 若被用户改出
     * {@code file: ../../x.md} 这类路径穿越写法，也只在覆盖目录内找同名文件，
     * 不会读到目录外内容。</p>
     *
     * @return 文件文本；两处都不存在或都不可读时返回 null
     */
    private String readWithOverride(String fileName) {
        String safeName = Path.of(fileName).getFileName().toString();
        String fromUser = readUserOverride(safeName);
        if (fromUser != null) {
            return fromUser;
        }
        return readClasspath(safeName);
    }

    private String readUserOverride(String fileName) {
        if (userOverrideDir == null) {
            return null;
        }
        Path file = userOverrideDir.resolve(fileName);
        try {
            if (Files.isRegularFile(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                logger.info("提示词文件 {} 使用用户覆盖版本：{}", fileName, file);
                return text;
            }
        } catch (IOException | RuntimeException e) {
            // 覆盖文件存在但坏了（编码异常、无权限）：回退内置版，不让定制失败拖垮启动
            logger.warn("用户覆盖提示词文件 {} 不可读（{}），回退 classpath 内置版", file, e.getMessage());
        }
        return null;
    }

    private String readClasspath(String fileName) {
        String resource = "/" + PROMPTS_DIR + "/" + fileName;
        try (InputStream in = PromptLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("classpath 提示词文件 {} 读取失败：{}", resource, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // 文本规范化与 requires 转换
    // =========================================================================

    /**
     * 段正文规范化：剥 UTF-8 BOM + 行尾统一为 LF + 去首尾空白。
     *
     * <p><b>为什么必须统一行尾</b>：classpath 段文件由 Windows 工具链写出、用户覆盖文件
     * 由任意编辑器保存、git {@code core.autocrlf} 还会在 checkout 时改写行尾——
     * 若不归一，同一段提示词在不同环境的字节不同，\r 混进模型请求体既白白
     * 污染 token 计数，又破坏前缀缓存的字节稳定性（P0-4 R8）。孤立 CR（老 Mac 风格）
     * 一并转 LF，行为确定化。</p>
     *
     * <p>段间分隔（\n\n）由 composer 统一负责，段文件自身不带尾随空行。</p>
     */
    static String normalize(String text) {
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        return text.strip();
    }

    /** manifest 的 requires 节点 → 不可变 {@link PromptRequirements}；null/空白项滤除。 */
    private static PromptRequirements toRequirements(RequiresDto dto) {
        if (dto == null) {
            return PromptRequirements.ALWAYS;
        }
        return new PromptRequirements(
                sanitize(dto.toolsets),
                sanitize(dto.tools),
                dto.skills);
    }

    /** 滤除 null/空白并 trim（用户手写 YAML 常见 {@code [web, ]} 产生空元素）。 */
    private static List<String> sanitize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(raw.size());
        for (String item : raw) {
            if (item != null && !item.isBlank()) {
                cleaned.add(item.trim());
            }
        }
        return cleaned;
    }

    // =========================================================================
    // manifest 解析（Jackson YAML → DTO）
    // =========================================================================

    /**
     * 解析 manifest YAML。
     *
     * @param yamlText manifest 文本
     * @param userCopy 是否来自用户覆盖版——决定失败时的日志级别与话术
     *                 （用户版损坏会回退内置版，属 WARN；内置版损坏无处可退，属 ERROR）
     * @return 解析出的段声明条目；损坏/无 segments 时返回空列表（不抛异常）
     */
    private List<ManifestSegment> parseManifest(String yamlText, boolean userCopy) {
        try {
            ManifestDto manifest = YAML.readValue(yamlText, ManifestDto.class);
            if (manifest == null || manifest.segments == null || manifest.segments.isEmpty()) {
                if (userCopy) {
                    logger.warn("用户覆盖的提示词 manifest 未声明任何 segments，回退内置清单");
                } else {
                    logger.error("内置提示词 manifest 未声明任何 segments，系统提示词将为空");
                }
                return List.of();
            }
            List<ManifestSegment> entries = new ArrayList<>(manifest.segments.size());
            for (SegmentDto dto : manifest.segments) {
                if (dto == null) {
                    continue;
                }
                entries.add(new ManifestSegment(dto.id, dto.file, dto.requires));
            }
            return entries;
        } catch (IOException | RuntimeException e) {
            if (userCopy) {
                logger.warn("用户覆盖的提示词 manifest 解析失败（{}），回退 classpath 内置版", e.getMessage());
            } else {
                logger.error("内置提示词 manifest 解析失败，系统提示词将为空：{}", e.getMessage());
            }
            return List.of();
        }
    }

    /** manifest 顶层结构（version 目前仅存档，供未来不兼容升级时判别格式）。 */
    static final class ManifestDto {
        public int version;
        public List<SegmentDto> segments;
    }

    /** manifest 单个段声明。 */
    static final class SegmentDto {
        public String id;
        public String file;
        public RequiresDto requires;
    }

    /** requires 子结构：三类判据（工具集 AND / 工具名 AND / 技能非空）。 */
    static final class RequiresDto {
        public List<String> toolsets;
        public List<String> tools;
        public boolean skills;
    }

    /** 解析后的 manifest 条目（内部传递用）。 */
    private record ManifestSegment(String id, String file, RequiresDto requires) {
    }
}
