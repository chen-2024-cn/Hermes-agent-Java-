package com.cyk.util;

import com.cyk.bean.Usage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * token 用量统计器（数据源 = API 响应 {@code usage} 字段的服务端真实计费口径，
 * <b>不做本地 tokenizer 估算</b>，保证展示数值与服务商账单一致）。
 *
 * <h2>两种生命周期</h2>
 * <ol>
 *   <li><b>当轮小计</b>：一次用户提问在 Agent 内部可能触发<b>多次</b>模型请求
 *       （模型选择调工具 → 执行 → 结果回传 → 再次请求，直至给出最终回复）。
 *       当轮用量 = 这一串请求的 usage 之和。调用方每轮 new 一个实例，
 *       天然实现「轮末显示、下轮自动归零」，无需手动 reset。</li>
 *   <li><b>会话累计</b>：进程级单例 {@link #session()} 跨轮累加，
 *       供「会话累计」展示。resume 恢复的旧会话不含 usage 数据，
 *       不计入累计——宁缺毋假，绝不展示估算值。</li>
 * </ol>
 *
 * <p>对话主循环虽是单线程，但单例跨对象共享，字段一律用 atomic
 * 表达「可安全并发累加」的意图。渲染（千分位格式、配色）不在本类，
 * 由 {@link ConsoleUi} 负责——<b>记账与呈现分离</b>，两者都可独立演进。</p>
 */
public final class TokenUsageTracker {

    /** 会话级全局累计器（进程单例）。 */
    private static final TokenUsageTracker SESSION = new TokenUsageTracker();

    private final AtomicLong prompt = new AtomicLong();
    private final AtomicLong completion = new AtomicLong();
    private final AtomicLong total = new AtomicLong();
    /** 累计的模型请求次数（一次用户提问的工具调用循环通常 &gt; 1 次请求）。 */
    private final AtomicInteger requests = new AtomicInteger();

    /** 会话级累计器。 */
    public static TokenUsageTracker session() {
        return SESSION;
    }

    /**
     * 记录一次 API 响应的 usage。
     *
     * <p>防御两类脏数据，任何情况都不抛出（用量统计绝不能破坏对话主流程）：</p>
     * <ul>
     *   <li>{@code usage == null}：流式下服务端未回传带 include_usage 的尾 chunk，
     *       静默跳过；</li>
     *   <li>{@code total_tokens} 缺失或为 0 而分项有值：个别 OpenAI 兼容层实现不完整，
     *       回填 prompt + completion 之和，让展示口径自洽。</li>
     * </ul>
     */
    public void record(Usage usage) {
        if (usage == null) {
            return;
        }
        long p = Math.max(0, usage.getPromptTokens());
        long c = Math.max(0, usage.getCompletionTokens());
        long t = usage.getTotalTokens() > 0 ? usage.getTotalTokens() : p + c;
        prompt.addAndGet(p);
        completion.addAndGet(c);
        total.addAndGet(t);
        requests.incrementAndGet();
    }

    /** 当前累计的不可变快照（读侧不会看到累加中途的撕裂值）。 */
    public Snapshot snapshot() {
        return new Snapshot(prompt.get(), completion.get(), total.get(), requests.get());
    }

    /** 清零。仅测试隔离使用；业务侧当轮小计靠新建实例归零。 */
    public void reset() {
        prompt.set(0);
        completion.set(0);
        total.set(0);
        requests.set(0);
    }

    /**
     * 不可变用量快照。
     *
     * @param promptTokens     输入（prompt）token 数
     * @param completionTokens 输出（completion）token 数
     * @param totalTokens      总计 token 数
     * @param requests         产生该用量的模型请求次数
     */
    public record Snapshot(long promptTokens, long completionTokens, long totalTokens, int requests) {
    }
}
