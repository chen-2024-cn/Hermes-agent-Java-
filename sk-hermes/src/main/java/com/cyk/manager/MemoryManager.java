package com.cyk.manager;

import com.cyk.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <h1>记忆管理器 — Hermes 智能体的持久化记忆系统</h1>
 *
 * <p>核心功能：让 AI 在<b>跨对话</b>中记住用户特征、偏好、项目上下文，而非每次对话都是"失忆"状态。</p>
 *
 * <h2>记忆类型</h2>
 * <ul>
 *   <li><b>memory（通用/项目记忆）</b> — 存储在 ~/.skhermes/memories/MEMORY.md</li>
 *   <li><b>user（用户画像记忆）</b> — 存储在 ~/.skhermes/memories/USER.md</li>
 * </ul>
 *
 * <h2>架构设计</h2>
 * <pre>
 *        ┌─────────────┐
 *        │  外部调用     │
 *        └──────┬───────┘
 *      add/search/delete/replace
 *               │
 *        ┌──────▼───────┐
 *        │  MemoryManager│
 *        │  + 安全扫描    │  ← 注入检测、不可见字符过滤
 *        │  + 内存缓存    │  ← memoryEntries / userEntries
 *        │  + 读写锁      │  ← ReentrantReadWriteLock
 *        │  + 容量淘汰    │  ← FIFO，超出限制删除旧记忆
 *        └──────┬───────┘
 *               │ 持久化
 *        ┌──────▼───────┐
 *        │  .skhermes/   │
 *        │  memories/    │
 *        │  ├─ MEMORY.md │  ← § 分隔的记忆条目
 *        │  └─ USER.md   │
 *        └──────────────┘
 * </pre>
 *
 * <h2>安全防护</h2>
 * <p>两层防御防止提示词注入攻击（Prompt Injection）：</p>
 * <ol>
 *   <li><b>不可见字符黑名单</b> — 检测零宽空格、双向文本控制符等 10 个 Unicode 控制字符</li>
 *   <li><b>威胁正则匹配</b> — 7 条规则拦截 "忽略之前指令"、"你不再是AI助手" 等劫持话术</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * <p>使用 {@link ReentrantReadWriteLock}：</p>
 * <ul>
 *   <li>读操作（search/getByCategory）获取<b>读锁</b>，允许多线程并发读</li>
 *   <li>写操作（add/delete/replace）获取<b>写锁</b>，独占访问，防止脏读</li>
 * </ul>
 */
public class MemoryManager {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);

    // ==================== 常量定义 ====================

    /**
     * 记忆条目分隔符
     * <p>选择 § 符号作为分隔符，因为它在自然语言和代码中几乎不会出现，
     * 能有效避免分隔符与记忆内容冲突</p>
     *
     * <p>文件示例：</p>
     * <pre>
     * 用户喜欢简洁回答，不要用 emoji
     * §
     * 项目使用 Java 17，Spring Boot 3.x
     * §
     * </pre>
     */
    private static final String ENTRY_DELIMITER = "\n§\n";

    /**
     * 通用记忆的字符数上限
     * <p>限制 2200 字符，保证记忆不撑爆 AI 的上下文窗口</p>
     */
    private static final int MEMORY_CHAR_LIMIT = 2200;

    /**
     * 用户记忆的字符数上限
     * <p>限制 1375 字符，比通用记忆少，因为用户画像通常更精简</p>
     */
    private static final int USER_CHAR_LIMIT = 1375;

    // ==================== 文件路径 ====================

    /**
     * 记忆根目录：{hermes_home}/memories/
     */
    private final Path memoriesDir;

    /**
     * 通用记忆文件路径
     */
    private final Path memoryFile;

    /**
     * 用户记忆文件路径
     */
    private final Path userFile;

    //相似度
    private static final double SIMILARITY_THRESHOLD = 0.85; // 85% 相似即判为相似

    // ==================== 并发控制 ====================

    /**
     * 读写锁
     * <ul>
     *   <li>读锁（readLock）：查询操作共享持有，不互斥</li>
     *   <li>写锁（writeLock）：增删改操作独占持有，与读锁互斥</li>
     * </ul>
     * <p>保证了"写在读后可见"和"写与写之间不交错"</p>
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // ==================== 内存缓存 ====================

    /**
     * 通用记忆的内存缓存（对应 MEMORY.md 在内存中的结构）
     * <p>每个元素是一条独立的记忆文本</p>
     */
    private List<String> memoryEntries = new ArrayList<>();

    /**
     * 用户记忆的内存缓存（对应 USER.md 在内存中的结构）
     */
    private List<String> userEntries = new ArrayList<>();

    /**
     * Markdown 渲染后的快照
     * <p>key="memory" → 通用记忆的 Markdown 文本，key="user" → 用户记忆的 Markdown 文本</p>
     * <p>每次加载记忆时重新生成，用于注入系统提示词（System Prompt）</p>
     */
    private final Map<String, String> systemPromptSnapshot = new HashMap<>();

    // ==================== 安全防护 ====================

    /**
     * 威胁模式库 — 防护提示词注入攻击（Prompt Injection）
     *
     * <p>涵盖常见的攻击手法：</p>
     * <ul>
     *   <li>"ignore previous instructions" — 让模型忘记角色设定</li>
     *   <li>"you are now <角色>" — 强制改变模型行为</li>
     *   <li>"do not tell the user" — 教模型隐瞒用户</li>
     *   <li>"system prompt override" — 覆盖系统提示词</li>
     *   <li>"curl/wget + 环境变量" — 试图让模型生成恶意命令窃取敏感信息</li>
     * </ul>
     */
    private static final Pattern[] THREAT_PATTERNS = {
            // 防御：要求模型忽略之前的指令（最常见的注入手法）
            Pattern.compile("ignore\\s+(previous|all|above|prior)\\s+instructions", Pattern.CASE_INSENSITIVE),
            // 防御：强制改变模型身份
            Pattern.compile("you\\s+are\\s+now\\s+", Pattern.CASE_INSENSITIVE),
            // 防御：教模型隐瞒用户
            Pattern.compile("do\\s+not\\s+tell\\s+the\\s+user", Pattern.CASE_INSENSITIVE),
            // 防御：覆盖系统提示词
            Pattern.compile("system\\s+prompt\\s+override", Pattern.CASE_INSENSITIVE),
            // 防御：让模型无视规则
            Pattern.compile("disregard\\s+(your|all|any)\\s+(instructions|rules|guidelines)", Pattern.CASE_INSENSITIVE),
            // 防御：诱导模型生成恶意 curl 命令窃取环境变量中的密钥
            Pattern.compile("curl\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", Pattern.CASE_INSENSITIVE),
            // 防御：同上，针对 wget 命令
            Pattern.compile("wget\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", Pattern.CASE_INSENSITIVE),
    };

    /**
     * 不可见字符黑名单 — 防御隐写式提示词注入
     *
     * <p>这些 Unicode 控制字符肉眼不可见，但能影响文本渲染方向或起分隔作用，常用于：</p>
     * <ul>
     *   <li><b>伪装文件名</b> — 例：\u202E exe.txt → 渲染为 "txt.exe"，诱导用户点击</li>
     *   <li><b>绕过安全检测</b> — 在敏感词中间插入零宽空格，使正则匹配失效</li>
     *   <li><b>欺骗 AI</b> — 用双向控制符反转指令含义</li>
     * </ul>
     *
     * <p>字符说明：</p>
     * <table>
     *   <tr><td>\u200B</td><td>零宽空格    </td><td>肉眼不可见，能绕过关键词检测</td></tr>
     *   <tr><td>\u200C</td><td>零宽非连接符</td><td>同上</td></tr>
     *   <tr><td>\u200D</td><td>零宽连接符  </td><td>同上</td></tr>
     *   <tr><td>\u2060</td><td>词连接符    </td><td>阻止自动换行，可用于隐藏超长内容</td></tr>
     *   <tr><td>\uFEFF</td><td>BOM标记     </td><td>字节序标记，可能干扰解析</td></tr>
     *   <tr><td>\u202A</td><td>左到右嵌入  </td><td>强制文字从左到右排列</td></tr>
     *   <tr><td>\u202B</td><td>右到左嵌入  </td><td>强制文字从右到左排列（阿拉伯语/希伯来语模式）</td></tr>
     *   <tr><td>\u202C</td><td>方向恢复    </td><td>结束嵌入指令</td></tr>
     *   <tr><td>\u202D</td><td>左到右覆盖  </td><td><b>最危险</b> — 强行反转字符显示顺序，制造视觉假象</td></tr>
     *   <tr><td>\u202E</td><td>右到左覆盖  </td><td>同上，反转方向</td></tr>
     * </table>
     */
    private static final Set<Character> INVISIBLE_CHARS = Set.of(
            '\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF',
            '\u202A', '\u202B', '\u202C', '\u202D', '\u202E'
    );

    // ==================== 构造方法 ====================

    /**
     * 初始化记忆管理器
     *
     * <ol>
     *   <li>创建记忆目录 ${hermes_home}/memories（如不存在）</li>
     *   <li>从磁盘加载 MEMORY.md 和 USER.md 到内存缓存</li>
     * </ol>
     */

    /**
     * 用单例模式，防止内存读写不一致，全局用这一个实例
     */
    private static final MemoryManager INSTANCE = new MemoryManager();

    public static MemoryManager getInstance() {
        return INSTANCE;
    }
    private MemoryManager() {
        this.memoriesDir = Constants.getHermesHome().resolve("memories");
        this.memoryFile = memoriesDir.resolve("MEMORY.md");
        this.userFile = memoriesDir.resolve("USER.md");

        // 确保记忆目录存在
        try {
            Files.createDirectories(memoriesDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // 从磁盘加载已有记忆到内存
        loadFromDisk();
    }


    // ==================== 存储初始化 ====================

    /**
     * 从磁盘加载记忆到内存
     *
     * <p>流程：读文件 → 按分隔符拆分 → 去重 → 渲染 Markdown 快照</p>
     * <p>使用写锁防止加载过程中被其他写操作干扰</p>
     */
    private void loadFromDisk() {
        lock.writeLock().lock();
        try {
            // 读取两个 md 文件，按 § 分隔符拆成记忆条目
            memoryEntries = readFile(memoryFile);
            userEntries = readFile(userFile);

            // 去重（LinkedHashSet 保持插入顺序）
            memoryEntries = deduplicate(memoryEntries);
            userEntries = deduplicate(userEntries);

            // 预渲染 Markdown 快照，后续 getSystemPromptSnapshot() 直接拼接返回，避免每次请求都重新渲染
            systemPromptSnapshot.put("memory", renderBlock("memory", memoryEntries));
            systemPromptSnapshot.put("user", renderBlock("user", userEntries));

        } finally {
            lock.writeLock().unlock();
        }
    }


    /**
     * 从磁盘文件读取记忆条目
     *
     * <p>文件格式：多条记忆用 § 分隔，末尾有一个分隔符</p>
     *
     * @param file 记忆文件路径（MEMORY.md 或 USER.md）
     * @return 记忆条目列表；若文件不存在返回空列表
     */
    private List<String> readFile(Path file) {

        try {
            // 文件不存在就返回空列表（首次启动时还没有记忆）
            if (!Files.exists(file)) {
                return new ArrayList<>();
            }

            // 整个文件读入内存
            String content = Files.readString(file, StandardCharsets.UTF_8);

            // 按 § 分隔符切分（limit=-1 保留尾部空串）
            String[] parts = content.split(ENTRY_DELIMITER, -1);

            // 过滤掉空条目和纯空白条目
            return Arrays.stream(parts)
                    .map(String::trim)
                    .filter(part -> !part.isEmpty())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Read file failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }


    /**
     * 记忆去重
     *
     * <p>使用 {@link LinkedHashSet} 实现：</p>
     * <ul>
     *   <li>HashSet 去重（基于 equals/hashCode）</li>
     *   <li>Linked 保持原始插入顺序（后面的相同记忆被丢弃）</li>
     * </ul>
     *
     * @param entries 可能包含重复的记忆列表
     * @return 去重后的记忆列表（保持原有顺序）
     */
    private List<String> deduplicate(List<String> entries) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String entry : entries) {
            seen.add(entry);
        }
        return new ArrayList<>(seen);
    }

    /**
     * 将记忆列表渲染为 Markdown 块
     *
     * <p>渲染结果示例：</p>
     * <pre>
     * ## User Memory
     *
     * - 用户喜欢简洁回答
     * - 项目使用 Java 17
     * </pre>
     *
     * @param category 分类标签，如 "memory"、"user"
     * @param entries  该分类下的记忆条目
     * @return Markdown 格式的文本块；若无记忆则返回空字符串
     */
    private String renderBlock(String category, List<String> entries) {
        if (entries.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(capitalize(category)).append(" Memory\n\n");
        for (String entry : entries) {
            // 记忆内的换行符缩进处理，保持 Markdown 列表项格式
            sb.append("- ").append(entry.replace("\n", "\n  ")).append("\n");
        }
        return sb.toString();
    }

    /**
     * 字符串首字母大写
     *
     * @param s 原字符串，如 "memory"
     * @return 首字母大写的字符串，如 "Memory"
     */
    private String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // ==================== 记忆写入 ====================

    /**
     * 添加一条项目/通用记忆
     *
     * @param content 记忆内容
     * @return true=添加成功，false=被安全检查拦截
     * @see #addEntry(String, String, int)
     */
    public boolean addMemory(String content) {
        return addEntry(content, "memory", MEMORY_CHAR_LIMIT);
    }

    /**
     * 添加一条用户记忆
     *
     * @param content 记忆内容
     * @return true=添加成功，false=被安全检查拦截
     * @see #addEntry(String, String, int)
     */
    public boolean addUser(String content) {
        return addEntry(content, "user", USER_CHAR_LIMIT);
    }

    /**
     * 添加记忆的核心逻辑
     *
     * <p>完整流程（每一步都是顺挂，任一失败则整体失败）：</p>
     * <ol>
     *   <li><b>安全扫描</b> — 检查不可见字符和威胁模式，不通过则直接拒绝</li>
     *   <li><b>相似去重</b> — 删除已有相似记忆，避免重复存储</li>
     *   <li><b>追加</b> — 新记忆添加到列表末尾</li>
     *   <li><b>容量淘汰</b> — 总字符数超限时从最旧的开始删除（FIFO）</li>
     *   <li><b>持久化</b> — 写入对应的 md 文件</li>
     * </ol>
     *
     * @param content   记忆内容
     * @param category  分类："memory" 或 "user"
     * @param charLimit 该分类的字符数上限
     * @return true=成功，false=被安全扫描拦截
     */
    private boolean addEntry(String content, String category, int charLimit) {
        // ① 安全扫描：检测注入攻击
        String scanResult = scanContent(content);
        if (scanResult != null) {
            logger.warn("Memory injection detected: {}", scanResult);
            return false;
        }

        lock.writeLock().lock();
        try {
            // ② 根据分类选数据源
            List<String> entries = "user".equals(category) ? userEntries : memoryEntries;
            Path file = "user".equals(category) ? userFile : memoryFile;

            // ③ 相似去重：删掉与新增内容相似的旧记忆
            entries.removeIf(e -> isSimilar(e, content));

            // ④ 追加到末尾
            entries.add(content);

            // ⑤ 容量淘汰：超出上限就丢掉最旧的记忆
            List<String> pruned = pruneEntries(entries, charLimit);

            // ⑥ 更新内存引用
            if ("user".equals(category)) {
                userEntries = pruned;
            } else {
                memoryEntries = pruned;
            }

            // ⑦ 写入磁盘
            writeFile(file, pruned);

            // ⑧ 刷新快照，使当前会话立即可见
            refreshSnapshot(category);

            return true;
        } finally {
            lock.writeLock().unlock();
        }

    }

    // ==================== 安全扫描 ====================

    /**
     * 对新记忆进行安全扫描
     *
     * <p>两层检测（任意一层命中即拒绝）：</p>
     * <ol>
     *   <li>逐字符检查 — 是否包含不可见 Unicode 控制字符</li>
     *   <li>正则匹配 — 是否包含提示词注入话术</li>
     * </ol>
     *
     * @param content 待检测的记忆内容
     * @return null=安全通过，非null=触发拦截的原因描述
     */
    private String scanContent(String content) {
        // 检测不可见字符
        for (char c : content.toCharArray()) {
            if (INVISIBLE_CHARS.contains(c)) {
                return "Invisible character detected: " + c;
            }
        }

        // 检测威胁模式
        for (Pattern pattern : THREAT_PATTERNS) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return "Threat pattern detected: " + matcher.group();
            }
        }

        return null; // null 表示安全通过
    }


    /**
     * 判断两条记忆是否相似
     *
     * <p>相似判定规则：</p>
     * <ul>
     *   <li>大小写不敏感完全匹配 → 相似</li>
     *   <li>两条都长于 20 字符，且前 20 字符完全相同 → 相似（前缀匹配，认为是同一条的变体）</li>
     *   <li>其他情况 → 不相似</li>
     * </ul>
     *
     * <p>注意：这不是精确去重，而是<b>模糊匹配</b>，防止内容稍有变化就产生重复记忆</p>
     *
     * @param a 已有记忆
     * @param b 新增记忆
     * @return true=相似（会被移除）
     */
    private boolean isSimilar(String a, String b) {
        // 完全匹配（忽略大小写）
        if (a.equalsIgnoreCase(b)) {
            return true;
        }

        // 归一化：去首尾空白、合并多余空格
        String na = a.strip().replaceAll("\\s+", " ");
        String nb = b.strip().replaceAll("\\s+", " ");


        return similarityRatio(na, nb) >= SIMILARITY_THRESHOLD;
    }

    /**
     * 用于比较两条记忆是否相似
     * 归一化相似度 (0.0 ~ 1.0)，1.0 = 完全相同
     */
    private double similarityRatio(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;

        //字符串相似度算法(计算从字符串 A 变为 B 需要多少次单字符编辑（增/删/改）)
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return 1.0 - (double) dp[a.length()][b.length()] / maxLen;
    }

    /**
     * 记忆容量淘汰
     *
     * <p>策略：FIFO（先进先出），从最旧的记忆开始删除，直到总字符数不超限</p>
     * <p>之所以删最旧的而非最长的，是因为<b>时间顺序对记忆系统至关重要</b>——
     * 旧信息往往是早期上下文框架，后续信息已在此基础上更新，可以安全淘汰</p>
     *
     * @param entries   记忆列表（按时间顺序排列，新记忆在末尾）
     * @param charLimit 字符数上限
     * @return 淘汰后的记忆列表（可能为空）
     */
    private List<String> pruneEntries(List<String> entries, int charLimit) {
        int totalChars = entries.stream().mapToInt(String::length).sum();

        // 从头部（最旧）逐个删除，直到满足容量要求
        while (totalChars > charLimit && !entries.isEmpty()) {
            String remove = entries.remove(0); // 移除第一个元素（最旧的记忆）
            totalChars -= remove.length();
        }

        return entries;
    }

    /**
     * 将记忆列表写入磁盘文件
     *
     * <p>格式：每条记忆用 § 包裹，末尾也加 § 保证解析一致性</p>
     *
     * @param file    目标文件
     * @param entries 记忆条目列表
     */
    private void writeFile(Path file, List<String> entries) {

        try {
            // 用 § 连接所有条目
            String content = String.join(ENTRY_DELIMITER, entries);
            // 末尾追加分隔符，保证下次 readFile 时 split 行为一致
            if (!content.isEmpty()) {
                content += ENTRY_DELIMITER;
            }
            // 全量写入磁盘
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 记忆查询 ====================

    /**
     * 搜索记忆
     *
     * <p>对通用记忆和用户记忆<b>同时搜索</b>，返回匹配结果。使用读锁允许并发查询</p>
     *
     * @param query 搜索关键词（大小写不敏感）
     * @param limit 最多返回条数
     * @return 匹配的记忆列表（先通用记忆，后用户记忆，总条数不超过 limit）
     */
    public List<String> search(String query, int limit) {
        lock.readLock().lock();
        try {
            String lowerQuery = query.toLowerCase();

            List<String> results = new ArrayList<>();

            // 先查通用记忆，再查用户记忆
            results.addAll(searchInEntries(memoryEntries, lowerQuery));
            results.addAll(searchInEntries(userEntries, lowerQuery));

            // 截断，取前 limit 条
            return results.stream()
                    .limit(limit)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }

    }

    /**
     * 在指定条目列表中搜索
     *
     * @param entries 记忆条目列表
     * @param query   已转为小写的查询词
     * @return 包含 query 的条目（大小写不敏感匹配）
     */
    private List<String> searchInEntries(List<String> entries, String query) {
        return entries.stream()
                .filter(entry -> entry.toLowerCase().contains(query))
                .collect(Collectors.toList());
    }

    /**
     * 按分类获取记忆（按时间顺序，最新的在后）
     *
     * @param category 分类："memory" 或 "user"
     * @param limit    最多返回条数
     * @return 该分类下的记忆列表
     */
    public List<String> getByCategory(String category, int limit) {
        lock.readLock().lock();
        try {
            List<String> entries = "user".equals(category) ? userEntries : memoryEntries;

            return entries.stream()
                    .limit(limit)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== 记忆修改 ====================

    /**
     * 删除记忆（模糊匹配）
     *
     * <p>找到<b>第一条</b>包含指定内容的记忆并删除</p>
     *
     * @param category 分类："memory" 或 "user"
     * @param content  要删除的内容关键词（包含匹配，并非精确匹配）
     * @return true=找到并删除，false=未匹配到任何记忆
     */
    public boolean delete(String category, String content) {
        lock.writeLock().lock();
        try {
            List<String> entries = "user".equals(category) ? userEntries : memoryEntries;
            Path file = "user".equals(category) ? userFile : memoryFile;

            // 模糊匹配：只要记忆包含了 content 就删除
            boolean removed = entries.removeIf(e -> e.contains(content));

            if (removed) {
                writeFile(file, entries);
                refreshSnapshot(category);
            }
            return removed;
        } finally {
            lock.writeLock().unlock();

        }

    }


    /**
     * 替换记忆
     *
     * <p>找到<b>第一条</b>包含 oldContent 的记忆，替换为 newContent</p>
     * <p>对新内容进行安全扫描。如果只替换内存而不持久化，则不做任何操作</p>
     *
     * @param category   分类："memory" 或 "user"
     * @param oldContent 旧内容的匹配关键词
     * @param newContent 新内容
     * @return true=找到并替换成功，false=未找到或安全检查未通过
     */
    public boolean replace(String category, String oldContent, String newContent) {
        lock.writeLock().lock();
        try {
            // 新内容也要过安全扫描
            String scanResult = scanContent(newContent);
            if (scanResult != null) {
                logger.warn("Memory injection detected: {}", scanResult);
                return false;
            }

            List<String> entries = "user".equals(category) ? userEntries : memoryEntries;
            Path file = "user".equals(category) ? userFile : memoryFile;

            boolean replaced = false;

            // 遍历找第一条匹配的记忆
            for (int i = 0; i < entries.size(); i++) {
                String entry = entries.get(i);
                if (entry.contains(oldContent)) {
                    entries.set(i, newContent);
                    replaced = true;
                    break; // 只替换第一条匹配项
                }
            }

            if (replaced) {
                writeFile(file, entries);
                refreshSnapshot(category);
            }

            return replaced;

        } finally {
            lock.writeLock().unlock();
        }


    }

    /**
     * 刷新指定分类的快照缓存
     *
     * <p>在 addEntry/delete/replace 后调用，确保当前会话中 LLM
     * 能立即看到最新的记忆内容，而非等到下次重启。</p>
     *
     * @param category 分类："memory" 或 "user"
     */
    private void refreshSnapshot(String category) {
        List<String> entries = "user".equals(category) ? userEntries : memoryEntries;
        systemPromptSnapshot.put(category, renderBlock(category, entries));
    }

    /**
     * 获取系统提示词快照
     *
     * <p>将通用记忆和用户记忆拼接为一段 Markdown 文本，直接注入到发给 AI 的 System Prompt 中。</p>
     * <p>AI 会在每次对话开始时看到这段文本，从而"知道"之前的记忆。</p>
     *
     * <p>快照在 {@link #loadFromDisk()} 中预渲染，此处仅做拼接，性能开销极低。</p>
     *
     * @return Markdown 格式的快照文本；若无任何记忆则返回空字符串
     */
    public String getSystemPromptSnapshot() {
        StringBuilder sb = new StringBuilder();
        String memoryBlock = systemPromptSnapshot.get("memory");
        if (memoryBlock != null) {
            sb.append(memoryBlock);
        }
        String userBlock = systemPromptSnapshot.get("user");
        if (userBlock != null) {
            sb.append(userBlock);
        }

        return sb.toString();

    }

}