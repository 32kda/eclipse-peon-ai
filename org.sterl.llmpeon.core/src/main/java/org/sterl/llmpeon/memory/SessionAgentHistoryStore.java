package org.sterl.llmpeon.memory;

import java.util.List;
import java.util.Objects;

import org.sterl.llmpeon.session.MessageKind;
import org.sterl.llmpeon.session.SessionId;
import org.sterl.llmpeon.session.SessionService;

import dev.langchain4j.data.message.ChatMessage;

/**
 * Routes one agent's memory through the session event log.
 *
 * <p>Appends update the visible transcript and the agent's working context; {@link #persist}
 * replaces only the working context (compaction) and leaves the transcript intact.</p>
 */
public class SessionAgentHistoryStore implements AgentHistoryStore {

    private final SessionService sessionService;
    private final SessionId sessionId;
    private final String agentName;

    public SessionAgentHistoryStore(SessionService sessionService, SessionId sessionId, String agentName) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.agentName = agentName == null ? "" : agentName;
    }

    @Override
    public List<ChatMessage> load() {
        return sessionService.get(sessionId).agentContext(agentName);
    }

    @Override
    public void append(ChatMessage message) {
        sessionService.appendMessage(sessionId, agentName, MessageKind.CHAT, message);
    }

    @Override
    public void append(List<ChatMessage> messages) {
        if (messages == null) return;
        for (var message : messages) append(message);
    }

    @Override
    public void appendTranscript(ChatMessage message) {
        sessionService.appendTranscriptOnly(sessionId, agentName, MessageKind.CHAT, message);
    }

    @Override
    public void persist(List<ChatMessage> messages) {
        sessionService.replaceAgentContext(sessionId, agentName, messages, "compact");
    }

    @Override
    public void clear() {
        sessionService.clearAgentContext(sessionId, agentName);
    }
}
