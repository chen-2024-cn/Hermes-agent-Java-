package com.cyk.util;

import com.cyk.ProcessFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <h1>进程树终止（JH-SPEC-001 P0-5 R10 / P1-3 R3）</h1>
 *
 * <p>{@code Process.destroyForcibly()} 只终止直接子进程。技能脚本与终端命令拉起的
 * 常是「shell 壳 → 真实工作进程」结构，只杀壳会让孙进程变孤儿继续占 CPU 与文件句柄，
 * 用户看到的现象是「命令已超时，但机器还是卡着」。</p>
 *
 * <p>{@link ProcessTerminator} 的清理顺序是<b>先收集并强杀后代、再终止本体</b>——
 * 反过来做会让子进程在父进程死后被 init/服务控制管理器收养，
 * {@code ProcessHandle.descendants()} 随即失效，后代永久逃过清理。</p>
 *
 * <p>「后代是否真的被清干净」在 CI 上难以稳定断言（跨平台进程枚举差异 + 时序抖动），
 * 规范 P1-3 AC5 将其列为人工实测项；本测试覆盖可确定验证的部分：
 * 本体被终止、耗时受控、null 与已退出进程安全、重复调用幂等。</p>
 */
class ProcessTerminatorTest {

    @Test
    @DisplayName("存活进程被终止，且不会等到它自然结束")
    void aliveProcessIsTerminatedPromptly() throws Exception {
        Process process = startSleeper(30);
        try {
            assertThat(process.isAlive()).isTrue();

            long start = System.nanoTime();
            ProcessTerminator.destroyTree(process);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(process.isAlive()).as("本体必须已被终止").isFalse();
            assertThat(elapsedMillis)
                    .as("应立即终止，而非等待 30s 自然结束")
                    .isLessThan(6_000L);
        } finally {
            ProcessTerminator.destroyTree(process);
        }
    }

    @Test
    @DisplayName("传入 null 安全返回")
    void nullProcessIsSafe() {
        assertThatCode(() -> ProcessTerminator.destroyTree(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("对已退出的进程调用安全")
    void alreadyExitedProcessIsSafe() throws Exception {
        Process process = new ProcessBuilder(ProcessFixtures.echoStdout("done")).start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.isAlive()).isFalse();

        assertThatCode(() -> ProcessTerminator.destroyTree(process)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("重复调用幂等")
    void destroyIsIdempotent() throws Exception {
        Process process = startSleeper(30);
        try {
            ProcessTerminator.destroyTree(process);
            assertThatCode(() -> ProcessTerminator.destroyTree(process)).doesNotThrowAnyException();
            assertThat(process.isAlive()).isFalse();
        } finally {
            ProcessTerminator.destroyTree(process);
        }
    }

    private static Process startSleeper(int seconds) throws IOException {
        List<String> command = ProcessFixtures.sleeper(seconds);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        // 关闭父进程侧的输出流，避免管道缓冲写满后子进程阻塞影响终止时序
        process.getInputStream().close();
        process.getOutputStream().close();
        return process;
    }
}
