package org.sterl.llmpeon.session;

import java.time.Instant;
import java.util.Objects;

import dev.langchain4j.data.message.ChatMessage;

/** One ordered entry of a session transcript. */
public record SessionMessage(String agentName, MessageKind kind, Instant timestamp, ChatMessage message) {

    public SessionMessage {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(message, "message");
        agentName = agentName == null ? "" : agentName;
    }
}
