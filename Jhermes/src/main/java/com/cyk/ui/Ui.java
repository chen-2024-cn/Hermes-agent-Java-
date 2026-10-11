package com.cyk.ui;

import java.io.PrintStream;

/**
 * <h1>输出通道门面（JH-SPEC-001 P0-2 R0 最小版，P1-1 R3 扩展）</h1>
 *
 * <p>把「谁在往屏幕上写字」收口到一个类，是所有后续输出治理的前提：</p>
 * <ol>
 *   <li><b>通道分离</b>：{@link #out()} 是给用户看的内容（stdout，可被重定向到文件），
 *       {@link #diag()} 是状态与提示（stderr，重定向时仍可见）——P1-1 R1 要求
 *       框架日志全部走 diag；</li>
 *   <li><b>可替换</b>：{@code -p} 非交互模式（P1-2 R1）要求 stdout 只输出最终答案，
 *       把 banner、用量行、命令回显全部抑制；实现方式是替换本类持有的流，
 *       而不是在每个调用点加 if；</li>
 *   <li><b>可测试</b>：单测能把两条流换成 {@code ByteArrayOutputStream}，
 *       从而断言「用户看到了什么」而不污染真实控制台。</li>
 * </ol>
 *
 * <p>P1-1 R7 会加 ArchUnit 规则：除本包外禁止直接访问 {@code System.out} / {@code System.err}。
 * 因此本类是整个工程<b>唯一</b>被允许触达这两个流的出口。</p>
 *
 * <p>同时持有进程级唯一的 {@link UserInput}（见 {@link UserInput} 的类注释：
 * Agent 与确认闸门必须共用同一 stdin 来源）。</p>
 */
public final class Ui {

    private static volatile PrintStream outStream = System.out;
    private static volatile PrintStream diagStream = System.err;
    private static volatile UserInput userInput = new UserInput.ConsoleUserInput();

    private Ui() {
        // 门面类禁止实例化
    }

    /** @return 用户可见内容的输出流（stdout 语义） */
    public static PrintStream out() {
        return outStream;
    }

    /** @return 状态、提示与框架日志的输出流（stderr 语义） */
    public static PrintStream diag() {
        return diagStream;
    }

    /** 向用户流打印一行。 */
    public static void println(String line) {
        outStream.println(line);
    }

    /** 向用户流打印不换行文本。 */
    public static void print(String text) {
        outStream.print(text);
    }

    /** 显式刷新用户流：提示符等不带换行的输出必须刷新，否则不会立即显示。 */
    public static void flush() {
        outStream.flush();
    }

    /** 向诊断流打印一行（不进 stdout，因此在 {@code > out.txt} 时不会污染结果）。 */
    public static void diagln(String line) {
        diagStream.println(line);
    }

    /** @return 进程级共享的输入通道（REPL 与确认闸门共用） */
    public static UserInput userInput() {
        return userInput;
    }

    /**
     * 替换输入通道。
     *
     * <p>用于测试注入桩；生产代码只在 P1-3 切换到 JLine 实现时调用一次。
     * 替换是全局的，因此测试必须在 {@code @AfterEach} 还原，否则会污染其它用例。</p>
     */
    public static void setUserInput(UserInput input) {
        if (input != null) {
            userInput = input;
        }
    }

    /** 替换输出流（P1-2 非交互模式与单测断言用）。null 表示还原为真实流。 */
    public static void setStreams(PrintStream out, PrintStream diag) {
        outStream = (out != null) ? out : System.out;
        diagStream = (diag != null) ? diag : System.err;
    }

    /** 还原所有通道（测试收尾必须调用，否则会污染其它用例）。 */
    public static void resetForTest() {
        outStream = System.out;
        diagStream = System.err;
        userInput = new UserInput.ConsoleUserInput();
    }
}
