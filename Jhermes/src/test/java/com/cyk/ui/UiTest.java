package com.cyk.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>Ui 门面与共享输入通道（JH-SPEC-001 P0-2 R0）</h1>
 *
 * <p>本测试锁住两件事：</p>
 * <ol>
 *   <li><b>通道可替换且可还原</b>——P1-2 的 {@code -p} 模式与全部输出断言都依赖这一点；
 *       若 {@code resetForTest()} 没还原干净，后续用例会看到彼此的输出而随机失败。</li>
 *   <li><b>非交互判据</b>——{@link UserInput#isInteractive()} 必须在无控制台时为 false，
 *       这是 P1-2 R4「非交互下需要确认即拒绝」的唯一依据。判据错了，
 *       管道调用（{@code echo x | Jhermes}）会永久阻塞在确认提示上。</li>
 * </ol>
 */
class UiTest {

    private InputStream originalIn;
    private PrintStream originalOut;

    @BeforeEach
    void snapshot() {
        originalIn = System.in;
        originalOut = System.out;
    }

    @AfterEach
    void restore() {
        System.setIn(originalIn);
        System.setOut(originalOut);
        Ui.resetForTest();
    }

    // =========================================================================
    // 输出通道
    // =========================================================================

    @Test
    @DisplayName("println/print 进入被替换的 out 流，而非真实 stdout")
    void outputGoesToReplacedStream() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Ui.setStreams(new PrintStream(captured, true, StandardCharsets.UTF_8), null);

        Ui.println("用户可见内容");
        Ui.print("不换行");
        Ui.flush();

        assertThat(captured.toString(StandardCharsets.UTF_8))
                .contains("用户可见内容")
                .contains("不换行");
    }

    @Test
    @DisplayName("diagln 走独立通道，不污染用户输出")
    void diagnosticsUseSeparateStream() {
        ByteArrayOutputStream userOut = new ByteArrayOutputStream();
        ByteArrayOutputStream diagOut = new ByteArrayOutputStream();
        Ui.setStreams(new PrintStream(userOut, true, StandardCharsets.UTF_8),
                new PrintStream(diagOut, true, StandardCharsets.UTF_8));

        Ui.println("答案");
        Ui.diagln("状态提示");

        assertThat(userOut.toString(StandardCharsets.UTF_8)).contains("答案").doesNotContain("状态提示");
        assertThat(diagOut.toString(StandardCharsets.UTF_8)).contains("状态提示").doesNotContain("答案");
    }

    @Test
    @DisplayName("setStreams(null,null) 与 resetForTest 都还原为真实流")
    void streamsAreRestorable() {
        PrintStream sentinel = new PrintStream(new ByteArrayOutputStream());
        Ui.setStreams(sentinel, sentinel);
        assertThat(Ui.out()).isSameAs(sentinel);

        Ui.setStreams(null, null);
        assertThat(Ui.out()).isEqualTo(System.out);
        assertThat(Ui.diag()).isEqualTo(System.err);
    }

    // =========================================================================
    // 输入通道（R0 的核心：共用同一 stdin）
    // =========================================================================

    @Test
    @DisplayName("ConsoleUserInput 读到的行来自被注入的 System.in")
    void consoleUserInputReadsFromStdin() {
        System.setIn(new ByteArrayInputStream("第一行\n第二行\n".getBytes(StandardCharsets.UTF_8)));
        UserInput.ConsoleUserInput input = new UserInput.ConsoleUserInput();

        assertThat(input.readLine(null)).isEqualTo("第一行");
        assertThat(input.readLine(null)).isEqualTo("第二行");
    }

    @Test
    @DisplayName("提示符在读取前被打印（否则用户对着空行不知该输入什么）")
    void promptIsPrintedBeforeReading() {
        System.setIn(new ByteArrayInputStream("ok\n".getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Ui.setStreams(new PrintStream(captured, true, StandardCharsets.UTF_8), null);

        UserInput.ConsoleUserInput input = new UserInput.ConsoleUserInput();
        String line = input.readLine("选择 > ");

        assertThat(line).isEqualTo("ok");
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("选择 > ");
    }

    @Test
    @DisplayName("EOF 后 readLine 返回 null 且 isEofReached 为真（REPL 据此收尾而非空转）")
    void eofIsDetected() {
        System.setIn(new ByteArrayInputStream("only-one-line\n".getBytes(StandardCharsets.UTF_8)));
        UserInput.ConsoleUserInput input = new UserInput.ConsoleUserInput();

        assertThat(input.readLine(null)).isEqualTo("only-one-line");
        assertThat(input.isEofReached()).isFalse();

        assertThat(input.readLine(null)).as("流已读完").isNull();
        assertThat(input.isEofReached()).isTrue();
        assertThat(input.readLine(null)).as("EOF 后继续读仍应返回 null，不得抛异常").isNull();
    }

    @Test
    @DisplayName("无控制台时 isInteractive 为 false：确认闸门必须据此直接拒绝（P1-2 R4 依据）")
    void nonInteractiveWhenNoConsole() {
        // surefire 环境下 System.console() 恒为 null，与管道调用 / 重定向场景等价
        UserInput.ConsoleUserInput input = new UserInput.ConsoleUserInput();

        assertThat(System.console()).as("测试环境无控制台").isNull();
        assertThat(input.isInteractive()).isFalse();
    }

    @Test
    @DisplayName("EOF 之后即使曾有控制台也判定为非交互")
    void eofMakesInputNonInteractive() {
        System.setIn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        UserInput.ConsoleUserInput input = new UserInput.ConsoleUserInput();

        input.readLine(null);// 触发 EOF
        assertThat(input.isInteractive()).isFalse();
    }

    @Test
    @DisplayName("close 不会破坏共享通道：关闭后仍可再次读取")
    void closeIsNonDestructive() {
        System.setIn(new ByteArrayInputStream("after-close\n".getBytes(StandardCharsets.UTF_8)));
        UserInput.ConsoleUserInput input = new UserInput.ConsoleUserInput();

        input.close();

        assertThat(input.readLine(null))
                .as("REPL 退出时关闭通道不得关掉 System.in，否则确认闸门会抛 Stream closed")
                .isEqualTo("after-close");
    }

    @Test
    @DisplayName("setUserInput 可注入桩，null 被忽略（防止测试把自己变成不可输入）")
    void userInputIsInjectable() {
        UserInput stub = new UserInput() {
            @Override public String readLine(String prompt) { return "y"; }
            @Override public boolean isInteractive() { return true; }
            @Override public boolean isEofReached() { return false; }
            @Override public void close() { }
        };

        Ui.setUserInput(stub);
        assertThat(Ui.userInput()).isSameAs(stub);

        Ui.setUserInput(null);
        assertThat(Ui.userInput()).as("传入 null 不得把通道清空").isSameAs(stub);
    }
}
