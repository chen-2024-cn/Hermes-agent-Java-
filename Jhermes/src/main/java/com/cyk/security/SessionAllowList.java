package com.cyk.security;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>会话内「同类放行」表（JH-SPEC-001 P0-2 R7）</h1>
 *
 * <p>用户在 WRITE 确认时选 {@code [a]} 后，本会话内后续「同类」命令不再逐条打扰。
 * R7 对「同类」有严格定义，本类据此实现：</p>
 *
 * <ul>
 *   <li><b>同一可执行名 + 同一子命令 + 工作区内</b>——归组键由
 *       {@link PolicyDecision#allowGroupKey()} 给出（形如 {@code git|push|in}）；</li>
 *   <li><b>仅对 WRITE 生效</b>：DANGEROUS 每次都强确认、BLOCKED 永不放行，
 *       都不接受会话放行（{@link PolicyDecision#allowsSessionReuse()} 已把这两档排除）；</li>
 *   <li><b>仅存内存，进程退出即失效</b>：不持久化——否则一次误放 {@code [a]}
 *       会永久降低此后所有会话的防护；</li>
 *   <li><b>脚本哈希变化后不得沿用</b>（R14）：技能脚本的归组键携带脚本哈希，
 *       脚本被改写即键变、放行失效，防止「先放行干净脚本，再篡改成恶意脚本」。</li>
 * </ul>
 *
 * <p>线程安全：工具调用虽在回合循环内串行，但用 {@code ConcurrentHashMap} 兜底，
 * 与项目其余共享状态（如 TerminalTool 的重复计数表）保持一致的并发假设。</p>
 */
final class SessionAllowList {

    private final Set<String> allowedGroups = ConcurrentHashMap.newKeySet();

    /**
     * 放行某个归组键（用户在 WRITE 确认时选 {@code [a]}）。
     *
     * @param groupKey 归组键；null/空忽略
     */
    void allow(String groupKey) {
        if (groupKey != null && !groupKey.isEmpty()) {
            allowedGroups.add(groupKey);
        }
    }

    /** 归组键是否已被本会话放行。 */
    boolean isAllowed(String groupKey) {
        return groupKey != null && allowedGroups.contains(groupKey);
    }

    /** 清空（测试隔离用；进程正常生命周期内不清空）。 */
    void clear() {
        allowedGroups.clear();
    }

    /** @return 当前已放行的归组数（供审计/自检展示）。 */
    int size() {
        return allowedGroups.size();
    }
}
