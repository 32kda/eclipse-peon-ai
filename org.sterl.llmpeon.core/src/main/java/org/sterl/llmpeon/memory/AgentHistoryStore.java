package org.sterl.llmpeon.memory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.langchain4j.data.message.ChatMessage;

/**
 * Durable backend for one agent's memory.
 *
 * <p>The default implementation is the per-agent JSONL file; sessions use a store that routes
 * transcript appends and working-context replacements through {@code SessionService}.</p>
 */
public interface AgentHistoryStore {

    List<ChatMessage> load();

    /** Appends one message to both the transcript and this agent's working context. */
    void append(ChatMessage message) throws IOException;

    /** Appends several messages to both the transcript and this agent's working context. */
    void append(List<ChatMessage> messages) throws IOException;

    /**
     * Appends a message to the transcript only. Used when consecutive user messages are joined:
     * the joined context is written via {@link #persist(List)} and the new raw user message must
     * still be visible in the session transcript.
     */
    default void appendTranscript(ChatMessage message) throws IOException {
        // File-backed single-agent stores have no separate transcript.
    }

    /** Replaces this agent's working context (compaction/clear); never touches the transcript. */
    void persist(List<ChatMessage> messages) throws IOException;

    /** Clears this agent's working context. */
    void clear() throws IOException;

    /** The single backing file, when the implementation has one. */
    default Optional<Path> backingFile() {
        return Optional.empty();
    }
}
