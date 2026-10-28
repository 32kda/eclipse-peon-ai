package org.sterl.llmpeon.session;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;

/**
 * The only place that knows the on-disk JSON shape of a session event. A future KV/DB
 * implementation may use a different codec; the domain never sees JSON.
 */
public final class SessionEventCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionEventCodec() {
    }

    public static String toJson(SessionEvent event) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("seq", event.seq());
            root.put("at", event.at().toEpochMilli());

            if (event instanceof SessionEvent.SessionCreated created) {
                root.put("type", "SessionCreated");
                root.put("id", created.id().toString());
                root.put("title", created.title());
            } else if (event instanceof SessionEvent.MessageAdded added) {
                root.put("type", "MessageAdded");
                root.put("agent", added.agent());
                root.put("kind", added.kind().name());
                root.set("message", MAPPER.readTree(ChatMessageSerializer.messageToJson(added.message())));
            } else if (event instanceof SessionEvent.AgentContextReplaced replaced) {
                root.put("type", "AgentContextReplaced");
                root.put("agent", replaced.agent());
                root.put("baseSeq", replaced.baseSeq());
                root.put("reason", replaced.reason());
                root.set("context", MAPPER.readTree(
                        ChatMessageSerializer.messagesToJson(replaced.context())));
            } else if (event instanceof SessionEvent.SessionCleared) {
                root.put("type", "SessionCleared");
            } else if (event instanceof SessionEvent.SessionRetitled retitled) {
                root.put("type", "SessionRetitled");
                root.put("title", retitled.title());
            } else if (event instanceof SessionEvent.SkillPinned pinned) {
                root.put("type", "SkillPinned");
                root.set("skill", skillNode(pinned.skill()));
            } else if (event instanceof SessionEvent.SkillUnpinned unpinned) {
                root.put("type", "SkillUnpinned");
                root.set("skill", skillNode(unpinned.skill()));
            } else {
                throw new IllegalArgumentException("Unknown session event " + event.getClass());
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode session event " + event, e);
        }
    }

    public static SessionEvent fromJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            long seq = root.path("seq").asLong();
            Instant at = Instant.ofEpochMilli(root.path("at").asLong());
            String type = root.path("type").asText();

            return switch (type) {
                case "SessionCreated" -> new SessionEvent.SessionCreated(seq, at,
                        SessionId.parse(root.path("id").asText()), root.path("title").asText());
                case "MessageAdded" -> new SessionEvent.MessageAdded(seq, at,
                        root.path("agent").asText(), MessageKind.valueOf(root.path("kind").asText()),
                        message(root.get("message")));
                case "AgentContextReplaced" -> new SessionEvent.AgentContextReplaced(seq, at,
                        root.path("agent").asText(), root.path("baseSeq").asLong(),
                        messages(root.get("context")), root.path("reason").asText());
                case "SessionCleared" -> new SessionEvent.SessionCleared(seq, at);
                case "SessionRetitled" -> new SessionEvent.SessionRetitled(seq, at,
                        root.path("title").asText());
                case "SkillPinned" -> new SessionEvent.SkillPinned(seq, at, skill(root.get("skill")));
                case "SkillUnpinned" -> new SessionEvent.SkillUnpinned(seq, at, skill(root.get("skill")));
                default -> throw new IllegalArgumentException("Unknown session event type '" + type + "'");
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode session event: " + json, e);
        }
    }

    private static ObjectNode skillNode(PinnedSkill skill) {
        var node = MAPPER.createObjectNode();
        node.put("name", skill.name());
        node.put("source", skill.source());
        return node;
    }

    private static PinnedSkill skill(JsonNode node) {
        return new PinnedSkill(node.path("name").asText(), node.path("source").asText());
    }

    private static ChatMessage message(JsonNode node) {
        if (node == null || node.isNull()) throw new IllegalArgumentException("message is missing");
        return ChatMessageDeserializer.messageFromJson(node.toString());
    }

    private static java.util.List<ChatMessage> messages(JsonNode node) {
        if (node == null || node.isNull()) return java.util.List.of();
        if (!(node instanceof ArrayNode)) throw new IllegalArgumentException("context is not an array");
        return ChatMessageDeserializer.messagesFromJson(node.toString());
    }
}
