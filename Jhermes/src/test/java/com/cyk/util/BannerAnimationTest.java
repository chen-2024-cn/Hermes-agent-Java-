package com.cyk.util;

import com.cyk.ui.Ui;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class BannerAnimationTest {
    @AfterEach void reset() { Ui.resetForTest(); }

    private static BannerAnimation.Presentation presentation(AtomicLong clock) {
        return new BannerAnimation.Presentation("2.2.0", false, List.of("模型: local-model", "Enter 发送"), clock::get);
    }

    @Test void cubeFramesHaveFixedBoundsAndReallyRotate() {
        var uniqueFrames = new java.util.HashSet<String>();
        for (int frame = 0; frame < BannerAnimation.FRAME_COUNT; frame++) {
            String[] cube = BannerAnimation.cubeFrame(frame, false);
            assertThat(cube).hasSize(BannerAnimation.ROWS);
            for (String row : cube) {
                assertThat(StartupBanner.displayWidth(row)).isEqualTo(BannerAnimation.CUBE_COLUMNS);
                assertThat(row).doesNotContain("\033", "\n");
            }
            uniqueFrames.add(String.join("\n", cube));
        }
        assertThat(uniqueFrames.size()).isGreaterThan(20);
        assertThat(BannerAnimation.cubeFrame(BannerAnimation.FRAME_COUNT - 1, false))
                .containsExactly(BannerAnimation.cubeFrame(0, false));
    }

    @Test void combinedFrameKeepsOriginalBannerAndColorsNeverChangeWidth() {
        String plain = StartupBanner.render("2.2.0", false);
        String colored = StartupBanner.render("2.2.0", true);
        int width = BannerAnimation.bannerWidth(plain);
        for (int frame = 0; frame < BannerAnimation.FRAME_COUNT; frame++) {
            String[] original = plain.split("\n");
            String[] combined = BannerAnimation.renderFrame(plain, frame, false).split("\n");
            String[] tinted = BannerAnimation.renderFrame(colored, frame, true).split("\n");
            for (int row = 0; row < combined.length; row++) {
                assertThat(combined[row]).startsWith(original[row]);
                assertThat(StartupBanner.displayWidth(combined[row])).isEqualTo(width + BannerAnimation.GAP + BannerAnimation.CUBE_COLUMNS);
                assertThat(StartupBanner.stripAnsi(tinted[row])).isEqualTo(combined[row]);
                assertThat(StartupBanner.displayWidth(combined[row])).isLessThan(80);
            }
        }
    }

    @Test void frameClockStartsWithTheFirstRenderNotBeforeInitialization() {
        AtomicLong clock = new AtomicLong();
        var view = presentation(clock);
        clock.set(60_000_000_000L);
        String first = view.renderHeader(80, 30);
        assertThat(first).startsWith(BannerAnimation.renderFrame(StartupBanner.render("2.2.0", false), 0, false));
        assertThat(view.isAnimating()).isTrue();
        assertThat(view.needsRefresh(80, 30)).isFalse();
        clock.addAndGet(40_000_000L);
        assertThat(view.needsRefresh(80, 30)).isTrue();
        assertThat(view.renderHeader(80, 30)).startsWith(
                BannerAnimation.renderFrame(StartupBanner.render("2.2.0", false), 1, false));
    }

    @Test void elapsedTimeSelectsTheCurrentFrameWithoutSleepingOrReplayingBacklog() {
        AtomicLong clock = new AtomicLong();
        var view = presentation(clock);
        view.renderHeader(120, 35);
        clock.set(4_040_000_000L);
        assertThat(view.renderHeader(120, 35)).startsWith(
                BannerAnimation.renderFrame(StartupBanner.render("2.2.0", false), 101, false));
        clock.set(10_000_000_000L);
        String finalHeader = view.renderHeader(120, 35);
        assertThat(finalHeader).startsWith(BannerAnimation.renderFrame(
                StartupBanner.render("2.2.0", false), BannerAnimation.FRAME_COUNT - 1, false));
        assertThat(view.isAnimating()).isFalse();
        clock.set(30_000_000_000L);
        assertThat(view.needsRefresh(120, 35)).isFalse();
        assertThat(view.renderHeader(120, 35)).isEqualTo(finalHeader);
    }

    @Test void stoppingBeforeTenSecondsIsImmediateAndKeepsTheCurrentFrame() {
        AtomicLong clock = new AtomicLong();
        var view = presentation(clock);
        view.renderHeader(80, 30);
        clock.set(2_000_000_000L);
        String shown = view.renderHeader(80, 30);
        view.stop();
        clock.set(20_000_000_000L);
        assertThat(view.isAnimating()).isFalse();
        assertThat(view.needsRefresh(80, 30)).isFalse();
        assertThat(view.renderHeader(80, 30)).isEqualTo(shown);
    }

    @Test void startupInformationAndInputHintRemainSeparateFromMessageData() {
        var view = presentation(new AtomicLong());
        view.addLine("输入提示");
        String header = view.renderHeader(80, 35);
        assertThat(header).endsWith("模型: local-model\nEnter 发送\n输入提示\n");
        assertThat(header).doesNotContain("\033[9A", "\033[60G", "\033[2J");
    }

    @Test void narrowAndShortTerminalsDowngradeWithoutAnimation() {
        var normal = presentation(new AtomicLong());
        assertThat(normal.renderHeader(70, 35)).startsWith(StartupBanner.render("2.2.0", false));
        assertThat(normal.isAnimating()).isFalse();
        for (int width : new int[]{0, 1, 8, 40, 56}) {
            var small = new BannerAnimation.Presentation("2.2.0", false, List.of(), () -> 0L);
            String label = small.renderHeader(width, 24).stripTrailing();
            assertThat(StartupBanner.displayWidth(label)).isLessThanOrEqualTo(Math.max(0, width - 1));
            assertThat(label).doesNotContain("╭", "\033");
            assertThat(small.isAnimating()).isFalse();
        }
        var shortWindow = presentation(new AtomicLong());
        assertThat(shortWindow.renderHeader(100, 8)).isEqualTo("Jhermes · v2.2.0\n模型: local-model\nEnter 发送\n");
    }

    @Test void resizingReflowsThroughTextRatherThanMovingIntoOldCoordinates() {
        AtomicLong clock = new AtomicLong();
        var view = presentation(clock);
        view.renderHeader(80, 35);
        clock.set(1_000_000_000L);
        assertThat(view.needsRefresh(40, 35)).isTrue();
        assertThat(view.renderHeader(40, 35)).startsWith("Jhermes · v2.2.0\n").doesNotContain("╭", "\033");
        assertThat(view.isAnimating()).isFalse();
        clock.set(2_000_000_000L);
        assertThat(view.needsRefresh(40, 35)).isFalse();
    }

    @Test void redirectedOutputKeepsStaticBannerAndDoesNotWait() {
        var bytes = new ByteArrayOutputStream();
        Ui.setStreams(new PrintStream(bytes, true, StandardCharsets.UTF_8), null);
        long started = System.nanoTime();
        BannerAnimation.print("2.2.0", List.of("模型: local-model", "温度: 0.7"));
        assertThat(System.nanoTime() - started).isLessThan(1_000_000_000L);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo(StartupBanner.render("2.2.0", false)
                + System.lineSeparator() + "模型: local-model" + System.lineSeparator() + "温度: 0.7" + System.lineSeparator());
        assertThat(bytes.toString(StandardCharsets.UTF_8)).doesNotContain("\033");
    }

    @Test void noColorKeepsRotatingGeometryAndOriginalStaticRenderContract() {
        AtomicLong clock = new AtomicLong();
        var view = presentation(clock);
        String first = view.renderHeader(120, 30);
        clock.set(3_000_000_000L);
        String next = view.renderHeader(120, 30);
        assertThat(next).isNotEqualTo(first).doesNotContain("\033");
        assertThat(next.codePoints().anyMatch(cp -> cp >= 0x2801 && cp <= 0x28ff)).isTrue();
        assertThat(StartupBanner.render("2.2.0", false).lines().mapToInt(StartupBanner::displayWidth).max().orElse(0)).isLessThanOrEqualTo(60);
    }
}
