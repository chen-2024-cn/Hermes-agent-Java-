package com.cyk.command.slash;

import com.cyk.bean.ToolEntry;
import com.cyk.capability.CapabilityRegistry;
import com.cyk.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 斜杠命令注册表单元测试（JH-SPEC-001 P0-3 R4）。
 *
 * <p>重点验证三态返回的语义边界——这是 REPL「该发模型还是不该发」的唯一判据，
 * 错了要么浪费 token 让模型解读 {@code /blah}，要么把用户的正常提问吞掉。</p>
 */
class SlashCommandRegistryTest {

    /** 捕获输出与重试计数的测试上下文。 */
    private static final class Fixture {
        final List<String> out = new ArrayList<>();
        final AtomicInteger retries = new AtomicInteger();
        final SlashCommandContext ctx;

        Fixture(CapabilityRegistry capabilities) {
            ctx = new SlashCommandContext(out::add, capabilities, retries::incrementAndGet);
        }

        String joined() {
            return String.join("\n", out);
        }
    }

    private static CapabilityRegistry caps(Map<String, List<String>> toolsetTools) {
        ToolRegistry registry = new ToolRegistry();
        toolsetTools.forEach((toolset, names) -> names.forEach(name -> registry.register(
                new ToolEntry.Builder()
                        .name(name).toolset(toolset)
                        .schema(Map.of("description", name,
                                "parameters", Map.of("type", "object", "properties", Map.of())))
                        .handler(args -> "ok")
                        .build())));
        return CapabilityRegistry.from(registry);
    }

    private static Fixture fixture() {
        return new Fixture(caps(Map.of(
                "file_operations", List.of("read_file", "write_file"),
                "terminal", List.of("run_command"))));
    }

    private final SlashCommandRegistry registry = SlashCommandRegistry.withBuiltins();

    // =========================================================================
    // 内建命令与三态语义
    // =========================================================================

    @Test
    @DisplayName("withBuiltins 注册全部内建与草稿命令，主名字典序")
    void builtinsShouldRegisterFourCommands() {
        assertThat(registry.commands())
                .extracting(SlashCommand::name)
                .containsExactly("/chat", "/clear", "/context", "/debug", "/help", "/image", "/init", "/multiline", "/paste", "/remove", "/resume", "/retry", "/sessions", "exit");
        assertThat(registry.commandCount()).isEqualTo(14);
    }

    @Test
    @DisplayName("exit 与 /exit 都返回 EXIT（裸词直觉 + 斜杠形式兼容）")
    void exitShouldSignalSessionEnd() {
        Fixture f = fixture();

        assertThat(registry.dispatch("exit", f.ctx)).isEqualTo(CommandOutcome.EXIT);
        assertThat(registry.dispatch("EXIT", f.ctx)).isEqualTo(CommandOutcome.EXIT);
        assertThat(registry.dispatch("  exit  ", f.ctx)).isEqualTo(CommandOutcome.EXIT);
        // exit 不需要输出任何内容（告别语由 run() 收尾统一打印，避免两处各说一遍）
        assertThat(f.out).isEmpty();
    }

    @Test
    @DisplayName("/retry 触发宿主重试动作并返回 HANDLED（不重复入队用户消息）")
    void retryShouldDelegateToHostAction() {
        Fixture f = fixture();

        assertThat(registry.dispatch("/retry", f.ctx)).isEqualTo(CommandOutcome.HANDLED);

        assertThat(f.retries).hasValue(1);
        assertThat(f.out).as("无可重试回合时由宿主提示，注册表不额外输出").isEmpty();
    }

    @Test
    @DisplayName("/help 打印帮助并返回 HANDLED；内容含命令表与工具表")
    void helpShouldRenderCommandsAndCapabilities() {
        Fixture f = fixture();

        assertThat(registry.dispatch("/help", f.ctx)).isEqualTo(CommandOutcome.HANDLED);

        String help = f.joined();
        assertThat(help).contains("可用命令").contains("/retry").contains("exit")
                .contains("当前可用工具").contains("read_file").contains("run_command");
        assertThat(f.joined()).contains("Jhermes");
    }

    @Test
    @DisplayName("/? 是 /help 别名，行为等价")
    void questionMarkAliasShouldBehaveLikeHelp() {
        Fixture f = fixture();

        assertThat(registry.dispatch("/?", f.ctx)).isEqualTo(CommandOutcome.HANDLED);
        assertThat(f.joined()).contains("可用命令");
    }

    @Test
    @DisplayName("普通提问返回 NOT_A_COMMAND（不发提示、不改状态，交回主循环）")
    void ordinaryQuestionsAreNotCommands() {
        Fixture f = fixture();

        for (String input : List.of("帮我写个排序", "exit code 是什么", "read_file 怎么用",
                "如何退出程序",
                // 以斜杠开头但后面还有文本 → 是句子里的路径/端点，绝不能当命令吞掉
                "/usr/bin/env 是什么", "/api/users 接口怎么设计")) {
            assertThat(registry.dispatch(input, f.ctx))
                    .as("输入 %s 应判为普通提问", input)
                    .isEqualTo(CommandOutcome.NOT_A_COMMAND);
            assertThat(registry.isCommandInput(input)).as("isCommandInput 应与 dispatch 同结论：%s", input)
                    .isFalse();
        }
        assertThat(f.out).as("非命令输入不得产生任何命令输出").isEmpty();
    }

    @Test
    @DisplayName("打错的单 token 斜杠命令（/helpp）：就地提示 + 列出可用命令")
    void misspelledSingleTokenSlashCommandGetsHint() {
        Fixture f = fixture();

        assertThat(registry.dispatch("/helpp", f.ctx)).isEqualTo(CommandOutcome.HANDLED);
        assertThat(registry.isCommandInput("/helpp")).isTrue();
        assertThat(f.joined()).contains("未知命令：/helpp").contains("/help");
    }

    @Test
    @DisplayName("未注册的斜杠命令：就地提示 + 列出可用命令，不发往模型")
    void unknownSlashCommandIsHandledLocally() {
        Fixture f = fixture();

        CommandOutcome outcome = registry.dispatch("/blah", f.ctx);

        assertThat(outcome).as("未知斜杠命令应就地消化，省一轮 token").isEqualTo(CommandOutcome.HANDLED);
        assertThat(f.joined()).contains("未知命令：/blah")
                .contains("/help").contains("/retry").contains("exit")
                .contains("普通提问无需斜杠前缀");
    }

    @Test
    @DisplayName("isCommandInput 与 dispatch 判定一致：以斜杠开头或命中裸词命令")
    void isCommandInputShouldAgreeWithDispatch() {
        Fixture f = fixture();

        assertThat(registry.isCommandInput("/help")).isTrue();
        assertThat(registry.isCommandInput("/nope")).isTrue();  // 单 token 未注册：走提示分支
        assertThat(registry.isCommandInput("/nope 后面还有文本")).isFalse(); // 句子 → 提问
        assertThat(registry.isCommandInput("exit")).isTrue();
        assertThat(registry.isCommandInput("EXIT")).isTrue();
        assertThat(registry.isCommandInput("退出")).isFalse();
        assertThat(registry.isCommandInput("exit code 是什么")).isFalse(); // exit 后跟内容 = 普通提问
        assertThat(registry.isCommandInput("   ")).isFalse();
        // 与 dispatch 结论一致（判定分叉是最难查的一类 bug）
        assertThat(registry.isCommandInput("/blah"))
                .isEqualTo(registry.dispatch("/blah", f.ctx) != CommandOutcome.NOT_A_COMMAND);
        assertThat(registry.isCommandInput("今天天气"))
                .isEqualTo(registry.dispatch("今天天气", f.ctx) != CommandOutcome.NOT_A_COMMAND);
    }

    @Test
    @DisplayName("参数切分：命令名之后的文本作为 argument 传给 handler（为 /model fast 预留）")
    void argumentsShouldBePassedToHandler() {
        Fixture f = fixture();
        List<String> captured = new ArrayList<>();
        registry.register(SlashCommand.of("/echo", "回显参数用于测试",
                (ctx, arg) -> {
                    ctx.println("arg=[" + arg + "]");
                    captured.add(arg);
                    return CommandOutcome.HANDLED;
                }));

        registry.dispatch("/echo   多个   空格   参数 ", f.ctx);

        assertThat(captured).containsExactly("多个   空格   参数");
        assertThat(f.joined()).contains("arg=[多个   空格   参数]");
        // 无参数时为空串而非 null（handler 不必判 null）
        List<String> noArg = new ArrayList<>();
        registry.register(SlashCommand.of("/noarg", "无参测试",
                (ctx, arg) -> {
                    noArg.add(arg);
                    return CommandOutcome.HANDLED;
                }));
        registry.dispatch("/noarg", f.ctx);
        assertThat(noArg).containsExactly("");
    }

    // =========================================================================
    // 健壮性与可扩展性
    // =========================================================================

    @Test
    @DisplayName("handler 抛异常：降级为可读提示 + HANDLED，绝不掀翻 REPL")
    void handlerExceptionShouldNotEscape() {
        Fixture f = fixture();
        registry.register(SlashCommand.of("/boom", "总是抛异常的测试命令",
                (ctx, arg) -> {
                    throw new IllegalStateException("boom 内部错误");
                }));

        CommandOutcome outcome = registry.dispatch("/boom", f.ctx);

        assertThat(outcome).as("命令失败属本次操作受阻，不是会话终止").isEqualTo(CommandOutcome.HANDLED);
        assertThat(f.joined()).contains("/boom").contains("IllegalStateException")
                .contains("会话未结束");
    }

    @Test
    @DisplayName("handler 返回 null 视为 HANDLED（防御式默认，主循环不会拿到 null 而 NPE）")
    void nullHandlerOutcomeDefaultsToHandled() {
        Fixture f = fixture();
        registry.register(SlashCommand.of("/nullish", "返回 null 的测试命令", (ctx, arg) -> null));

        assertThat(registry.dispatch("/nullish", f.ctx)).isEqualTo(CommandOutcome.HANDLED);
    }

    @Test
    @DisplayName("追加命令后 /help 自动列出（P0-4 的 /context、P1-3 的 /model 走此路径）")
    void helpShouldPickUpLaterRegisteredCommands() {
        Fixture f = fixture();
        registry.register(new SlashCommand("/context", List.of("/ctx"), "显示分段预算明细（测试）",
                (ctx, arg) -> CommandOutcome.HANDLED));
        registry.register(SlashCommand.of("/model", "切换模型别名（测试）",
                (ctx, arg) -> CommandOutcome.HANDLED));

        registry.dispatch("/help", f.ctx);

        String help = f.joined();
        assertThat(help).contains("/context, /ctx").contains("/model")
                .contains("显示分段预算明细").contains("切换模型别名");
        assertThat(registry.commandCount()).isEqualTo(15);
    }

    @Test
    @DisplayName("裸词命令必须整行匹配：「exit code 是什么」不得被当成退出指令（真实回归）")
    void bareWordCommandRequiresWholeLineElseUserLosesSession() {
        Fixture f = fixture();

        // 旧实现按首词匹配时，这类正常提问会直接结束会话（用户输入丢失）
        for (String question : List.of("exit code 是什么", "如何 exit 一个进程",
                "exit(0) 和 exit(1) 区别")) {
            assertThat(registry.dispatch(question, f.ctx))
                    .as("提问 %s 不得触发退出", question)
                    .isEqualTo(CommandOutcome.NOT_A_COMMAND);
            assertThat(registry.isCommandInput(question)).isFalse();
        }
        // 整行（含首尾空白）才算裸词命令
        assertThat(registry.dispatch("\texit ", f.ctx)).isEqualTo(CommandOutcome.EXIT);
        assertThat(registry.dispatch("/exit\n粘贴的第二行", f.ctx)).isEqualTo(CommandOutcome.NOT_A_COMMAND);
        assertThat(registry.isCommandInput("/help\r\nyes")).isFalse();
        // 斜杠形式可带参数（为 /model fast 预留），/exit /quit 与 exit 等价
        assertThat(registry.dispatch("/exit", f.ctx)).isEqualTo(CommandOutcome.EXIT);
        assertThat(registry.dispatch("/quit", f.ctx)).isEqualTo(CommandOutcome.EXIT);
    }

    @Test
    @DisplayName("未知命令提示列出主名与别名，并告知普通提问无需斜杠")
    void unknownCommandHintShouldListInvocations() {
        Fixture f = fixture();

        // /context 已成为内建命令，这里换一个确实未注册的命令名来验证"未知命令"提示
        registry.dispatch("/model", f.ctx);

        String message = f.joined();
        assertThat(message).contains("未知命令：/model")
                .contains("/help").contains("/retry").contains("exit")
                .contains("普通提问无需斜杠前缀");
    }

    @Test
    @DisplayName("覆盖注册不留别名孤儿：自定义 /help 后旧 /? 不再指向内建实现")
    void overrideShouldRemoveAliasesOfReplacedCommand() {
        Fixture f = fixture();
        registry.register(SlashCommand.of("/help", "被覆盖的自定义帮助",
                (ctx, arg) -> {
                    ctx.println("custom help");
                    return CommandOutcome.HANDLED;
                }));

        // 主名走新实现
        assertThat(registry.dispatch("/help", f.ctx)).isEqualTo(CommandOutcome.HANDLED);
        assertThat(f.joined()).isEqualTo("custom help");

        // 旧命令的别名随之一并摘除（显式优于隐式）：不声明就不保留，
        // 绝不允许两个不同实例共享 /help 主名（那种状态下行为取决于 Map 顺序，无法排查）
        List<String> afterAlias = new ArrayList<>();
        assertThat(registry.dispatch("/?", new SlashCommandContext(afterAlias::add,
                f.ctx.capabilities(), f.ctx.retryLastTurn())))
                .isEqualTo(CommandOutcome.HANDLED);
        assertThat(String.join("\n", afterAlias)).contains("未知命令：/?");

        assertThat(registry.commands())
                .extracting(SlashCommand::name)
                .doesNotHaveDuplicates()
                .contains("/help");
    }

    @Test
    @DisplayName("空白/null 输入安全返回 NOT_A_COMMAND；构造校验拒绝空名与无执行体")
    void blankInputAndInvalidDeclarationAreRejected() {
        Fixture f = fixture();

        assertThat(registry.dispatch(null, f.ctx)).isEqualTo(CommandOutcome.NOT_A_COMMAND);
        assertThat(registry.dispatch("   ", f.ctx)).isEqualTo(CommandOutcome.NOT_A_COMMAND);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> SlashCommand.of("  ", "空名", (ctx, arg) -> CommandOutcome.HANDLED))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> SlashCommand.of("/x", "无执行体", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("invocationNames 汇总主名与别名（未知命令提示的数据源）")
    void invocationNamesShouldIncludeAliases() {
        assertThat(registry.invocationNames())
                .contains("/help", "/?", "/retry", "exit", "/exit", "/quit")
                .isSorted();
    }
}
