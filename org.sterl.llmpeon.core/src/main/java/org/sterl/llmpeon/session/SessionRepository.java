package org.sterl.llmpeon.session;

import java.util.List;

/**
 * Persistence port for sessions. Implementations may use append-only files, a KV store or a
 * relational database; the session service only sees events and summaries.
 */
public interface SessionRepository {

    void append(SessionId id, SessionEvent event);

    List<SessionEvent> loadEvents(SessionId id);

    List<SessionSummary> list();

    boolean exists(SessionId id);

    default void delete(SessionId id) {
        throw new UnsupportedOperationException("delete is not supported by " + getClass().getSimpleName());
    }
}
