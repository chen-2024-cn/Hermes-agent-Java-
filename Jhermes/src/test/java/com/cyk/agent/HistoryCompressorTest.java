package com.cyk.agent;

import com.cyk.bean.Function;
import com.cyk.bean.Message;
import com.cyk.bean.ModelMessage;
import com.cyk.bean.Session;
import com.cyk.bean.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HistoryCompressor} 测试（JH-SPEC-001 P0-4 R7 / AC5）。
 *
 * <h2>测试用的"窗口"怎么选（这个数字很关键）</h2>
 * <p>真实窗口是 65536 token，要构造这么大的历史很费事，所以测试里缩小窗口。
 * 但不能缩得太小：压缩器永远保留最近 3 个回合组不折叠，如果这 3 个回合组本身的体积
 * 就超过触发线，那么"折叠后视图仍高于触发线"，边界每轮都会被迫推进，滞回就测不出来了。</p>
 * <p>每个回合组约 1031 字符 ≈ 413 token，最近 3 个就是约 1240 token。所以窗口取
 * <b>4000 token</b>：触发线 = 2000，目标线 = 1200。这样 8 个回合组（约 3300 token）会触发压缩，
 * 折叠 5 个旧回合组后视图约 1400 token，低于触发线，滞回才能被观察到。
 * 系统提示词传 null、tools 字符数传 0，让总量只由历史决定，数字好算。</p>
 *
 * <h2>历史的构造方式</h2>
 * <p>{@link #turn} 造一个回合组：user → assistant(带 tool_calls) → tool(大结果) → assistant(最终回复)。
 * 多个回合组拼起来，就是一段典型的"多次读文件"的长对话。</p>
 */
class HistoryCompressorTest {

    private static final int WINDOW = 4000;
    private static final int BIG = 1000; // 一条大工具结果的字符数

    /** 造一个回合组，tool 结果是 BIG 个字符。 */
    private static List<ModelMessage> turn(int n, int toolChars) {
        String callId = "call-" + n;

        Function function = new Function();
        function.setName("read_file");
        function.setArguments("{\"path\":\"f" + n + ".txt\"}");
        ToolCall call = new ToolCall();
        call.setId(callId);
        call.setType("function");
        call.setFunction(function);

        ModelMessage assistantCall = new ModelMessage();
        assistantCall.setRole("assistant");
        assistantCall.setContent("");
        assistantCall.setToolCalls(List.of(call));

        List<ModelMessage> messages = new ArrayList<>();
        messages.add(ModelMessage.user("问题" + n));
        messages.add(assistantCall);
        messages.add(ModelMessage.tool("x".repeat(toolChars), callId));
        messages.add(ModelMessage.assistant("回答" + n));
        return messages;
    }

    /** 把前 count 个回合组拼成一段历史。 */
    private static List<ModelMessage> history(int count, int toolChars) {
        List<ModelMessage> all = new ArrayList<>();
        for (int n = 1; n <= count; n++) {
            all.addAll(turn(n, toolChars));
        }
        return all;
    }

    private static HistoryCompressor.Result compress(HistoryCompressor compressor, List<ModelMessage> history) {
        return compressor.compress(history, null, 0, WINDOW);
    }

    private static boolean isFolded(ModelMessage message) {
        return message.getContent() != null && message.getContent().startsWith("[工具结果已折叠");
    }

    /** 把压缩后的 ModelMessage 列表还原成 Session 的消息，借用现有的"配对校验"逻辑检查结构合法性。 */
    private static List<String> pairingProblems(List<ModelMessage> messages) {
        Session session = new Session("pairing-check");
        session.setMaxMessages(Integer.MAX_VALUE);
        for (ModelMessage m : messages) {
            session.messages.add(Message.from(m));
        }
        return session.findUnpairedToolMessages();
    }

    @Test
    @DisplayName("没到触发线：原样返回同一个列表对象，什么都不折叠")
    void belowTriggerShouldReturnSameListUntouched() {
        // 1 个回合组 ≈ 1031 字符 ≈ 413 token，远低于触发线 2000
        List<ModelMessage> history = history(1, BIG);

        HistoryCompressor.Result result = compress(new HistoryCompressor(), history);

        assertThat(result.compressed()).isFalse();
        assertThat(result.messages()).as("未压缩时零拷贝，返回的就是原列表").isSameAs(history);
    }

    @Test
    @DisplayName("超过触发线：折叠旧的 tool 结果，保留最近 3 个回合组")
    void aboveTriggerShouldFoldOldToolResultsAndKeepRecentTurns() {
        List<ModelMessage> history = history(6, BIG);

        HistoryCompressor.Result result = compress(new HistoryCompressor(), history);

        assertThat(result.compressed()).isTrue();
        // 6 个回合组 → 前 3 个可折叠，后 3 个受保护
        List<ModelMessage> view = result.messages();
        for (int n = 1; n <= 6; n++) {
            ModelMessage tool = view.get((n - 1) * 4 + 2);
            if (n <= 3) {
                assertThat(isFolded(tool)).as("第 %d 个回合组较旧，应被折叠", n).isTrue();
            } else {
                assertThat(isFolded(tool)).as("第 %d 个回合组属于最近 3 个，必须保留", n).isFalse();
                assertThat(tool.getContent()).hasSize(BIG);
            }
        }
    }

    @Test
    @DisplayName("构造性配对保证（AC5）：压缩后消息数不变，tool_calls 与 tool 仍严格配对")
    void pairingMustSurviveCompression() {
        List<ModelMessage> history = history(8, BIG);

        HistoryCompressor.Result result = compress(new HistoryCompressor(), history);

        assertThat(result.compressed()).isTrue();
        assertThat(result.messages()).as("只换内容，一条消息都不删").hasSameSizeAs(history);
        assertThat(pairingProblems(result.messages())).as("压缩后不得出现孤立或悬空的工具消息").isEmpty();
        // 每条被折叠的 tool 消息，tool_call_id 仍和原来一致
        for (int i = 0; i < history.size(); i++) {
            assertThat(result.messages().get(i).getRole()).isEqualTo(history.get(i).getRole());
            assertThat(result.messages().get(i).getToolCallId()).isEqualTo(history.get(i).getToolCallId());
        }
    }

    @Test
    @DisplayName("不修改真实历史：原列表和其中的消息对象都原封不动")
    void mustNotMutateTheRealHistory() {
        List<ModelMessage> history = history(8, BIG);
        List<ModelMessage> snapshotRefs = new ArrayList<>(history);
        List<String> snapshotContents = history.stream().map(ModelMessage::getContent).toList();

        compress(new HistoryCompressor(), history);

        assertThat(history).as("列表里的对象引用不变").containsExactlyElementsOf(snapshotRefs);
        assertThat(history.stream().map(ModelMessage::getContent).toList())
                .as("每条消息的内容不变").isEqualTo(snapshotContents);
    }

    @Test
    @DisplayName("滞回（保护缓存）：折叠一次后，历史再增长也不再推进边界，前缀字节保持不变")
    void hysteresisShouldKeepPrefixStableAcrossTurns() {
        HistoryCompressor compressor = new HistoryCompressor();
        List<ModelMessage> history = history(8, BIG);

        HistoryCompressor.Result first = compress(compressor, history);
        int boundaryAfterFirst = compressor.foldBoundary();
        List<String> prefixAfterFirst = first.messages().subList(0, boundaryAfterFirst).stream()
                .map(ModelMessage::getContent).toList();
        assertThat(first.compressed()).isTrue();

        // 对话继续：再来一个小回合组（工具结果很短，不会让视图越过触发线）
        history.addAll(turn(9, 50));
        HistoryCompressor.Result second = compress(compressor, history);

        assertThat(compressor.foldBoundary()).as("视图没长回触发线，边界不应推进").isEqualTo(boundaryAfterFirst);
        List<String> prefixAfterSecond = second.messages().subList(0, boundaryAfterFirst).stream()
                .map(ModelMessage::getContent).toList();
        assertThat(prefixAfterSecond).as("边界之前的内容逐字节不变（缓存可命中）").isEqualTo(prefixAfterFirst);
    }

    @Test
    @DisplayName("滞回：折叠后视图长回触发线以上时，才再次推进边界")
    void boundaryShouldAdvanceOnlyWhenViewGrowsBackAboveTrigger() {
        HistoryCompressor compressor = new HistoryCompressor();
        List<ModelMessage> history = history(8, BIG);
        compress(compressor, history);
        int boundaryBefore = compressor.foldBoundary();

        // 连续追加几个带大结果的回合组，让压缩后的视图重新变大
        for (int n = 9; n <= 14; n++) {
            history.addAll(turn(n, BIG));
        }
        HistoryCompressor.Result result = compress(compressor, history);

        assertThat(compressor.foldBoundary()).as("视图重新越过触发线，边界应向前推进")
                .isGreaterThan(boundaryBefore);
        assertThat(result.compressed()).isTrue();
        assertThat(pairingProblems(result.messages())).isEmpty();
    }

    @Test
    @DisplayName("确定性：同一份历史两次压缩，结果逐条相同")
    void compressionShouldBeDeterministic() {
        List<ModelMessage> history = history(8, BIG);

        List<String> a = compress(new HistoryCompressor(), history).messages().stream()
                .map(ModelMessage::getContent).toList();
        List<String> b = compress(new HistoryCompressor(), history).messages().stream()
                .map(ModelMessage::getContent).toList();

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("太短的工具结果不折叠；已折叠的占位文字不会被再次折叠")
    void shortResultsAndPlaceholdersShouldBeLeftAlone() {
        List<ModelMessage> history = new ArrayList<>();
        history.addAll(turn(1, 50));      // 第 1 个回合组：工具结果太短，不值得折叠
        for (int n = 2; n <= 8; n++) {
            history.addAll(turn(n, BIG)); // 第 2~8 个回合组：大结果，把总量顶过触发线（id 各不相同）
        }

        HistoryCompressor compressor = new HistoryCompressor();
        HistoryCompressor.Result first = compress(compressor, history);

        // 第一个回合组的 tool 结果只有 50 字符：保持原样
        assertThat(first.messages().get(2).getContent()).hasSize(50);

        // 再压缩一次：占位文字不会被"折叠的折叠"（长度不会变化、前缀仍是同一段文字）
        List<String> firstContents = first.messages().stream().map(ModelMessage::getContent).toList();
        List<String> again = compress(compressor, history).messages().stream()
                .map(ModelMessage::getContent).toList();
        assertThat(again).isEqualTo(firstContents);
    }

    @Test
    @DisplayName("历史里 user 消息不足 3 条：全部属于最近回合组，什么都不折叠")
    void fewerThanKeepTurnsShouldFoldNothing() {
        // 只有 2 个回合组，但每个的工具结果都很大，总量远超触发线
        List<ModelMessage> history = history(2, 5000);

        HistoryCompressor.Result result = compress(new HistoryCompressor(), history);

        assertThat(result.compressed()).isFalse();
        assertThat(result.messages()).isSameAs(history);
    }

    @Test
    @DisplayName("回合失败回滚使历史变短：旧边界自动收回，不越界、不抛异常")
    void shrinkingHistoryShouldPullBoundaryBack() {
        HistoryCompressor compressor = new HistoryCompressor();
        List<ModelMessage> history = history(10, BIG);
        compress(compressor, history);
        assertThat(compressor.foldBoundary()).isGreaterThan(0);

        // 模拟回滚：历史被截短到只剩 2 个回合组
        List<ModelMessage> rolledBack = new ArrayList<>(history.subList(0, 8));
        HistoryCompressor.Result result = compress(compressor, rolledBack);

        assertThat(compressor.foldBoundary()).isZero();
        assertThat(result.compressed()).isFalse();
    }

    @Test
    @DisplayName("折叠节省统计：savedChars 等于每条原长度减占位文字长度之和")
    void savedCharsShouldMatchActualDifference() {
        List<ModelMessage> history = history(8, BIG);

        HistoryCompressor.Result result = compress(new HistoryCompressor(), history);

        int actual = 0;
        for (int i = 0; i < history.size(); i++) {
            actual += history.get(i).getContent().length() - result.messages().get(i).getContent().length();
        }
        assertThat(result.savedChars()).isEqualTo(actual).isPositive();
    }

    @Test
    @DisplayName("占位文字说明了原长度，并提示可重新调用工具")
    void placeholderShouldExplainWhatHappened() {
        List<ModelMessage> history = history(8, BIG);

        HistoryCompressor.Result result = compress(new HistoryCompressor(), history);

        String placeholder = result.messages().get(2).getContent();
        assertThat(placeholder).contains("原 1000 字符").contains("重新调用");
    }

    @Test
    @DisplayName("边界输入：null、空历史、非法窗口都安全返回，不抛异常")
    void degenerateInputsShouldBeSafe() {
        HistoryCompressor compressor = new HistoryCompressor();

        assertThat(compressor.compress(null, null, 0, WINDOW).compressed()).isFalse();
        assertThat(compressor.compress(List.of(), null, 0, WINDOW).compressed()).isFalse();
        assertThat(compressor.compress(history(8, BIG), null, 0, 0).compressed()).isFalse();
        assertThat(compressor.compress(history(8, BIG), null, 0, -5).compressed()).isFalse();
    }
}
