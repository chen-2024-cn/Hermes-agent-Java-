package com.cyk.ui;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;

/** Preserve normal logging while coordinating complete log lines with the waiting status. */
public final class WaitingConsoleAppender extends ConsoleAppender<ILoggingEvent> {
    @Override protected void append(ILoggingEvent event) {
        WaitingAnimation.externalOutput(() -> super.append(event));
    }
}
