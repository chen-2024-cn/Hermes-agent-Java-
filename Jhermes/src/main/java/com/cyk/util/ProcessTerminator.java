package com.cyk.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * <h1>子进程树的可靠终止（JH-SPEC-001 P0-5 R10 / P1-3 R3）</h1>
 *
 * <p>{@link Process#destroyForcibly()} 只终止<b>直接子进程</b>。而在 Windows 上通过
 * {@code powershell.exe -Command ...} 或技能脚本拉起的往往是「壳 → 真实工作进程」的
 * 两层甚至多层结构：只杀壳会让真正干活的孙进程变成孤儿继续跑（占着 CPU、文件句柄、
 * 数据库连接），用户看到的现象是「命令已超时终止，但机器还是卡着、文件还被锁」。</p>
 *
 * <p>正确顺序必须是<b>先杀后代、再杀父进程</b>：反过来会让子进程在父进程死亡后被
 * init/服务控制管理器收养，{@link ProcessHandle#descendants()} 随即失效，孙进程永久逃过清理
 * （规范 P1-3 R3 明确要求此顺序）。</p>
 *
 * <p>抽出独立工具类而非各自实现：技能脚本超时（P0-5 R10）与终端命令中断/超时（P1-3 R3）
 * 都需要同一段逻辑，重复实现必然出现「一处修了、另一处漏了」的防护不齐问题
 * （与 {@code SensitivePathGuard} 抽出共享黑名单是同一条工程理由）。</p>
 */
public final class ProcessTerminator {

    private static final Logger logger = LoggerFactory.getLogger(ProcessTerminator.class);

    /** 等待进程真正退出的宽限时长，避免调用方无限期阻塞在清理阶段。 */
    private static final long GRACE_SECONDS = 5;

    private ProcessTerminator() {
        // 工具类禁止实例化
    }

    /**
     * 终止整个进程树：先后代、再本体，各自强杀并等待退出。
     *
     * <p>任何一步失败都不中断后续清理（某个孙进程可能已自行退出），
     * 因为「清理尽可能多的进程」比「精确报告哪一个没杀掉」更重要。</p>
     *
     * @param process 待终止的进程；null 安全
     */
    public static void destroyTree(Process process) {
        if (process == null) {
            return;
        }
        long pid = pidOf(process);
        int killed = 0;

        // ① 先收集后代快照再动手：边杀边遍历会因进程消失导致流失效
        try (Stream<ProcessHandle> descendants = process.descendants()) {
            for (ProcessHandle handle : descendants.toList()) {
                if (forceKill(handle)) {
                    killed++;
                }
            }
        } catch (RuntimeException e) {
            logger.debug("枚举子进程失败（pid={}）: {}", pid, e.getMessage());
        }

        // ② 再终止本体
        process.destroyForcibly();
        waitForExit(process, pid);

        logger.debug("已终止进程树 pid={}，强制清理后代 {} 个", pid, killed);
    }

    /** 强杀单个进程句柄；已退出的进程返回 false（不算失败）。 */
    private static boolean forceKill(ProcessHandle handle) {
        try {
            if (!handle.isAlive()) {
                return false;
            }
            handle.destroyForcibly();
            // 等待确实退出：不等待的强杀在 Windows 上可能只是发出请求
            handle.onExit().get(GRACE_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.debug("终止子进程 pid={} 失败: {}", safePid(handle), e.getMessage());
            return false;
        }
    }

    /** 给本体进程一段宽限期等待退出，超时仅记日志（不再递归，避免清理阶段无限拉长）。 */
    private static void waitForExit(Process process, long pid) {
        try {
            if (!process.waitFor(GRACE_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("进程 pid={} 在 {}s 内未退出，已发出强杀请求", pid, GRACE_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long pidOf(Process process) {
        try {
            return process.pid();
        } catch (UnsupportedOperationException e) {
            return -1L;
        }
    }

    private static long safePid(ProcessHandle handle) {
        try {
            return handle.pid();
        } catch (RuntimeException e) {
            return -1L;
        }
    }
}
