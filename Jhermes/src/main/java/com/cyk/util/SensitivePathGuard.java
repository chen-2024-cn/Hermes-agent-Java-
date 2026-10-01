package com.cyk.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * <h1>敏感凭据路径守卫（工具层共享黑名单）</h1>
 *
 * <p>Agent 的多个工具都能触达本机文件系统：{@code run_command}（终端）、
 * {@code read_file}/{@code write_file}/{@code grep_files}/{@code search_files}（文件）。
 * 只要<b>任意一个</b>入口能读到凭据文件，密钥就会明文进入对话上下文，
 * 并随 session json / trajectory JSONL 持久化落盘——这是不可逆的泄露。</p>
 *
 * <p>因此黑名单<b>必须共享同一份</b>，而不是每个工具各写一遍：
 * 各写一份的典型后果是「终端拦了、文件工具没拦」这类防护不对齐的真实口子
 * （本项目 2026-09-29 之前正是这个状态）。抽成单一工具类后，
 * 新增一条凭据特征只需改一处，所有工具同步生效。</p>
 *
 * <h2>威胁来源</h2>
 * <p>真实事故驱动（2026-09-28）：模型在搜索“知识库在哪”时随手
 * {@code Get-Content ~/.ai_tools/.meta.json}，把 Feishu auth token 明文打进了对话历史。
 * 轻量模型没有“哪些文件是凭据”的先验知识，提示词层约束不可靠，
 * 所以必须在工具入口<b>硬拦截</b>：模型的正常任务几乎永远不需要读这些文件。</p>
 *
 * <h2>匹配规则</h2>
 * <ul>
 *   <li>大小写无关（统一 {@link Locale#ROOT} 小写化，Windows 路径大小写不敏感）</li>
 *   <li>子串匹配 + <b>右边界校验</b>：命中标记后必须紧跟非字母数字字符或到串尾，
 *       避免 {@code .sshare} 这类名字里含 {@code .ssh} 字样的正常目录被误伤</li>
 *   <li>Windows 反斜杠与 Unix 正斜杠两种写法都要登记，
 *       因为终端命令串里两种都可能出现，而 {@link Path#toString()} 在 Windows 上固定是反斜杠</li>
 * </ul>
 */
public final class SensitivePathGuard {

    /**
     * 敏感凭据文件/目录特征（小写形式）。
     *
     * <p>⚠️ 修改本列表即同时改变终端工具与文件工具的安全边界，
     * 新增特征务必同步补 {@code SensitivePathGuardTest} 用例。</p>
     */
    public static final List<String> MARKERS = List.of(
        ".ai_tools",                  // 本机 AI 工具凭据目录（.meta.json 含 auth token）
        ".ssh",                       // SSH 密钥与 known_hosts
        ".gnupg",                      // GPG 密钥环
        ".aws\\credentials", ".aws/credentials",
        ".kube\\config", ".kube/config",
        ".docker\\config.json", ".docker/config.json",
        ".netrc", "_netrc",
        ".git-credentials",            // git 明文凭据
        "id_rsa", "id_ed25519",
        ".npmrc",                      // npm 可能含 _authToken
        ".pypirc",
        // Jhermes 数据目录里的 config.yaml 含模型 api_key 与 PG 口令，读取即等于泄密。
        // 新旧目录名必须同时拦截：Constants.migrateLegacyData 迁移失败/两目录并存时，
        // 凭据仍在旧的 .skhermes 下，只拦新名会留下真实可利用的泄露口子。
        ".jhermes\\config.yaml", ".jhermes/config.yaml",
        ".skhermes\\config.yaml", ".skhermes/config.yaml"
    );

    private SensitivePathGuard() {
        // 工具类禁止实例化
    }

    /**
     * 检查任意文本（终端命令串或文件路径）是否触碰敏感凭据特征。
     *
     * @param text 待检测文本，null/空串视为安全
     * @return 命中的敏感标记；未命中返回 null
     */
    public static String check(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            int idx = normalized.indexOf(marker);
            while (idx >= 0) {
                int after = idx + marker.length();
                if (after >= normalized.length()
                        || !Character.isLetterOrDigit(normalized.charAt(after))) {
                    return marker;
                }
                idx = normalized.indexOf(marker, idx + 1);
            }
        }
        return null;
    }

    /**
     * {@link #check(String)} 的布尔形式，便于 {@code if} 内直接使用。
     */
    public static boolean isSensitive(String text) {
        return check(text) != null;
    }

    /**
     * 检查 {@link Path}（调用方应已 {@code toAbsolutePath().normalize()}）。
     *
     * @return 命中的敏感标记；null 表示安全（path 为 null 也视为安全）
     */
    public static String checkPath(Path path) {
        return path == null ? null : check(path.toString());
    }

    /**
     * {@link #checkPath(Path)} 的布尔形式。目录与文件同样适用——
     * 目录遍历场景可用它决定是否 {@code SKIP_SUBTREE}，
     * 避免把凭据目录整个走进去看文件名（文件名本身也可能泄露信息）。
     */
    public static boolean isSensitivePath(Path path) {
        return checkPath(path) != null;
    }
}
