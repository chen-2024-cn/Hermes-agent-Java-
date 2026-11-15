package com.cyk.util;

import com.cyk.constant.Constants;
import com.cyk.ui.JLineUserInput;
import com.cyk.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Startup decoration is rendered by the line editor, never by a competing writer or a sleep loop. */
public final class BannerAnimation {
    static final int ROWS = 9;
    static final int CUBE_COLUMNS = RotatingCube.COLUMNS;
    static final int GAP = 3;
    static final long DURATION_MILLIS = 10_000;
    static final long FRAME_MILLIS = 40;
    static final int FRAME_COUNT = (int) (DURATION_MILLIS / FRAME_MILLIS) + 1;

    public static void print(String version) {
        print(version, List.of());
    }

    public static void print(String version, List<String> startupLines) {
        if (Ui.userInput() instanceof JLineUserInput editor && editor.supportsAnimation()
                && Ui.out() == System.out) {
            editor.prepareStartup(new Presentation(version, ConsoleUi.isColorEnabled(), startupLines, System::nanoTime));
        } else {
            Ui.out().println(StartupBanner.render(version, false));
            startupLines.forEach(Ui.out()::println);
            Ui.out().flush();
        }
    }

    /** Pure frame selection and layout. The first read owns its lifetime and monotonic clock. */
    public static final class Presentation {
        private final String version;
        private final boolean color;
        private final List<String> startupLines;
        private final LongSupplier clock;
        private boolean started;
        private boolean stopped;
        private long startedAt;
        private int columns;
        private int rows;
        private int frame;
        private int renderedFrame = -1;
        private int renderedColumns = -1;
        private int renderedRows = -1;

        public Presentation(String version, boolean color, List<String> startupLines, LongSupplier clock) {
            this.version = version;
            this.color = color;
            this.startupLines = new ArrayList<>(startupLines);
            this.clock = clock;
        }

        public void addLine(String line) { startupLines.add(line); }

        public boolean needsRefresh(int columns, int rows) {
            advance(columns, rows);
            return renderedFrame != frame || renderedColumns != columns || renderedRows != rows;
        }

        public boolean isAnimating() { return started && !stopped; }

        private void advance(int columns, int rows) {
            if (!started) {
                started = true;
                startedAt = clock.getAsLong();
                this.columns = columns;
                this.rows = rows;
                stopped = !fitsCube(columns, rows);
            } else if (columns != this.columns || rows != this.rows) {
                // Reflow the prompt safely through JLine; don't continue animation across a resize.
                stopped = true;
                this.columns = columns;
                this.rows = rows;
            }
            if (!stopped) {
                long elapsed = Math.max(0, clock.getAsLong() - startedAt);
                frame = (int) Math.min(FRAME_COUNT - 1, elapsed / (FRAME_MILLIS * 1_000_000L));
                if (elapsed >= DURATION_MILLIS * 1_000_000L) {
                    frame = FRAME_COUNT - 1;
                    stopped = true;
                }
            }
        }

        private boolean fitsCube(int columns, int rows) {
            return columns > bannerWidth(StartupBanner.render(version, false)) + GAP + CUBE_COLUMNS
                    && rows > ROWS + startupLines.size() + 2;
        }

        public String renderHeader(int columns, int rows) {
            advance(columns, rows);
            String banner = StartupBanner.render(version, color);
            int width = bannerWidth(banner);
            String heading;
            if (columns <= width || rows <= ROWS + startupLines.size() + 2) {
                heading = compact(version, columns);
            } else if (columns <= width + GAP + CUBE_COLUMNS) {
                heading = banner;
            } else {
                heading = renderFrame(banner, frame, color);
            }
            renderedFrame = frame;
            renderedColumns = columns;
            renderedRows = rows;
            StringBuilder result = new StringBuilder(heading).append('\n');
            for (String line : startupLines) result.append(line).append('\n');
            return result.toString();
        }

        public void stop() { stopped = true; }
    }

    /** Original left-hand banner plus a fixed-size frame; colors do not affect layout width. */
    static String renderFrame(String banner, int frame, boolean color) {
        String[] left = banner.split("\n", -1);
        if (left.length != ROWS) throw new IllegalArgumentException("Unexpected banner height");
        String[] cube = cubeFrame(frame, color);
        StringBuilder result = new StringBuilder();
        for (int row = 0; row < ROWS; row++) {
            if (row > 0) result.append('\n');
            result.append(left[row]).append(" ".repeat(GAP)).append(cube[row]);
        }
        return result.toString();
    }

    static String[] cubeFrame(int frame, boolean color) {
        return RotatingCube.render(frame, FRAME_COUNT, color);
    }

    static int bannerWidth(String banner) {
        return banner.lines().mapToInt(StartupBanner::displayWidth).max().orElse(0);
    }

    static String compact(String version, int columns) {
        String label = Constants.CLI_NAME + (version == null || version.isBlank() ? "" : " · v" + version.strip());
        int limit = Math.max(0, columns - 1);
        int width = 0;
        int end = 0;
        while (end < label.length()) {
            int cp = label.codePointAt(end);
            int next = StartupBanner.isWide(cp) ? 2 : 1;
            if (width + next > limit) break;
            width += next;
            end += Character.charCount(cp);
        }
        return label.substring(0, end);
    }

    private BannerAnimation() { }
}
