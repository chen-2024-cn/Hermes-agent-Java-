package com.cyk.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RotatingCubeTest {
    @Test void everyFrameContainsOnlyFixedWidthBrailleOrSpaces() {
        for (int frame = 0; frame < 32; frame++) {
            String[] lines = RotatingCube.render(frame, 32, false);
            assertThat(lines).hasSize(9);
            int dots = 0;
            for (String line : lines) {
                assertThat(line).hasSize(17);
                assertThat(line.codePoints().allMatch(cp -> cp == ' ' || cp >= 0x2801 && cp <= 0x28ff)).isTrue();
                dots += (int) line.chars().filter(cp -> cp != ' ').count();
            }
            assertThat(dots).isBetween(25, 100);
        }
    }

    @Test void depthUsesThreeRestrainedCyanIntensitiesAndPreservesGeometry() {
        String[] plain = RotatingCube.render(0, 32, false);
        String[] colored = RotatingCube.render(0, 32, true);
        String all = String.join("\n", colored);
        assertThat(all).contains("\033[38;2;48;76;88m", "\033[38;2;69;145;163m", "\033[38;2;126;225;235m")
                .doesNotContain("95m", "96m", "AI");
        for (int row = 0; row < plain.length; row++) {
            assertThat(StartupBanner.stripAnsi(colored[row])).isEqualTo(plain[row]);
            assertThat(StartupBanner.displayWidth(colored[row])).isEqualTo(17);
        }
    }

    @Test void repeatedRenderingIsDeterministicAndQuarterTurnEndsInSamePose() {
        for (int frame = 0; frame < 32; frame++) {
            assertThat(RotatingCube.render(frame, 32, false)).containsExactly(RotatingCube.render(frame, 32, false));
        }
        assertThat(RotatingCube.render(0, 32, false)).containsExactly(RotatingCube.render(31, 32, false));
    }
}
