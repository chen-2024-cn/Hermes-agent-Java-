package com.cyk.command.slash;

import com.cyk.capability.CapabilityRegistry;
import com.cyk.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <h1>斜杠命令注册表（JH-SPEC-001 P0-3 R4，决策 D-4）</h1>
 *
 * <h2>解决的问题</h2>
 * <p>改造前 REPL 的命令逻辑是 {@code Agent.run()} 主循环里的散落 {@code if}：
 * 加一条命令就要改主循环，且没有任何一处能回答「当前有哪些命令」——
 * 这正是 {@code /help} 无法实现的原因（能力与命令清单散落在代码里，写死在帮助文本
 * 必然与实现漂移）。</p>
 *
 * <p>收口为注册表后，三件事同时成立：</p>
 * <ol>
 *   <li><b>新增命令 = 追加注册</b>：P0-4 的 {@code /context}、P1-3 的
 *       {@code /model}{@code /config} 一行不改主循环；</li>
 *   <li><b>{@code /help} 自动完整</b>：命令表由 {@link #commands()} 渲染，
 *       工具表由 {@link CapabilityRegistry#asSummary()} 渲染——两者都取自<b>注册实况</b>，
 *       帮助文本与真实能力不可能漂移（AC3 的构造保证，{@code HelpConsistencyTest} 从外部验证）；</li>
 *   <li><b>解析规则单点</b>：斜杠/裸词的大小写无关匹配、参数切分、未知命令提示全在本类，
 *       不必在每个 handler 里重复判断。</li>
 * </ol>
 *
 * <h2>解析规则（与旧行为兼容，且不吞掉用户的真实提问）</h2>
 * <ul>
 *   <li>裸词命令：{@code exit} 直接退出（保留既有直觉），<b>必须整行匹配</b>——
 *       否则「exit code 是什么」会被当成退出指令而丢失输入；{@code /exit}{@code /quit} 等价；</li>
 *   <li>斜杠命令：{@code /help}、{@code /?}、{@code /retry}，大小写无关，允许带参数（{@code /model fast}）；</li>
 *   <li>以 {@code /} 开头且<b>未注册</b>，分两种情形：
 *     <ul>
 *       <li>整行就是一个 token（{@code /helpp}）→ 判为打错的命令，就地提示 + 可用清单，
 *           返回 {@link CommandOutcome#HANDLED}：省一轮无意义的 token；</li>
 *       <li>token 后还有文本（{@code /usr/bin/env 是什么}、{@code /api/users 怎么设计}）
 *           → <b>回退为普通提问</b>：以斜杠开头的自然语言（路径、URL、API 端点）
 *           相当常见，把它们当成命令会直接吞掉用户的真实问题，损失远大于省下的 token。</li>
 *     </ul>
 *   </li>
 *   <li>其余输入 → {@link CommandOutcome#NOT_A_COMMAND}，交回主循环当普通提问。</li>
 * </ul>
 *
 * <h2>健壮性</h2>
 * <p>handler 抛异常不终结会话（与 {@code ToolRegistry.dispatch} 同一哲学）：
 * 命令失败属「本次操作受阻」，记 WARN 并给出可读提示后继续 REPL。
 * {@code OutOfMemoryError} 是唯一例外——JVM 级不可恢复状态不上报为「命令失败」。</p>
 */
public final class SlashCommandRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SlashCommandRegistry.class);

    /** 命令名单一事实源（Agent 的提示文案与测试均引用此处，不再各写一份字面量）。 */
    public static final String EXIT_COMMAND = "exit";
    public static final String RETRY_COMMAND = "/retry";
    public static final String HELP_COMMAND = "/help";

    /** 已注册命令：key = 小写调用名（含别名）→ 命令声明。别名与主名同表，匹配一次命中。 */
    private final Map<String, SlashCommand> byInvocationName = new LinkedHashMap<>();

    /**
     * 预置内建命令（{@code exit} / {@code /retry} / {@code /help}）的注册表。
     *
     * <p>{@code /help} 的 handler 闭包捕获 <b>本注册表实例</b>，因此后续追加的命令
     * （{@code /context}、{@code /model}…）会自动出现在帮助文本里，无需手工维护清单。</p>
     */
    public static SlashCommandRegistry withBuiltins() {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        registry.register(new SlashCommand(EXIT_COMMAND, List.of("/exit", "/quit"),
                "结束当前会话（历史自动保存，可稍后 resume）",
                (ctx, arg) -> CommandOutcome.EXIT));
        registry.register(new SlashCommand(RETRY_COMMAND, List.of(),
                "重发上一个失败回合（仅在请求失败后可用）",
                (ctx, arg) -> {
                    ctx.retryLastTurn().run();
                    return CommandOutcome.HANDLED;
                }));
        registry.register(new SlashCommand(HELP_COMMAND, List.of("/?"),
                "显示本帮助：全部命令与当前真实可用能力",
                (ctx, arg) -> {
                    ctx.println(renderHelp(registry, ctx.capabilities()));
                    return CommandOutcome.HANDLED;
                }));
        return registry;
    }

    /**
     * 注册一条命令（主名与全部别名都登记，大小写无关）。
     *
     * <p><b>覆盖同名主命令时先摘除它登记的全部调用名</b>：如果只覆写主名，
     * 旧命令的别名会残留（如用自定义 {@code /help} 覆盖内建后仍留下 {@code /?}），
     * 结果是两个不同实例共享同一主名——{@code /help} 渲染出重复行，
     * 而 {@code /?} 指向旧实现，行为取决于内部 Map 顺序，极难排查。
     * 重名覆盖仍记 WARN，但同主名的定制覆盖（常见用法）不当作异常。</p>
     */
    public void register(SlashCommand command) {
        // 摘除同主名的旧命令（连同其全部别名），避免别名孤儿
        SlashCommand previous = byInvocationName.get(command.name().toLowerCase(Locale.ROOT));
        if (previous != null) {
            byInvocationName.values().removeIf(c -> c.name().equalsIgnoreCase(previous.name()));
        }
        for (String invocation : command.invocationNames()) {
            SlashCommand existing = byInvocationName.put(invocation.toLowerCase(Locale.ROOT), command);
            if (existing != null && !existing.name().equalsIgnoreCase(command.name())) {
                logger.warn("命令调用名 '{}' 已属于 {}，被 {} 覆盖",
                        invocation, existing.name(), command.name());
            }
        }
    }

    /** 全部已注册命令（去重后按主名字典序；{@code /help} 与测试的数据源）。 */
    public List<SlashCommand> commands() {
        return byInvocationName.values().stream()
                .distinct()
                .sorted(Comparator.comparing(SlashCommand::name))
                .toList();
    }

    /**
     * 输入是否会被当作命令处理。
     *
     * <p>与 {@link #dispatch} 必须同结论（分叉判定最难查）：命中已注册命令，
     * 或整行就是一个未注册的斜杠 token（将得到未知命令提示）。</p>
     */
    public boolean isCommandInput(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String trimmed = input.trim();
        String head = headOf(trimmed);
        if (!head.startsWith("/")) {
            return matchBareWord(head, trimmed) != null;
        }
        // 斜杠开头：已注册即命令；未注册但整行为单 token 也算（走提示分支）；
        // 未注册且后面还有文本 → 当普通提问（如 /usr/bin/env 是什么）
        return byInvocationName.containsKey(head.toLowerCase(Locale.ROOT))
                || head.length() == trimmed.length();
    }

    /**
     * 解析并执行一条输入。
     *
     * @param input 用户原始输入（可含参数与多余空白）
     * @param ctx   执行上下文
     * @return 三态结果，主循环据此决定「交模型 / 继续 / 退出」
     */
    public CommandOutcome dispatch(String input, SlashCommandContext ctx) {
        if (input == null || input.isBlank()) {
            return CommandOutcome.NOT_A_COMMAND;
        }
        String trimmed = input.trim();
        String head = headOf(trimmed);
        String argument = trimmed.length() > head.length()
                ? trimmed.substring(head.length() + 1).trim()
                : "";

        SlashCommand command = head.startsWith("/")
                ? byInvocationName.get(head.toLowerCase(Locale.ROOT))
                : matchBareWord(head, trimmed);
        if (command == null) {
            // 整行就是一个未注册的斜杠 token（如 /helpp）→ 判为打错的命令，
            // 就地提示并消化：模型拿到 /helpp 只会困惑，提示对用户更有用
            if (head.startsWith("/") && head.length() == trimmed.length()) {
                ctx.println(unknownSlashCommandMessage(head));
                return CommandOutcome.HANDLED;
            }
            // 其余（普通提问，以及“/usr/bin/env 是什么”这类含路径的句子）交给模型
            return CommandOutcome.NOT_A_COMMAND;
        }

        try {
            CommandOutcome outcome = command.handler().handle(ctx, argument);
            return outcome != null ? outcome : CommandOutcome.HANDLED;
        } catch (OutOfMemoryError oom) {
            throw oom; // JVM 级不可恢复：与 dispatch 工具的处置一致，原样上抛
        } catch (Throwable t) {
            logger.warn("命令 {} 执行异常: {}", command.name(), t.toString(), t);
            ctx.println("命令 " + command.name() + " 执行失败：" + t.getClass().getSimpleName()
                    + "。会话未结束，可重试或用 " + HELP_COMMAND + " 查看可用命令。");
            return CommandOutcome.HANDLED;
        }
    }

    /**
     * 裸词命令匹配：<b>仅当整行就是命令名</b>时才命中。
     *
     * <p>这是旧行为的硬约束（{@code trimmed.equalsIgnoreCase("exit")}），不可放宽：
     * 若按首词匹配，用户提问「exit code 是什么」或「如何 exit 进程」会被当成退出指令
     * 直接结束会话（实测踩到过，用户输入丢失）。斜杠命令才允许带参数
     * （{@code /model fast}）——前缀本身就是「这是命令」的显式声明。</p>
     *
     * @return 命中的命令；不是裸词命令时返回 null
     */
    private SlashCommand matchBareWord(String head, String trimmedLine) {
        if (head.length() != trimmedLine.length()) {
            return null; // 后面还有内容 → 是句子（如「exit code 是什么」），不是裸词命令
        }
        return byInvocationName.get(head.toLowerCase(Locale.ROOT));
    }

    /** 取输入的第一个空白分隔词作为命令名。 */
    private static String headOf(String trimmed) {
        int space = indexOfFirstWhitespace(trimmed);
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    private static int indexOfFirstWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private String unknownSlashCommandMessage(String head) {
        List<String> names = commands().stream()
                .flatMap(c -> c.invocationNames().stream())
                .sorted()
                .toList();
        return "未知命令：" + head + "\n可用命令：" + String.join("、", names)
                + "\n（普通提问无需斜杠前缀，直接输入即可）";
    }

    // =========================================================================
    // /help 渲染（AC3：命令表与工具表均取自注册实况，非硬编码文本）
    // =========================================================================

    /**
     * 渲染 {@code /help} 全文。
     *
     * <p>刻意做成 <b>public static 纯函数</b>（入参就是数据源）而非实例方法：
     * {@code HelpConsistencyTest} 可以拿同一份 {@code CapabilityRegistry} 断言
     * 「输出里出现的工具名/工具集/条数」与注册实况逐项相等——若渲染改成读其它来源，
     * 该断言立刻失败。这就是「同源」由构造保证、由测试看守的含义。</p>
     *
     * @param registry     命令注册表（命令表数据源）
     * @param capabilities 能力实况（工具表数据源）
     * @return 纯文本帮助（终端不渲染 Markdown，故用【】分节、两空格缩进）
     */
    public static String renderHelp(SlashCommandRegistry registry, CapabilityRegistry capabilities) {
        StringBuilder sb = new StringBuilder();
        sb.append("◆ ").append(Constants.CLI_NAME).append(" · 帮助\n");

        sb.append("\n【可用命令】\n");
        List<SlashCommand> commands = registry.commands();
        int nameWidth = commands.stream()
                .map(c -> String.join(", ", c.invocationNames()))
                .mapToInt(SlashCommandRegistry::displayWidth).max().orElse(8);
        for (SlashCommand command : commands) {
            sb.append("  ")
                    .append(pad(String.join(", ", command.invocationNames()), nameWidth))
                    .append("  ").append(command.description()).append('\n');
        }
        sb.append("  ").append(pad("（普通提问）", nameWidth))
                .append("  直接输入问题即可，无需斜杠前缀\n");

        sb.append(renderCapabilities(capabilities));

        sb.append("\n【说明】\n")
                .append("  · 上表以外的工具当前不可用：被 tools.disabled_toolsets 禁用，")
                .append("或初始化未成功（如 RAG 未连上数据库）\n")
                .append("  · 命令与工具清单均由代码从注册表实时派生，不会与真实能力脱节\n")
                .append("  · 终端不渲染 Markdown，回复为纯文本\n");
        return sb.toString().stripTrailing();
    }

    /** 工具表：按工具集分组、集内工具名字典序（与系统提示词能力段同一数据源）。 */
    private static String renderCapabilities(CapabilityRegistry capabilities) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<String>> summary = capabilities.asSummary();
        sb.append("\n【当前可用工具】共 ")
                .append(capabilities.toolCount()).append(" 个，")
                .append(summary.size()).append(" 个工具集\n");
        if (summary.isEmpty()) {
            sb.append("  （无已注册工具：本次会话只能依据模型自身知识回答）\n");
            return sb.toString();
        }
        int width = summary.keySet().stream().mapToInt(String::length).max().orElse(4);
        summary.forEach((toolset, names) -> sb.append("  ")
                .append(pad(toolset, width))
                .append(" (").append(names.size()).append(")  ")
                .append(String.join(", ", names))
                .append('\n'));
        return sb.toString();
    }

    /**
     * 右侧补空格对齐：按<b>终端显示宽度</b>而非字符数。
     *
     * <p>命令表含中文描述与全角字符（如「（普通提问）」），它们在等宽字体下占两格。
     * 按 {@code String.length()} 对齐会让这些行明显歪掉——帮助文本的可读性
     * 就是它的功能，故引入一个极简宽度估算（CJK 区计 2，其余计 1）。</p>
     */
    private static String pad(String text, int width) {
        int padding = width - displayWidth(text);
        return padding > 0 ? text + " ".repeat(padding) : text;
    }

    /** 估算字符串在等宽终端下的显示列数（≥ U+2E80 的双宽字符计 2，其余计 1）。 */
    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) >= 0x2E80 ? 2 : 1;
        }
        return width;
    }

    /** 命令数量（测试与诊断用）。 */
    public int commandCount() {
        return (int) commands().size();
    }

    /** 全部调用形式（主名 + 别名，字典序）；未知命令提示与测试断言共用。 */
    public List<String> invocationNames() {
        List<String> names = new ArrayList<>(byInvocationName.keySet());
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }
}
