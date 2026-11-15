package com.cyk.command.slash;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <h1>一条斜杠命令的声明（名称 + 别名 + 一句话用途 + 执行体）</h1>
 *
 * <p>REPL 内命令的单一声明单元。改造前 {@code Agent.run()} 里是散落的
 * {@code if (trimmed.equalsIgnoreCase("exit")) break;} / {@code if (...equals("/retry"))}——
 * 每加一条命令就要在 60 行的主循环里再插一个分支，且「有哪些命令」这件事
 * 没有任何一处能完整回答（{@code /help} 因此无从写起，只能靠文档，文档必然过期）。</p>
 *
 * <p>声明式后：新增命令 = 往 {@link SlashCommandRegistry} 注册一条，
 * {@code /help} 自动列出、主循环一行不改（P0-4 的 {@code /context}、
 * P1-3 的 {@code /model}{@code /config} 都是纯追加）。</p>
 *
 * @param name        主命令名，大小写无关匹配；<b>以 {@code /} 开头为斜杠命令</b>
 *                    （如 {@code /help}），不带斜杠为裸词命令（如 {@code exit}，
 *                    兼容「直接敲 exit 退出」的既有直觉）
 * @param aliases     别名（如 {@code /?} 之于 {@code /help}）；不可变，可为空
 * @param description 一句话用途，{@code /help} 命令表直接渲染此文本——
 *                    描述与命令同处一条声明，避免两处漂移
 * @param handler     执行体
 */
public record SlashCommand(String name, List<String> aliases, String description, Handler handler) {

    /**
     * 命令执行体。
     *
     * @param ctx      执行上下文（输出通道、能力实况、重试动作）
     * @param argument 命令名之后的剩余文本（已 trim；无参数时为空串）——
     *                 为 {@code /model fast} 这类带参命令预留，不必等到 P1-3 再改接口
     * @return 分发结果；handler 不负责打印「已处理」之类的收尾，输出即其职责全部
     */
    @FunctionalInterface
    public interface Handler {
        CommandOutcome handle(SlashCommandContext ctx, String argument);
    }

    public SlashCommand {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("命令名不能为空");
        }
        java.util.Objects.requireNonNull(handler, "命令 " + name + " 缺少执行体");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        description = description == null ? "" : description;
        name = name.trim();
    }

    /** 无别名的便利工厂。 */
    public static SlashCommand of(String name, String description, Handler handler) {
        return new SlashCommand(name, List.of(), description, handler);
    }

    /**
     * 候选输入是否命中本命令（主名或任一别名，大小写无关，忽略首尾空白）。
     *
     * @param candidate 用户输入的命令词（不含参数）
     */
    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String alias : aliases) {
            if (normalized.equals(alias.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 调用形式清单（主名 + 别名），{@code /help} 用它渲染命令名列。 */
    public List<String> invocationNames() {
        List<String> names = new ArrayList<>(aliases.size() + 1);
        names.add(name);
        names.addAll(aliases);
        return List.copyOf(names);
    }
}
