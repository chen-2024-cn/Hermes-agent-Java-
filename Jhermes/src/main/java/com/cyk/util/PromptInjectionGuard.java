package com.cyk.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h1>提示词注入守卫（Prompt Injection Guard）</h1>
 *
 * <p>提示词注入检测的<b>单一事实源</b>：威胁话术正则库与不可见字符黑名单集中在此，
 * 供两类调用方共享——</p>
 * <ol>
 *   <li><b>记忆写入侧</b>（{@code MemoryManager.scanContent}）— 检出即<b>整条拒绝</b>。
 *       MEMORY.md 每轮都会拼进 System Prompt，一次投毒会话会话生效，属于持久化注入，
 *       是危害最大的路径，必须硬拒</li>
 *   <li><b>外部内容入口</b>（{@code ToolRegistry.dispatch} 对 web/rag 类工具的结果）—
 *       检出后<b>不拒绝，而是加显式安全警示</b>。网页/知识库内容被拉回来本来就是任务目的，
 *       整体拒掉会破坏工具可用性；正确姿势是把内容「标记为不可信数据」，
 *       提醒模型其中可能藏有注入指令（间接提示词注入，indirect prompt injection）</li>
 * </ol>
 *
 * <h2>已知局限（黑名单的天花板）</h2>
 * <ul>
 *   <li>正则只覆盖英文话术，中文注入（"忽略之前的指令"）与语义等价改写（"forget your system
 *       message"）会绕过——业界共识是黑名单挡不住改写，根治方向是 LLM 分类器做语义级检测</li>
 *   <li>不可见字符检查同时兼作「反绕过」：攻击者在敏感词中间插零宽空格使话术正则失效，
 *       但字符检查会先命中</li>
 * </ul>
 */
public final class PromptInjectionGuard {

    /**
     * 威胁模式库 — 防护提示词注入攻击（Prompt Injection）。
     *
     * <p>涵盖常见攻击手法：</p>
     * <ul>
     *   <li>"ignore previous instructions" — 让模型忘记角色设定</li>
     *   <li>"you are now &lt;角色&gt;" — 强制改变模型行为</li>
     *   <li>"do not tell the user" — 教模型隐瞒用户</li>
     *   <li>"system prompt override" — 覆盖系统提示词</li>
     *   <li>"curl/wget + 环境变量" — 试图让模型生成恶意命令窃取敏感信息</li>
     * </ul>
     */
    public static final Pattern[] THREAT_PATTERNS = {
            // 防御：要求模型忽略之前的指令（最常见的注入手法）。
            // 允许修饰词组合（"ignore all above instructions" / "ignore the previous instructions"）：
            // 旧版单修饰词正则被 PromptInjectionGuardTest 抓到漏检双词组合的口子（2026-09-29）
            Pattern.compile("ignore\\s+(the\\s+|any\\s+)?(previous|all|above|prior)"
                    + "(\\s+(all|above|prior|these|those))*\\s+instructions", Pattern.CASE_INSENSITIVE),
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
     * 不可见字符黑名单 — 防御隐写式提示词注入。
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
    public static final Set<Character> INVISIBLE_CHARS = Set.of(
            '\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF',
            '\u202A', '\u202B', '\u202C', '\u202D', '\u202E'
    );

    private PromptInjectionGuard() {
        // 工具类禁止实例化
    }

    /**
     * 对文本做完整注入扫描（不可见字符 + 威胁话术两层，任意一层命中即返回）。
     *
     * @param content 待检测文本，null 视为安全
     * @return null=安全通过；非 null=触发拦截的原因描述（含命中的话术片段，便于日志与提示）
     */
    public static String scan(String content) {
        if (content == null) {
            return null;
        }
        // ① 逐字符检查不可见 Unicode 控制字符（兼作话术正则的反绕过层）
        for (char c : content.toCharArray()) {
            if (INVISIBLE_CHARS.contains(c)) {
                return "Invisible character detected: \\u" + Integer.toHexString(c);
            }
        }
        // ② 威胁话术正则
        for (Pattern pattern : THREAT_PATTERNS) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return "Threat pattern detected: " + matcher.group();
            }
        }
        return null;
    }

    /**
     * {@link #scan(String)} 的布尔形式。
     */
    public static boolean containsInjection(String content) {
        return scan(content) != null;
    }
}
