package org.sterl.llmpeon.session;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import dev.langchain4j.data.message.ChatMessage;

/**
 * One append-only session event. Events are the durable source of truth; the materialized
 * {@link ChatSession} is rebuilt by replaying them.
 */
public sealed interface SessionEvent {

    long seq();

    Instant at();

    /** Session header. */
    record SessionCreated(long seq, Instant at, SessionId id, String title) implements SessionEvent {
        public SessionCreated {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(id, "id");
            title = title == null || title.isBlank() ? "New chat" : title;
        }
    }

    /** A normal transcript message. */
    record MessageAdded(long seq, Instant at, String agent, MessageKind kind, ChatMessage message)
            implements SessionEvent {
        public MessageAdded {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(message, "message");
            agent = agent == null ? "" : agent;
        }
    }

    /**
     * Replaces one agent's LLM working context (compaction). The transcript is never touched.
     *
     * @param baseSeq last event sequence already included in {@code context}; reserved for future
     *                snapshots/checkpoints.
     */
    record AgentContextReplaced(long seq, Instant at, String agent, long baseSeq,
            List<ChatMessage> context, String reason) implements SessionEvent {
        public AgentContextReplaced {
            Objects.requireNonNull(at, "at");
            agent = agent == null ? "" : agent;
            context = context == null ? List.of() : List.copyOf(context);
            reason = reason == null ? "" : reason;
        }
    }

    /** Clears the visible transcript and every working context; the session itself stays alive. */
    record SessionCleared(long seq, Instant at) implements SessionEvent {
        public SessionCleared {
            Objects.requireNonNull(at, "at");
        }
    }

    /** Auto/manual title change. */
    record SessionRetitled(long seq, Instant at, String title) implements SessionEvent {
        public SessionRetitled {
            Objects.requireNonNull(at, "at");
            title = title == null || title.isBlank() ? "New chat" : title;
        }
    }

    record SkillPinned(long seq, Instant at, PinnedSkill skill) implements SessionEvent {
        public SkillPinned {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(skill, "skill");
        }
    }

    record SkillUnpinned(long seq, Instant at, PinnedSkill skill) implements SessionEvent {
        public SkillUnpinned {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(skill, "skill");
        }
    }
}
