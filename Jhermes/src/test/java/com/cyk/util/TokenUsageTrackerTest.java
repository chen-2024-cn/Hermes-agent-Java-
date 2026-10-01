package com.cyk.util;

import com.cyk.bean.Usage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenUsageTracker 记账测试。
 *
 * <p>核心验证四件事：</p>
 * <ol>
 *   <li>多次 record 正确累加（对应「一次提问 = 多次模型请求」的工具调用循环）；</li>
 *   <li>脏数据防御：usage 为 null、total_tokens 缺失、负数都不会污染账目或抛异常；</li>
 *   <li>每轮 new 一个实例即自动归零（业务代码无需 reset）；</li>
 *   <li>单例 session() 并发累加不丢数（atomic 字段的实际保证）。</li>
 * </ol>
 */
class TokenUsageTrackerTest {

    private static Usage usage(int prompt, int completion, int total) {
        Usage u = new Usage();
        u.setPromptTokens(prompt);
        u.setCompletionTokens(completion);
        u.setTotalTokens(total);
        return u;
    }

    @Test
    void shouldAccumulateMultipleRequestsIntoOneTurn() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        // 模拟一次提问内的工具调用循环：工具轮 → 工具轮 → 最终回答轮
        tracker.record(usage(1000, 20, 1020));
        tracker.record(usage(1500, 30, 1530));
        tracker.record(usage(2000, 400, 2400));

        TokenUsageTracker.Snapshot s = tracker.snapshot();
        assertThat(s.promptTokens()).isEqualTo(4500);
        assertThat(s.completionTokens()).isEqualTo(450);
        assertThat(s.totalTokens()).isEqualTo(4950);
        assertThat(s.requests()).isEqualTo(3);
    }

    @Test
    void shouldIgnoreNullUsage() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.record(null);

        TokenUsageTracker.Snapshot s = tracker.snapshot();
        assertThat(s.totalTokens()).isZero();
        assertThat(s.requests()).isZero();
    }

    @Test
    void shouldBackfillTotalWhenServerOmitsIt() {
        // 部分 OpenAI 兼容层只回传分项不回传 total_tokens（或回 0）
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.record(usage(120, 34, 0));

        assertThat(tracker.snapshot().totalTokens()).isEqualTo(154);
    }

    @Test
    void shouldClampNegativeValuesToZero() {
        // 脏数据防御：负数不该把已有账目减成负值
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.record(usage(100, 10, 110));
        tracker.record(usage(-999, -999, -1));

        TokenUsageTracker.Snapshot s = tracker.snapshot();
        assertThat(s.promptTokens()).isEqualTo(100);
        assertThat(s.completionTokens()).isEqualTo(10);
        // total 为负时同样回填分项之和（此处分项被夹为 0）
        assertThat(s.totalTokens()).isEqualTo(110);
        assertThat(s.requests()).isEqualTo(2);
    }

    @Test
    void shouldStartFromZeroForEachNewInstance() {
        // 每轮新建实例 = 自动归零，这是「本轮用量」与「会话累计」区分的基础
        TokenUsageTracker first = new TokenUsageTracker();
        first.record(usage(500, 50, 550));

        TokenUsageTracker second = new TokenUsageTracker();
        second.record(usage(10, 5, 15));

        assertThat(first.snapshot().totalTokens()).isEqualTo(550);
        assertThat(second.snapshot().totalTokens()).isEqualTo(15);
    }

    @Test
    void sessionShouldBeStableSingleton() {
        assertThat(TokenUsageTracker.session()).isSameAs(TokenUsageTracker.session());
    }

    @Test
    void shouldAccumulateConcurrentlyWithoutLoss() throws InterruptedException {
        // 单例虽被单线程主循环使用，但字段用 atomic 表达了并发安全意图，此处实测该保证
        TokenUsageTracker tracker = new TokenUsageTracker();
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        tracker.record(usage(1, 1, 2));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        TokenUsageTracker.Snapshot s = tracker.snapshot();
        assertThat(s.requests()).isEqualTo(threads * perThread);
        assertThat(s.promptTokens()).isEqualTo(threads * perThread);
        assertThat(s.totalTokens()).isEqualTo(2L * threads * perThread);
    }

    @Test
    void resetShouldClearAllCounters() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.record(usage(10, 10, 20));
        tracker.reset();

        TokenUsageTracker.Snapshot s = tracker.snapshot();
        assertThat(s.promptTokens()).isZero();
        assertThat(s.completionTokens()).isZero();
        assertThat(s.totalTokens()).isZero();
        assertThat(s.requests()).isZero();
    }
}
