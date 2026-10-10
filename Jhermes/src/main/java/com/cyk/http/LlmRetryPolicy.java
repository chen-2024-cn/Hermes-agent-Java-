package com.cyk.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * <h1>LLM 调用的指数退避重试策略（JH-SPEC-001 P0-5 R3）</h1>
 *
 * <p>把「哪些错误值得重试、退避多久、重试前要不要告知用户」从 {@code Agent} 的
 * 对话循环里抽出来，成为可独立单测的协作者：</p>
 *
 * <ul>
 *   <li><b>分类依据</b> {@link LlmTransportException#isRetryable()}：
 *       429/5xx/超时/连接重置才重试；401/403/404/400 立刻抛出——凭据与请求体错误
 *       重试一万次结果相同，还会放大风控风险与用户等待。</li>
 *   <li><b>退避曲线</b> 500ms → 1s → 2s（{@code base * 2^(attempt-1)}）。
 *       首次失败后立即重试对限流场景毫无意义，反而可能延长封禁。</li>
 *   <li><b>UI 通知</b> 经 {@link RetryNotifier} 回调而非直接打印：本类保持在传输层，
 *       不持有 stdout（P1-1 将禁止 {@code com.cyk.ui} 之外的类访问 System.out）。</li>
 *   <li><b>可测性</b> {@link Sleeper} 可注入假实现，单测无需真的睡 3.5 秒。</li>
 * </ul>
 *
 * <p>重试次数口径：{@link #MAX_RETRIES}=3 指<b>首次调用之外的重试上限</b>，
 * 故一次请求最多尝试 4 次，最坏附加延迟 500+1000+2000 = 3.5s（CC-8 启动预算不受影响，
 * 该延迟只发生在真实失败路径）。</p>
 */
public final class LlmRetryPolicy {

    private static final Logger logger = LoggerFactory.getLogger(LlmRetryPolicy.class);

    /** 首次调用之外的最大重试次数（总尝试次数 = MAX_RETRIES + 1）。 */
    public static final int MAX_RETRIES = 3;

    /** 退避基数（毫秒）：第 n 次重试等待 base * 2^(n-1)。 */
    static final long BASE_BACKOFF_MILLIS = 500L;

    /** 退避上限，防止重试次数被调大后单次等待失控。 */
    static final long MAX_BACKOFF_MILLIS = 8_000L;

    /**
     * 重试前通知用户。
     *
     * <p>实现方（Agent）负责走 UI 通道打印一行提示，例如
     * {@code ⚠ 模型服务限流(HTTP 429)，1s 后重试(1/3)...}——
     * 让用户知道程序没卡死，且明白在等什么。</p>
     */
    public interface RetryNotifier {
        void onRetry(int attempt, int maxRetries, long backoffMillis, LlmTransportException error);
    }

    /** 休眠抽象：生产为 {@code Thread.sleep}，单测注入空实现以消除等待。 */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final Sleeper THREAD_SLEEP = Thread::sleep;

    private final RetryNotifier notifier;
    private final Sleeper sleeper;

    /** 生产构造：真实休眠 + 调用方提供的通知回调（可为 null 表示静默重试）。 */
    public LlmRetryPolicy(RetryNotifier notifier) {
        this(notifier, THREAD_SLEEP);
    }

    /** 测试构造：注入假 Sleeper，断言退避曲线而无需真实等待。 */
    LlmRetryPolicy(RetryNotifier notifier, Sleeper sleeper) {
        this.notifier = notifier;
        this.sleeper = sleeper;
    }

    /**
     * 执行一次 LLM 调用，按策略重试。
     *
     * @param action 真正发起请求的调用（通常封装 {@link LlmClient} 的一次 chat 调用）
     * @param <T>    响应类型
     * @return 成功响应
     * @throws LlmTransportException 不可重试错误，或可重试错误在重试耗尽后最后一次抛出
     */
    public <T> T call(Supplier<T> action) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (LlmTransportException e) {
                attempt++;
                if (!e.isRetryable() || attempt > MAX_RETRIES) {
                    // 不可重试，或已耗尽重试次数：交给上层做回合级错误边界处理
                    if (attempt > MAX_RETRIES) {
                        logger.warn("模型调用重试 {} 次后仍失败: {}", MAX_RETRIES, e.getMessage());
                    }
                    throw e;
                }
                long backoff = backoffMillis(attempt);
                logger.warn("模型调用失败（{}），{}ms 后第 {}/{} 次重试",
                        e.getMessage(), backoff, attempt, MAX_RETRIES);
                if (notifier != null) {
                    notifier.onRetry(attempt, MAX_RETRIES, backoff, e);
                }
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException ie) {
                    // 用户 Ctrl+C 中断退避：恢复中断标记并立即放弃重试，不吞掉中断语义
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** 第 attempt 次重试的退避时长（指数增长，封顶 {@link #MAX_BACKOFF_MILLIS}）。 */
    static long backoffMillis(int attempt) {
        long backoff = BASE_BACKOFF_MILLIS * (1L << (attempt - 1));
        return Math.min(backoff, MAX_BACKOFF_MILLIS);
    }
}
