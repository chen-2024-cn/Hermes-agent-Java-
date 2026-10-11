package com.cyk.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * <h1>用户交互抽象（JH-SPEC-001 P0-2 R0）</h1>
 *
 * <p>确认闸门需要一个「向用户提问并等待回答」的通道。R0 对此有一条硬约束：</p>
 *
 * <blockquote>Agent 与确认闸门<b>共用同一个 stdin 来源</b>。不得另建第二个读取
 * {@code System.in} 的 reader，否则缓冲区会吞掉输入。</blockquote>
 *
 * <p>这条约束源自 {@link BufferedReader} 的预读语义：它会一次性从底层流读入远超一行的
 * 数据填进内部缓冲区。若 REPL 与确认闸门各自持有一个 reader，REPL 的 reader 可能已经
 * 把用户接下来要输入的 y/n 预读进自己的缓冲区，闸门的 reader 于是永久阻塞——
 * 表现就是「确认提示出现后，敲什么都没用」这类极难排查的死锁。</p>
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li>{@link #readLine(String)} 是唯一的输入入口，所有交互（对话输入、y/n 确认、
 *       键入 yes 强确认）都必须走它；</li>
 *   <li>{@link #isInteractive()} 决定确认闸门的策略：非交互（管道输入、CI 调用）时
 *       一律拒绝需要确认的操作，而不是阻塞等待（P1-2 R4）。</li>
 * </ul>
 *
 * <p>P0-2 阶段只做最小抽象（转发 {@code System.out} 与一个共享的 reader）；
 * P1-3 会追加 JLine 实现（行编辑、历史、Ctrl+C 中断），届时 REPL 与闸门仍共用同一实例。</p>
 */
public interface UserInput {

    /**
     * 打印提示并读取一行输入。
     *
     * @param prompt 提示文本；null 或空串表示不打印提示
     * @return 用户输入的一行（不含换行符）；{@code null} 表示输入流已结束（EOF）
     */
    String readLine(String prompt);

    /**
     * 是否可以与用户交互。
     *
     * <p>判据通常是「是否存在控制台」加上「输入流是否已 EOF」。管道输入
     * （{@code echo xxx | Jhermes}）时 {@link System#console()} 为 null，
     * 因此确认闸门必须直接拒绝而非等待——否则进程会永久挂住。</p>
     */
    boolean isInteractive();

    /** @return 输入流是否已到达 EOF（用于 REPL 正常收尾而非空转刷屏） */
    boolean isEofReached();

    /**
     * 释放底层资源。
     *
     * <p>REPL 主循环退出时<b>不应</b>关闭共享 reader：确认闸门可能还在等待输入，
     * 且关闭 {@code System.in} 后无法恢复。实现应使本方法成为幂等的空操作或仅在
     * 进程确实退出时生效。</p>
     */
    void close();

    /**
     * 基于共享 {@link BufferedReader} 的控制台实现（默认实现）。
     *
     * <p>整个进程只应存在一个实例（见 {@link Ui#userInput()}），
     * 这正是「共用同一 stdin」约束的技术落点。</p>
     */
    final class ConsoleUserInput implements UserInput {

        private final BufferedReader reader =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        private volatile boolean eofReached = false;

        /** 交互式判定在构造时快照：进程生命周期内控制台可用性不会变化。 */
        private final boolean consoleAvailable = System.console() != null;

        @Override
        public String readLine(String prompt) {
            if (prompt != null && !prompt.isEmpty()) {
                PrintStream out = Ui.out();
                out.print(prompt);
                // 必须 flush：提示符不带换行，缓冲的 PrintStream 不会自动刷新，
                // 不刷则用户看到光标停在没有提示文字的空行上
                out.flush();
            }
            try {
                String line = reader.readLine();
                if (line == null) {
                    eofReached = true;
                }
                return line;
            } catch (IOException e) {
                eofReached = true;
                Ui.diag().println("读取输入失败: " + e.getMessage());
                return null;
            }
        }

        @Override
        public boolean isInteractive() {
            return consoleAvailable && !eofReached;
        }

        @Override
        public boolean isEofReached() {
            return eofReached;
        }

        @Override
        public void close() {
            // 刻意不关闭 System.in：它被 REPL 与确认闸门共享，
            // 提前关闭会让后续读取抛 Stream closed
        }
    }
}
