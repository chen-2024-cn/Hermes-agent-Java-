package com.cyk.command;

import com.cyk.manager.SessionManager;

import java.io.IOException;

/** The CLI and REPL resolve a session without starting another input loop. */
public final class SessionSelection {
    private SessionSelection() { }

    public static String resolve(SessionManager manager, String id, boolean last) throws IOException {
        String target = id;
        if (last || target == null || target.isBlank()) {
            var sessions = manager.listSessions();
            if (sessions.isEmpty()) return null;
            target = sessions.getFirst().id;
        }
        manager.readExisting(target);
        return target;
    }
}
