package com.cyk.security;

/**
 * <h1>命令确认闸门（JH-SPEC-001 P0-2 R7）</h1>
 *
 * <p>把「命令被分级后到底放不放行」的交互决策抽象出来，使：①交互实现与非交互实现可替换；
 * ②{@code TerminalTool} 不必直接读 {@code System.in}；③单测可注入桩验证 y/n/a/yes 四条路径。</p>
 *
 * <h2>三级处置（R7）</h2>
 * <table border="1">
 *   <tr><th>READ_ONLY</th><td>不询问，直接放行</td></tr>
 *   <tr><th>WRITE</th><td>{@code [y] 执行 / [n] 拒绝 / [a] 本会话内同类放行}</td></tr>
 *   <tr><th>DANGEROUS</th><td>展示完整命令与理由，要求键入 {@code yes}</td></tr>
 *   <tr><th>BLOCKED</th><td>不进闸门——由调用方直接拒绝（任何标志不可绕过）</td></tr>
 * </table>
 *
 * <p>非交互模式（管道、CI）下任何需要确认的操作一律 DENY，绝不阻塞等待输入
 * （P1-2 R4，也是 {@link #request} 契约的一部分）。</p>
 */
public interface ConfirmationProvider {

    /** 闸门裁决结果。 */
    enum Approval {
        /** 用户批准执行（或 READ_ONLY 自动放行、--yolo 跳过 WRITE、会话内已放行同类）。 */
        ALLOW,
        /** 用户拒绝，或非交互模式下需要确认。 */
        DENY
    }

    /**
     * 对一条已分级命令请求放行裁决。
     *
     * @param decision 分级结果（BLOCKED 不应传入本方法，调用方需先行硬拦）
     * @return 裁决；实现<b>不得阻塞</b>于非交互场景
     */
    Approval request(PolicyDecision decision);

    /**
     * 非交互闸门：任何需要确认的命令一律拒绝（PIPE/CI 场景，P1-2 R4）。
     *
     * <p>READ_ONLY 仍放行（无副作用），WRITE/DANGEROUS 一律 DENY——
     * 宁可让模型收到「非交互模式无法确认，已拒绝」的 error，也绝不阻塞进程。</p>
     */
    final class NonInteractive implements ConfirmationProvider {
        @Override
        public Approval request(PolicyDecision decision) {
            return decision.isAutoAllowed() ? Approval.ALLOW : Approval.DENY;
        }
    }
}
