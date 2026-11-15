package com.cyk.command;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.ConsoleAppender;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Map;

/** Keep diagnostics off machine-readable stdout, without changing normal chat logging. */
final class DiagnosticOutput implements AutoCloseable {
    private final Map<ConsoleAppender<?>, String> targets = new IdentityHashMap<>();

    DiagnosticOutput() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            for (var logger : context.getLoggerList()) {
                var appenders = logger.iteratorForAppenders();
                while (appenders.hasNext()) {
                    if (appenders.next() instanceof ConsoleAppender<?> appender
                            && !targets.containsKey(appender)) {
                        targets.put(appender, appender.getTarget());
                        appender.stop();
                        appender.setTarget("System.err");
                        appender.start();
                    }
                }
            }
        }
    }

    @Override public void close() {
        targets.forEach((appender, target) -> {
            appender.stop();
            appender.setTarget(target);
            appender.start();
        });
    }
}
