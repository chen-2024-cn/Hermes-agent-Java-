package com.cyk.security;

/**
 * <h1>命令风险分级（JH-SPEC-001 P0-2）</h1>
 *
 * <p>四档的设计理由（规范 §P0-2「分级判据」）：</p>
 *
 * <table border="1">
 *   <caption>级别与处置</caption>
 *   <tr><th>READ_ONLY</th><td>显式白名单内、带参数约束的只读命令 → 自动放行</td></tr>
 *   <tr><th>WRITE</th><td>修改文件或状态、范围在工作区内，<b>或属未知命令</b> → y/n/a 确认</td></tr>
 *   <tr><th>DANGEROUS</th><td>越出工作区、历史改写、提权、下载并执行、外发本地数据
 *       → 展示命令与理由并要求键入 {@code yes}；非交互一律拒绝</td></tr>
 *   <tr><th>BLOCKED</th><td>系统级灾难命令 → <b>任何标志都不可绕过，永不执行</b></td></tr>
 * </table>
 *
 * <p>BLOCKED 是 v2 规范新增的一档：v1 的三级分类若直接落地，会把现有实现的
 * 「灾难命令硬拦截」降级为「可确认」——这是安全性的倒退（规范更正项 C-02）。</p>
 *
 * <p>{@code severity} 用于「升档」运算（P0-2 R9：工作区边界命中则升一档），
 * 比较时必须用 severity 而非 {@code compareTo}，因为枚举声明顺序虽一致，
 * 但用数值语义更明确、也不受将来插入新档影响。</p>
 */
public enum CommandLevel {

    /** 只读、无副作用（必须来自显式白名单，未知命令绝不落此档）。 */
    READ_ONLY(0),

    /** 有写入/状态变更，范围可控或未知命令（保守默认档）。 */
    WRITE(1),

    /** 高危：不可逆、越权或可外发数据，需强确认。 */
    DANGEROUS(2),

    /** 系统级灾难：永不执行，不可绕过（含 --yolo）。 */
    BLOCKED(3);

    private final int severity;

    CommandLevel(int severity) {
        this.severity = severity;
    }

    /** @return 数值化严重度，越大越危险；用于升档与取最严决策 */
    public int severity() {
        return severity;
    }

    /** @return 是否需要用户确认（WRITE 与 DANGEROUS） */
    public boolean requiresConfirmation() {
        return this == WRITE || this == DANGEROUS;
    }

    /** @return 是否需要「键入 yes」级别的强确认 */
    public boolean requiresStrongConfirmation() {
        return this == DANGEROUS;
    }

    /**
     * 升一档（R9 工作区边界命中时使用）。
     *
     * <p>BLOCKED 已是最高档，升档后仍为 BLOCKED——不允许出现「越界反而解锁」的荒谬结果。</p>
     */
    public CommandLevel escalate() {
        return switch (this) {
            case READ_ONLY -> WRITE;
            case WRITE -> DANGEROUS;
            case DANGEROUS, BLOCKED -> this;
        };
    }

    /** 取两者中更严的一档（多分段命令的整体决策）。 */
    public static CommandLevel max(CommandLevel a, CommandLevel b) {
        return a.severity() >= b.severity() ? a : b;
    }

    /**
     * 容错解析级别名（用于加载外置规则表）。
     *
     * @param text 级别名，大小写与空白无关
     * @return 对应级别；无法识别返回 null（调用方决定回退档位，绝不默认 READ_ONLY）
     */
    public static CommandLevel parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (CommandLevel level : values()) {
            if (level.name().equalsIgnoreCase(text.trim())) {
                return level;
            }
        }
        return null;
    }
}
