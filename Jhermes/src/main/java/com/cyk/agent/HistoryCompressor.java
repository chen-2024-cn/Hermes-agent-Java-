package com.cyk.agent;

import com.cyk.bean.ModelMessage;
import com.cyk.prompt.ComposedPrompt;
import com.cyk.prompt.ContextReport;
import com.cyk.util.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * <h1>历史压缩器（JH-SPEC-001 P0-4 R7）</h1>
 *
 * <h2>要解决的问题</h2>
 * <p>Agent 每一轮都会把"全部历史"重新发给模型。其中最占地方的是<b>工具结果</b>：
 * 读一个文件、跑一条命令，返回的内容动辄几千甚至上万字符（单条上限 12,000）。
 * 这些内容留在历史里，之后每一轮都要重复发送、重复计费，对话越长越贵，最后还会撑爆上下文窗口。</p>
 *
 * <h2>怎么压缩</h2>
 * <p>把<b>旧的</b>工具结果的内容，换成一行很短的占位说明。其余一切保持不变。</p>
 * <pre>
 *   压缩前:  tool 消息 { tool_call_id: "c1", content: "（一万两千个字符的文件内容……）" }
 *   压缩后:  tool 消息 { tool_call_id: "c1", content: "[工具结果已折叠：原 12000 字符……]" }
 * </pre>
 *
 * <h2>三条设计原则（每一条都有原因）</h2>
 * <ol>
 *   <li><b>只改"发给模型的副本"，不改真实历史。</b>
 *       真实的 {@code conversationHistory} 用于持久化会话、{@code /context} 诊断和 {@code /retry} 回滚。
 *       如果压缩器有 bug 直接改了它，磁盘上的会话就被破坏了；而副本出 bug，最多影响这一次请求。</li>
 *   <li><b>只折叠 tool 消息的内容，一条消息都不删。</b>
 *       模型接口要求：assistant 消息里的每个 {@code tool_calls}，后面必须有对应 {@code tool_call_id}
 *       的 tool 消息，否则服务端直接报 400。我们不删消息、不改 id，只换内容，
 *       所以"配对"从结构上就不可能被破坏，这叫"构造性保证"。</li>
 *   <li><b>滞回触发，保护前缀缓存。</b>见下一节。</li>
 * </ol>
 *
 * <h2>为什么需要"滞回"，以及为什么本类必须有状态</h2>
 * <p>服务商的前缀缓存是按字节比较请求开头的：开头一个字节变了，后面的缓存全部作废。</p>
 * <p>设想一个"没有记忆"的压缩器：每一轮都拿完整的真实历史重新估算，超过阈值就从最老的开始折叠，
 * 折叠到阈值以下为止。真实历史每轮都在变长，所以每一轮都要比上一轮<b>多折叠几条</b>——
 * 折叠边界每轮都往前挪，边界之后那一大段"没折叠的历史"每轮的字节都不同，缓存永远命不中。</p>
 * <p>正确的做法是<b>滞回</b>（像空调：高于 28° 才开机，一开就降到 24° 再停，而不是 28° 附近反复开关）：</p>
 * <ul>
 *   <li><b>触发线</b>（窗口的 50%）：压缩后视图的估算超过它，才推进折叠边界；</li>
 *   <li><b>目标线</b>（窗口的 30%）：一旦推进，就一口气折叠到视图估算低于它为止；</li>
 *   <li><b>记住边界</b>：之后每一轮先原样应用上次的边界（同样的消息折成同样的占位文字，字节不变），
 *       只要压缩后的视图还没长回触发线，就不再推进。这中间有很多轮，前缀完全稳定，缓存正常工作。</li>
 * </ul>
 * <p>所以本类有一个成员变量 {@link #foldBoundary}，由 Agent 持有同一个实例贯穿整个会话。</p>
 *
 * <p>折叠是<b>确定性</b>的：占位文字只由原内容的长度决定，同一条消息每次折叠结果逐字节相同。</p>
 */
public final class HistoryCompressor {

    /** 触发线：压缩后视图的估算超过窗口的这个比例，才推进折叠边界。 */
    static final double TRIGGER_RATIO = 0.50;

    /** 目标线：推进折叠边界时，折叠到视图估算低于窗口的这个比例为止。 */
    static final double TARGET_RATIO = 0.30;

    /** 保留最近几个回合组不动（一个回合组 = 从一条 user 消息到下一条 user 消息之前）。 */
    static final int KEEP_RECENT_TURNS = 3;

    /**
     * 太短的工具结果不值得折叠：占位文字本身就有几十个字符，折叠一条 50 字符的结果反而可能更长。
     * 低于这个长度的原样保留。
     */
    static final int MIN_FOLD_CHARS = 200;

    private static final String FOLD_PREFIX = "[工具结果已折叠";

    /**
     * 折叠边界：历史中下标小于它的 tool 消息（足够长的）都会被折叠。
     * 只增不减（除非历史被回滚变短，见 {@link #compress}），这就是"记住"。
     */
    private int foldBoundary = 0;

    /**
     * 压缩结果。
     *
     * @param messages    要发给模型的消息列表。没有压缩时就是传入的那个对象本身（零拷贝）
     * @param foldedCount 被折叠的 tool 消息条数；0 表示没有压缩
     * @param savedChars  折叠节省的字符数（原长度减占位文字长度之和）
     */
    public record Result(List<ModelMessage> messages, int foldedCount, int savedChars) {

        /** 本次是否真的压缩了。 */
        public boolean compressed() {
            return foldedCount > 0;
        }

        static Result untouched(List<ModelMessage> original) {
            return new Result(original, 0, 0);
        }
    }

    /** 当前折叠边界（测试与诊断用）。 */
    int foldBoundary() {
        return foldBoundary;
    }

    /**
     * 对历史做压缩，返回"发给模型的视图"。
     *
     * @param history      真实对话历史（本方法<b>绝不修改</b>它，也不修改其中的任何消息对象）
     * @param prompt       系统提示词装配结果（只用于估算总体积）
     * @param toolsChars   tools JSON 的字符数（只用于估算总体积）
     * @param windowTokens 上下文窗口大小（token）
     * @return 压缩结果；不需要压缩时 {@link Result#messages()} 就是 {@code history} 本身
     */
    public Result compress(List<ModelMessage> history, ComposedPrompt prompt,
                           int toolsChars, int windowTokens) {
        if (history == null || history.isEmpty() || windowTokens <= 0) {
            return Result.untouched(history);
        }

        int protectedFrom = indexWhereRecentTurnsStart(history, KEEP_RECENT_TURNS);
        // 边界不能越过受保护区，也不能超过历史长度：
        // 回合失败被回滚时历史会变短，旧边界可能已经指到不存在的位置，这里统一收回来
        foldBoundary = Math.min(foldBoundary, protectedFrom);

        // 第一步：先把"上次已经决定折叠的部分"原样应用一遍（同样的消息 → 同样的占位文字）。
        // 这一步没有任何判断，只是重放，所以只要边界不动，前缀字节就不动。
        List<ModelMessage> view = new ArrayList<>(history);
        int currentTokens = ContextReport.estimateRequestTokens(prompt, toolsChars, history);
        int folded = 0;
        int savedChars = 0;
        for (int i = 0; i < foldBoundary; i++) {
            ModelMessage message = view.get(i);
            if (!isFoldable(message)) {
                continue;
            }
            ModelMessage placeholder = foldedCopy(message);
            int before = message.getContent().length();
            int after = placeholder.getContent().length();
            view.set(i, placeholder);
            folded++;
            savedChars += before - after;
            currentTokens -= TokenEstimator.fromChars(before) - TokenEstimator.fromChars(after);
        }

        // 第二步：判断要不要推进边界。注意比较的是"重放之后"的估算，不是真实历史的估算——
        // 否则真实历史永远比视图大，会每一轮都触发推进，滞回就失效了。
        int triggerTokens = (int) (windowTokens * TRIGGER_RATIO);
        if (currentTokens > triggerTokens) {
            int targetTokens = (int) (windowTokens * TARGET_RATIO);
            int i = foldBoundary;
            while (i < protectedFrom && currentTokens > targetTokens) {
                ModelMessage message = view.get(i);
                if (isFoldable(message)) {
                    ModelMessage placeholder = foldedCopy(message);
                    int before = message.getContent().length();
                    int after = placeholder.getContent().length();
                    view.set(i, placeholder);
                    folded++;
                    savedChars += before - after;
                    currentTokens -= TokenEstimator.fromChars(before) - TokenEstimator.fromChars(after);
                }
                i++;
            }
            // 边界停在"处理到哪里"：之后每一轮都会重放到这里
            foldBoundary = i;
        }

        return folded == 0 ? Result.untouched(history) : new Result(view, folded, savedChars);
    }

    /**
     * 找到"最近 N 个回合组"从哪个下标开始。下标之前的消息才允许被折叠。
     *
     * <p>做法：从后往前数 user 消息，数到第 N 个，它的位置就是受保护区的起点。
     * 如果整段历史里 user 消息不足 N 条，说明整个历史都属于"最近"，返回 0（全部受保护，什么都不折叠）。</p>
     */
    static int indexWhereRecentTurnsStart(List<ModelMessage> history, int keepTurns) {
        int seen = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).getRole())) {
                seen++;
                if (seen == keepTurns) {
                    return i;
                }
            }
        }
        return 0;
    }

    /** 这条消息能不能折叠：必须是 tool 消息，有内容，内容足够长，并且还不是占位文字。 */
    private static boolean isFoldable(ModelMessage message) {
        return "tool".equals(message.getRole())
                && message.getContent() != null
                && message.getContent().length() >= MIN_FOLD_CHARS
                && !message.getContent().startsWith(FOLD_PREFIX);
    }

    /**
     * 生成折叠后的新消息：<b>新建对象</b>，保留 role / tool_call_id / name，只替换 content。
     *
     * <p>必须是新对象而不能直接 {@code setContent}：原对象还在真实历史里，改了它就等于改了真实历史，
     * 违反"只改副本"的原则。</p>
     *
     * <p>占位文字里写明原长度，并提示"需要的话重新调用工具"，
     * 这样模型看到后知道这里原本有内容、该怎么办，而不是误以为工具返回了空结果。
     * 这段文字只由原长度决定，所以同一条消息每次折叠结果逐字节相同（缓存友好）。</p>
     */
    private static ModelMessage foldedCopy(ModelMessage original) {
        ModelMessage copy = ModelMessage.tool(
                FOLD_PREFIX + "：原 " + original.getContent().length()
                        + " 字符。内容已从上下文移除以节省空间，如仍需要请重新调用对应工具]",
                original.getToolCallId());
        copy.setName(original.getName());
        return copy;
    }
}
