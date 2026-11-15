package com.cyk.ui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class WaitingAnimationTest {
    private static PrintStream stream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    @Test void framesRotateAndUseMonotonicElapsedSeconds() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicLong clock = new AtomicLong(10_000_000_000L);
        try (var animation = new WaitingAnimation(stream(bytes), true, false, () -> 100, clock::get, false)) {
            animation.resume("正在生成回答");
            clock.addAndGet(3_200_000_000L);
            animation.tick();
            assertThat(bytes.toString(StandardCharsets.UTF_8))
                    .contains("⠋ Jhermes · 正在生成回答 · 0.0s", "⠙ Jhermes · 正在生成回答 · 3.2s");
        }
        assertThat(bytes.toString(StandardCharsets.UTF_8)).endsWith("\r\033[2K");
    }

    @Test void allTenFramesCycleWithoutNewlines() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var animation = new WaitingAnimation(stream(bytes), true, false, () -> 100, () -> 0L, false)) {
            animation.resume("等待");
            for (int i = 0; i < 10; i++) animation.tick();
            assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
                    .doesNotContain("\n");
        }
    }

    @Test void pauseAndClosePreventAnyLateFrameAndAreIdempotent() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var animation = new WaitingAnimation(stream(bytes), true, false, () -> 100, () -> 0L, false);
        animation.resume("等待");
        animation.pause();
        String paused = bytes.toString(StandardCharsets.UTF_8);
        animation.tick();
        animation.pause();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo(paused);
        animation.resume("等待重试");
        animation.close();
        String closed = bytes.toString(StandardCharsets.UTF_8);
        animation.close();
        animation.tick();
        animation.resume("不应出现");
        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo(closed).doesNotContain("不应出现");
    }

    @Test void retryStatusKeepsTheOriginalRequestStartTime() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicLong clock = new AtomicLong();
        try (var animation = new WaitingAnimation(stream(bytes), true, false, () -> 100, clock::get, false)) {
            animation.resume("正在生成回答");
            clock.set(2_000_000_000L);
            animation.pause();
            animation.resume("等待重试");
            clock.set(3_000_000_000L);
            animation.resume("正在生成回答");
            assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("等待重试 · 2.0s", "正在生成回答 · 3.0s");
        }
    }

    @Test void staticFallbackHasNoControlCodesAndDoesNotLoop() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var animation = new WaitingAnimation(stream(bytes), false, false, () -> 100, () -> 0L, false)) {
            animation.resume("正在生成回答");
            animation.tick();
            animation.pause();
            animation.resume("正在生成回答");
            animation.resume("等待重试");
        }
        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo(
                "Jhermes · 正在生成回答" + System.lineSeparator() + "Jhermes · 等待重试" + System.lineSeparator());
    }

    @Test void logOutputClearsAndRestoresTheCurrentStatus() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = stream(bytes);
        try (var animation = new WaitingAnimation(output, true, false, () -> 100, () -> 0L, false)) {
            animation.resume("等待");
            bytes.reset();
            WaitingAnimation.externalOutput(() -> output.print("WARN: diagnostic\n"));
            assertThat(bytes.toString(StandardCharsets.UTF_8))
                    .startsWith("\r\033[2KWARN: diagnostic\n\r\033[2K⠋ Jhermes · 等待");
            animation.pause();
            bytes.reset();
            WaitingAnimation.externalOutput(() -> output.print("ANSWER\n"));
            assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("ANSWER\n");
        }
    }

    @Test void narrowAndResizedTerminalsNeverWrapTheStatus() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicInteger columns = new AtomicInteger(5);
        try (var animation = new WaitingAnimation(stream(bytes), true, false, columns::get, () -> 0L, false)) {
            animation.resume("正在生成回答");
            assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("\r\033[2K⠋ Jh");
            columns.set(2);
            bytes.reset();
            animation.tick();
            assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("\r\033[2K⠙");
        }
    }

    @Test void colorsAreOptionalAndStreamsAreNotClosed() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = stream(bytes);
        try (var animation = new WaitingAnimation(output, true, true, () -> 100, () -> 0L, false)) {
            animation.resume("等待");
            assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("\033[36m⠋\033[0m\033[90m", "\033[0m");
        }
        output.print("still usable");
        assertThat(bytes.toString(StandardCharsets.UTF_8)).endsWith("still usable");
    }
}
