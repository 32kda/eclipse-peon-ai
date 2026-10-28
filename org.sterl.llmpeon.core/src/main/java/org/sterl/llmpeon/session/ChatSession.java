package org.sterl.llmpeon.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.langchain4j.data.message.ChatMessage;

/** Materialized session state, rebuilt from {@link SessionEvent}s. */
public class ChatSession {

    private final SessionId id;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    private long lastSeq;
    private final List<SessionMessage> transcript = new ArrayList<>();
    private final Map<String, List<ChatMessage>> agentContexts = new LinkedHashMap<>();
    private final Map<String, PinnedSkill> pinnedSkills = new LinkedHashMap<>();

    public ChatSession(SessionId id) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = "New chat";
    }

    public SessionId id() {
        return id;
    }

    public String title() {
        return title;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long lastSeq() {
        return lastSeq;
    }

    public void markSeq(long seq) {
        if (seq > lastSeq) lastSeq = seq;
    }

    void created(String title, Instant at) {
        this.title = title == null || title.isBlank() ? "New chat" : title;
        this.createdAt = at;
        this.updatedAt = at;
    }

    void retitled(String title, Instant at) {
        this.title = title == null || title.isBlank() ? "New chat" : title;
        this.updatedAt = at;
    }

    void addMessage(String agent, MessageKind kind, Instant at, ChatMessage message) {
        transcript.add(new SessionMessage(agent, kind, at, message));
        agentContexts.computeIfAbsent(agent == null ? "" : agent, k -> new ArrayList<>()).add(message);
        this.updatedAt = at;
    }

    void replaceAgentContext(String agent, List<ChatMessage> context, Instant at) {
        agentContexts.put(agent == null ? "" : agent,
                context == null ? new ArrayList<>() : new ArrayList<>(context));
        this.updatedAt = at;
    }

    void clear(Instant at) {
        transcript.clear();
        agentContexts.clear();
        this.updatedAt = at;
    }

    void pin(PinnedSkill skill, Instant at) {
        pinnedSkills.put(skill.key(), skill);
        this.updatedAt = at;
    }

    void unpin(PinnedSkill skill, Instant at) {
        pinnedSkills.remove(skill.key());
        this.updatedAt = at;
    }

    public List<SessionMessage> transcript() {
        return List.copyOf(transcript);
    }

    public List<ChatMessage> agentContext(String agent) {
        var context = agentContexts.get(agent == null ? "" : agent);
        return context == null ? List.of() : List.copyOf(context);
    }

    public Map<String, List<ChatMessage>> agentContexts() {
        var copy = new LinkedHashMap<String, List<ChatMessage>>();
        agentContexts.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return copy;
    }

    public List<PinnedSkill> pinnedSkills() {
        return List.copyOf(pinnedSkills.values());
    }

    public int messageCount() {
        return transcript.size();
    }

    public SessionSummary summary() {
        return new SessionSummary(id, title, createdAt, updatedAt, transcript.size());
    }
}
