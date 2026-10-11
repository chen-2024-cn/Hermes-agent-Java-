package com.cyk.prompt;

import java.util.List;
import java.util.Optional;

/**
 * <h1>提示词装配结果（JH-SPEC-001 P0-3 R1）</h1>
 *
 * <p>不只是拼好的字符串：携带<b>逐段装配明细</b>（注入了哪些段、各占多少字符、
 * 跳过了哪些段、为什么跳过）。期 3 就把明细建出来是有意的——P0-4 的 {@code /context}
 * 诊断命令与预算守卫直接消费本结构，届时零返工。</p>
 *
 * <p>典型消费：</p>
 * <ul>
 *   <li>{@code text()} → 装入对话历史首位的 system 消息；</li>
 *   <li>{@code segments()} → /context 逐段明细表（id、字符数、注入/跳过及原因）；</li>
 *   <li>{@code totalChars()} → 预算实测（AC2 的字符侧证据；token 侧在 P0-4 用 prompt_tokens 复核）。</li>
 * </ul>
 *
 * @param text     完整系统提示词（已 strip，段间以空行分隔）
 * @param segments 逐段装配明细（有序，含跳过段；不可变）
 */
public record ComposedPrompt(String text, List<SegmentRender> segments) {

    public ComposedPrompt {
        text = text == null ? "" : text;
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /** 完整提示词字符数（预算口径；P0-4 换算 token 用）。 */
    public int totalChars() {
        return text.length();
    }

    /** 按 id 查某段的装配明细。 */
    public Optional<SegmentRender> segment(String id) {
        return segments.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** 实际注入的段（有序）。 */
    public List<SegmentRender> injectedSegments() {
        return segments.stream().filter(SegmentRender::injected).toList();
    }

    /** 被跳过的段（有序）。 */
    public List<SegmentRender> skippedSegments() {
        return segments.stream().filter(s -> !s.injected()).toList();
    }

    /**
     * 单段装配明细。
     *
     * @param id         段 id（manifest 声明；动态记忆尾段固定为 {@code memory-snapshot}）
     * @param charCount  注入后的正文字符数（模板替换后的实际长度）；跳过段恒为 0
     * @param injected   是否注入
     * @param skipReason 跳过原因（注入段为 null）；文案直接面向 /context 输出
     */
    public record SegmentRender(String id, int charCount, boolean injected, String skipReason) {

        static SegmentRender injected(String id, int charCount) {
            return new SegmentRender(id, charCount, true, null);
        }

        static SegmentRender skipped(String id, String reason) {
            return new SegmentRender(id, 0, false, reason);
        }
    }
}
