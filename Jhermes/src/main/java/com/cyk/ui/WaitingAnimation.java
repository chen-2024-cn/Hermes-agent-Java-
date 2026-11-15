package com.cyk.ui;

import com.cyk.constant.Constants;
import com.cyk.util.ConsoleUi;
import org.jline.utils.WCWidth;

import java.io.PrintStream;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** A request-scoped status line. It never writes into conversation history or owns the terminal. */
public final class WaitingAnimation implements AutoCloseable {
    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final Object OUTPUT_LOCK = new Object();
    private static WaitingAnimation active;

    private final PrintStream output;
    private final boolean animated;
    private final boolean color;
    private final IntSupplier columns;
    private final LongSupplier clock;
    private final long started;
    private final ScheduledExecutorService scheduler;
    private String status;
    private int frame;
    private boolean running;
    private boolean visible;
    private boolean closed;
    private String lastStaticStatus;

    public static WaitingAnimation create() {
        UserInput input = Ui.userInput();
        boolean animated = input instanceof JLineUserInput editor && editor.supportsAnimation()
                && Ui.out() == System.out;
        IntSupplier columns = input instanceof JLineUserInput editor ? editor::terminalColumns : () -> 80;
        return new WaitingAnimation(animated ? Ui.out() : Ui.diag(), animated,
                ConsoleUi.isColorEnabled(), columns, System::nanoTime, true);
    }

    /** Deterministic construction for frame and cleanup tests, without a background timer. */
    WaitingAnimation(PrintStream output, boolean animated, boolean color,
                     IntSupplier columns, LongSupplier clock, boolean schedule) {
        this.output = output;
        this.animated = animated;
        this.color = color;
        this.columns = columns;
        this.clock = clock;
        this.started = clock.getAsLong();
        this.scheduler = animated && schedule ? Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action, "jhermes-waiting-animation");
            thread.setDaemon(true);
            return thread;
        }) : null;
        if (scheduler != null) scheduler.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
    }

    public void resume(String status) {
        synchronized (OUTPUT_LOCK) {
            if (closed) return;
            this.status = status;
            running = true;
            if (animated) {
                if (active != null && active != this) active.pauseLocked();
                active = this;
                renderLocked();
            } else if (!status.equals(lastStaticStatus)) {
                output.println(Constants.CLI_NAME + " · " + status);
                output.flush();
                lastStaticStatus = status;
            }
        }
    }

    public void pause() {
        synchronized (OUTPUT_LOCK) { pauseLocked(); }
    }

    private void pauseLocked() {
        running = false;
        clearLocked();
        if (active == this) active = null;
    }

    private void clearLocked() {
        if (visible) {
            output.print("\r\033[2K");
            output.flush();
            visible = false;
        }
    }

    void tick() {
        synchronized (OUTPUT_LOCK) {
            if (!closed && running && animated && active == this) {
                frame = (frame + 1) % FRAMES.length;
                renderLocked();
            }
        }
    }

    private void renderLocked() {
        double seconds = Math.max(0, clock.getAsLong() - started) / 1_000_000_000.0;
        String label = " " + Constants.CLI_NAME + " · " + status + " · "
                + String.format(Locale.ROOT, "%.1fs", seconds);
        int width;
        try { width = Math.max(0, columns.getAsInt() - 1); }
        catch (RuntimeException e) { width = 79; }
        String text = clip(FRAMES[frame] + label, width);
        output.print("\r\033[2K");
        if (color && !text.isEmpty()) {
            output.print("\033[36m" + text.substring(0, 1) + "\033[0m\033[90m"
                    + text.substring(1) + "\033[0m");
        } else {
            output.print(text);
        }
        output.flush();
        visible = !text.isEmpty();
    }

    private static String clip(String text, int maxColumns) {
        int width = 0;
        int end = 0;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            int next = Math.max(0, WCWidth.wcwidth(cp));
            if (width + next > maxColumns) break;
            width += next;
            end += Character.charCount(cp);
        }
        return text.substring(0, end);
    }

    /** Log messages clear the status first and repaint it only after the complete log line. */
    public static void externalOutput(Runnable write) {
        synchronized (OUTPUT_LOCK) {
            WaitingAnimation current = active;
            if (current != null) current.clearLocked();
            try { write.run(); }
            finally {
                if (current != null && active == current && !current.closed && current.running) current.renderLocked();
            }
        }
    }

    @Override public void close() {
        synchronized (OUTPUT_LOCK) {
            if (closed) return;
            closed = true;
            pauseLocked();
        }
        if (scheduler != null) scheduler.shutdownNow();
    }
}
