package org.sterl.llmpeon.session;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Replays append-only events into the materialized {@link ChatSession}. */
public final class SessionProjector {

    private SessionProjector() {
    }

    public static ChatSession project(SessionId id, List<SessionEvent> events) {
        var session = new ChatSession(id);
        if (events == null || events.isEmpty()) return session;

        events.stream()
                .sorted(Comparator.comparingLong(SessionEvent::seq))
                .forEach(event -> apply(session, event));
        return session;
    }

    public static void apply(ChatSession session, SessionEvent event) {
        session.markSeq(event.seq());
        if (event instanceof SessionEvent.SessionCreated created) {
            session.created(created.title(), created.at());
        } else if (event instanceof SessionEvent.MessageAdded added) {
            session.addMessage(added.agent(), added.kind(), added.at(), added.message());
        } else if (event instanceof SessionEvent.AgentContextReplaced replaced) {
            session.replaceAgentContext(replaced.agent(), replaced.context(), replaced.at());
        } else if (event instanceof SessionEvent.SessionCleared cleared) {
            session.clear(cleared.at());
        } else if (event instanceof SessionEvent.SessionRetitled retitled) {
            session.retitled(retitled.title(), retitled.at());
        } else if (event instanceof SessionEvent.SkillPinned pinned) {
            session.pin(pinned.skill(), pinned.at());
        } else if (event instanceof SessionEvent.SkillUnpinned unpinned) {
            session.unpin(unpinned.skill(), unpinned.at());
        }
    }

    /** Ensures a materialized session has at least a header timestamp after replay. */
    public static ChatSession withDefaults(SessionId id, List<SessionEvent> events, Instant fallback) {
        var session = project(id, events);
        if (session.createdAt() == null) session.created("New chat", fallback);
        return session;
    }
}
