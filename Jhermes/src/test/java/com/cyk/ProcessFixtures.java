package com.cyk;

import java.util.List;

/**
 * <h1>进程类测试的可移植命令夹具</h1>
 *
 * <p>P0-5 R10 的超时验证需要「一个会长时间不退出的子进程」，而 Windows 与 Linux/macOS
 * 的可用命令完全不同。CI 是 windows + ubuntu 双平台矩阵（P0-1 R1），因此统一在此按
 * 操作系统生成等价命令，各测试不必各自重复 OS 分支。</p>
 *
 * <p>刻意选择系统自带命令而非引入测试依赖：Windows 用 {@code ping -n} 计时
 * （比 {@code timeout /t} 可靠，后者在无控制台输入时会失败），Unix 用 {@code sleep}。</p>
 */
public final class ProcessFixtures {

    /** 当前是否 Windows 平台。 */
    public static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win");

    private ProcessFixtures() {
    }

    /**
     * 构造「持续 seconds 秒才退出」的命令。
     *
     * @param seconds 期望存活秒数（Windows 的 ping -n 为发送次数，耗时约 (n-1) 秒）
     */
    public static List<String> sleeper(int seconds) {
        return WINDOWS
                ? List.of("cmd", "/c", "ping", "-n", String.valueOf(seconds + 1), "127.0.0.1")
                : List.of("sleep", String.valueOf(seconds));
    }

    /** 构造「向 stdout 输出一行」的命令。 */
    public static List<String> echoStdout(String text) {
        return WINDOWS
                ? List.of("cmd", "/c", "echo " + text)
                : List.of("sh", "-c", "echo " + text);
    }

    /** 构造「向 stderr 输出一行并以非零码退出」的命令。 */
    public static List<String> echoStderrThenFail(String text, int exitCode) {
        return WINDOWS
                ? List.of("cmd", "/c", "echo " + text + " 1>&2 & exit /b " + exitCode)
                : List.of("sh", "-c", "echo " + text + " >&2; exit " + exitCode);
    }
}
