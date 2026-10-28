package org.sterl.llmpeon.session;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;

/**
 * Application service for sessions: creates/opens/closes sessions, appends events and keeps a
 * bounded in-memory cache. All persistence goes through {@link SessionRepository}.
 *
 * <p>Open sessions are pinned (the UI tab is alive); only closed sessions are evicted from the
 * cache. The default cache size is 30.</p>
 */
public class SessionService {

    public static final int DEFAULT_MAX_CACHED_SESSIONS = 30;
    public static final String REMINDER_ATTRIBUTE = "peon.reminder";

    private final SessionRepository repository;
    private final int maxCachedSessions;
    private final Clock clock;
    private final SessionIdGenerator ids;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Access-order map = LRU order. */
    private final Map<SessionId, ChatSession> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<SessionId> open = new HashSet<>();

    public SessionService(SessionRepository repository) {
        this(repository, DEFAULT_MAX_CACHED_SESSIONS, Clock.systemUTC(), SessionIdGenerator.random());
    }

    public SessionService(SessionRepository repository, int maxCachedSessions, Clock clock,
            SessionIdGenerator ids) {
        this.repository = repository;
        this.maxCachedSessions = Math.max(1, maxCachedSessions);
        this.clock = clock;
        this.ids = ids;
    }

    public synchronized SessionId create(String title) {
        var id = ids.next();
        var now = clock.instant();
        var actualTitle = normalizeTitle(title);
        var created = new SessionEvent.SessionCreated(1, now, id, actualTitle);
        repository.append(id, created);
        var session = new ChatSession(id);
        SessionProjector.apply(session, created);
        cache.put(id, session);
        open.add(id);
        fireChanged();
        return id;
    }

    /** Creates a session that is not pinned (used by the legacy importer). */
    public synchronized SessionId createDetached(String title) {
        var id = ids.next();
        var now = clock.instant();
        var actualTitle = normalizeTitle(title);
        repository.append(id, new SessionEvent.SessionCreated(1, now, id, actualTitle));
        var session = new ChatSession(id);
        SessionProjector.apply(session, new SessionEvent.SessionCreated(1, now, id, actualTitle));
        cache.put(id, session);
        evictIfNeeded();
        fireChanged();
        return id;
    }

    /**
     * Imports an existing conversation under a caller-chosen id. Idempotent: an existing session
     * with the same id is left untouched. The imported session is not pinned.
     */
    public synchronized void importSession(SessionId id, String title, String agent,
            List<ChatMessage> messages) {
        if (cache.containsKey(id) || repository.exists(id)) return;
        var now = clock.instant();
        var actualTitle = normalizeTitle(title);
        var session = new ChatSession(id);
        append(session, new SessionEvent.SessionCreated(1, now, id, actualTitle));
        for (var message : messages == null ? List.<ChatMessage>of() : messages) {
            var kind = UserReminder.isReminder(message) ? MessageKind.REMINDER : MessageKind.CHAT;
            append(session, new SessionEvent.MessageAdded(nextSeq(session), now, agent, kind, message));
        }
        cache.put(id, session);
        evictIfNeeded();
        fireChanged();
    }

    /** Opens and pins a session. Throws when the id does not exist. */
    public synchronized ChatSession open(SessionId id) {
        var session = cache.get(id);
        if (session == null) {
            if (!repository.exists(id)) {
                throw new IllegalArgumentException("Unknown session " + id);
            }
            session = SessionProjector.withDefaults(id, repository.loadEvents(id), clock.instant());
            cache.put(id, session);
        }
        open.add(id);
        return session;
    }

    public synchronized void close(SessionId id) {
        open.remove(id);
        evictIfNeeded();
        fireChanged();
    }

    public synchronized boolean isOpen(SessionId id) {
        return open.contains(id);
    }

    public synchronized boolean exists(SessionId id) {
        return cache.containsKey(id) || repository.exists(id);
    }

    public synchronized ChatSession get(SessionId id) {
        var session = cache.get(id);
        if (session != null) return session;
        if (!repository.exists(id)) throw new IllegalArgumentException("Unknown session " + id);
        session = SessionProjector.withDefaults(id, repository.loadEvents(id), clock.instant());
        cache.put(id, session);
        evictIfNeeded();
        return session;
    }

    public synchronized void appendMessage(SessionId id, String agent, MessageKind kind, ChatMessage message) {
        var session = require(id);
        var event = new SessionEvent.MessageAdded(nextSeq(session), clock.instant(), agent, kind, message);
        append(session, event);
        autoTitle(session, kind, message);
        fireChanged();
    }

    /**
     * Records a message in the transcript only. Used when the memory joins consecutive user
     * messages: the new user message enters the transcript, while the joined context is written
     * separately through {@link #replaceAgentContext}.
     */
    public synchronized void appendTranscriptOnly(SessionId id, String agent, MessageKind kind,
            ChatMessage message) {
        var session = require(id);
        append(session, new SessionEvent.MessageAdded(nextSeq(session), clock.instant(), agent, kind, message));
        autoTitle(session, kind, message);
        fireChanged();
    }

    public synchronized void replaceAgentContext(SessionId id, String agent, List<ChatMessage> context,
            String reason) {
        var session = require(id);
        append(session, new SessionEvent.AgentContextReplaced(nextSeq(session), clock.instant(), agent,
                session.lastSeq(), context, reason));
        fireChanged();
    }

    public synchronized void clearAgentContext(SessionId id, String agent) {
        replaceAgentContext(id, agent, List.of(), "clear");
    }

    public synchronized void clear(SessionId id) {
        var session = require(id);
        append(session, new SessionEvent.SessionCleared(nextSeq(session), clock.instant()));
        fireChanged();
    }

    public synchronized void suggestTitle(SessionId id, String text) {
        var session = require(id);
        if (!isDefaultTitle(session.title())) return;
        var title = deriveTitle(text);
        if (title == null) return;
        append(session, new SessionEvent.SessionRetitled(nextSeq(session), clock.instant(), title));
        fireChanged();
    }

    public synchronized void pinSkill(SessionId id, PinnedSkill skill) {
        var session = require(id);
        append(session, new SessionEvent.SkillPinned(nextSeq(session), clock.instant(), skill));
        fireChanged();
    }

    public synchronized void unpinSkill(SessionId id, PinnedSkill skill) {
        var session = require(id);
        append(session, new SessionEvent.SkillUnpinned(nextSeq(session), clock.instant(), skill));
        fireChanged();
    }

    public List<SessionSummary> list() {
        return repository.list();
    }

    public synchronized void addChangeListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public synchronized void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private ChatSession require(SessionId id) {
        var session = cache.get(id);
        if (session == null) return open(id);
        return session;
    }

    private void append(ChatSession session, SessionEvent event) {
        repository.append(session.id(), event);
        SessionProjector.apply(session, event);
    }

    private long nextSeq(ChatSession session) {
        return session.lastSeq() + 1;
    }

    private void autoTitle(ChatSession session, MessageKind kind, ChatMessage message) {
        if (kind != MessageKind.CHAT || !isDefaultTitle(session.title())) return;
        if (!(message instanceof UserMessage user)) return;
        if (Boolean.TRUE.equals(user.attributes().get(REMINDER_ATTRIBUTE))) return;
        var title = deriveTitle(userText(user));
        if (title != null) {
            append(session, new SessionEvent.SessionRetitled(nextSeq(session), clock.instant(), title));
        }
    }

    private static String userText(UserMessage user) {
        if (user.hasSingleText()) return user.singleText();
        var text = user.contents().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .reduce("", (a, b) -> a + System.lineSeparator() + b);
        return text;
    }

    private static boolean isDefaultTitle(String title) {
        return title == null || title.isBlank() || "New chat".equals(title);
    }

    private static String normalizeTitle(String title) {
        return isDefaultTitle(title) ? "New chat" : title.strip();
    }

    /** First non-empty line, trimmed and capped. Returns {@code null} for blank input. */
    public static String deriveTitle(String text) {
        if (text == null) return null;
        for (String raw : text.split("\\R")) {
            var line = raw.strip();
            if (line.isEmpty()) continue;
            while (line.startsWith("#") || line.startsWith("*") || line.startsWith("-")
                    || line.startsWith(">")) {
                line = line.substring(1).strip();
            }
            if (line.isEmpty()) continue;
            return line.length() <= 60 ? line : line.substring(0, 57).stripTrailing() + "...";
        }
        return null;
    }

    private void evictIfNeeded() {
        while (cache.size() > maxCachedSessions) {
            var victim = cache.keySet().stream().filter(id -> !open.contains(id)).findFirst();
            if (victim.isEmpty()) return; // all cached sessions are open — never evict an open tab
            cache.remove(victim.get());
        }
    }

    private void fireChanged() {
        for (var listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // A listener must never break session persistence.
                org.slf4j.LoggerFactory.getLogger(SessionService.class)
                        .warn("Session change listener failed", e);
            }
        }
    }

    /** Test seam: number of materialized sessions currently cached. */
    public synchronized int cachedSessionCount() {
        return cache.size();
    }

    public synchronized List<SessionId> openSessionIds() {
        return new ArrayList<>(open);
    }
}
