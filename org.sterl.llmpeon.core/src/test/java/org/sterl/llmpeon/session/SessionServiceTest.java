package org.sterl.llmpeon.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sterl.llmpeon.memory.SessionAgentHistoryStore;
import org.sterl.llmpeon.memory.ThreadSafeMemory;
import org.sterl.llmpeon.session.SessionEvent.MessageAdded;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

class SessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tmp;

    private SessionService service(int max) {
        return new SessionService(new FileSessionRepository(tmp), max, CLOCK, SessionId::random);
    }

    @Test
    void appendReplaysTranscriptAndWorkingContext() {
        var service = service(30);
        var id = service.create("New chat");
        service.appendMessage(id, "Peon-Dev", MessageKind.CHAT, UserMessage.from("Hello world"));
        service.appendMessage(id, "Peon-Dev", MessageKind.CHAT, AiMessage.from("Hi"));
        service.appendMessage(id, "Peon-Dev", MessageKind.CHAT,
                ToolExecutionResultMessage.from("1", "read", "content"));
        service.close(id);

        var reopened = service(30);
        var session = reopened.open(id);

        assertThat(session.title()).isEqualTo("Hello world");
        assertThat(session.transcript()).hasSize(3);
        assertThat(session.agentContext("Peon-Dev")).hasSize(3);
    }

    @Test
    void compactionReplacesContextButNeverTranscript() {
        var service = service(30);
        var id = service.create("New chat");
        service.appendMessage(id, "Peon-Dev", MessageKind.CHAT, UserMessage.from("one"));
        service.appendMessage(id, "Peon-Dev", MessageKind.CHAT, AiMessage.from("two"));
        service.appendMessage(id, "Peon-Dev", MessageKind.CHAT, UserMessage.from("three"));

        var summary = List.<ChatMessage>of(UserMessage.from("summary"), AiMessage.from("continue"));
        service.replaceAgentContext(id, "Peon-Dev", summary, "compact");

        var session = service.get(id);
        assertThat(session.transcript()).hasSize(3);
        assertThat(session.agentContext("Peon-Dev")).containsExactlyElementsOf(summary);

        var reopened = service(30).open(id);
        assertThat(reopened.transcript()).hasSize(3);
        assertThat(reopened.agentContext("Peon-Dev")).containsExactlyElementsOf(summary);
    }

    @Test
    void clearIsAnAppendedEventNotAFileRewrite() throws Exception {
        var service = service(30);
        var id = service.create("New chat");
        service.appendMessage(id, "Peon-Dev", MessageKind.CHAT, UserMessage.from("one"));
        service.appendMessage(id, "Peon-Dev", MessageKind.CHAT, AiMessage.from("two"));

        var file = tmp.resolve(id.toString()).resolve(FileSessionRepository.EVENTS_FILE);
        var before = java.nio.file.Files.readString(file);
        service.clear(id);
        var after = java.nio.file.Files.readString(file);

        assertThat(after).startsWith(before);
        assertThat(service.get(id).transcript()).isEmpty();
        assertThat(service.get(id).agentContext("Peon-Dev")).isEmpty();
    }

    @Test
    void reminderDoesNotBecomeTitle() {
        var service = service(30);
        var id = service.create("New chat");
        service.appendMessage(id, "Peon-Dev", MessageKind.REMINDER,
                UserReminder.create("loop breaker"));

        assertThat(service.get(id).title()).isEqualTo("New chat");
        assertThat(service.get(id).transcript().get(0).kind()).isEqualTo(MessageKind.REMINDER);
    }

    @Test
    void openSessionsArePinnedAndClosedSessionsUseLru() {
        var service = service(2);
        var first = service.create("first");
        var second = service.create("second");
        var third = service.create("third");

        assertThat(service.cachedSessionCount()).isEqualTo(3); // all open/pinned
        service.close(first);
        assertThat(service.cachedSessionCount()).isEqualTo(2);
        assertThat(service.open(second)).isNotNull();
        assertThat(service.open(third)).isNotNull();
        // first was evicted from RAM but is still on disk
        assertThat(service.open(first).title()).isEqualTo("first");
    }

    @Test
    void importIsIdempotentAndKeepsAllMessages() {
        var service = service(30);
        var id = new SessionId(java.util.UUID.randomUUID());
        var messages = List.<ChatMessage>of(UserMessage.from("imported question"), AiMessage.from("answer"));

        service.importSession(id, "Imported", "Peon-Dev", messages);
        service.importSession(id, "Imported", "Peon-Dev", messages);

        assertThat(service.list()).hasSize(1);
        var session = service.open(id);
        assertThat(session.transcript()).hasSize(2);
        assertThat(session.agentContext("Peon-Dev")).hasSize(2);
    }

    @Test
    void sessionAgentStoreKeepsJoinedUserMessagesInTranscript() {
        var service = service(30);
        var id = service.create("New chat");
        var memory = new ThreadSafeMemory(new SessionAgentHistoryStore(service, id, "Peon-Dev"));

        memory.add(UserMessage.from("first"));
        memory.add(UserMessage.from("second"));

        var session = service.get(id);
        assertThat(session.transcript()).hasSize(2);
        assertThat(session.agentContext("Peon-Dev")).hasSize(1);
        assertThat(((UserMessage) session.agentContext("Peon-Dev").get(0)).contents()).hasSize(2);
    }

    @Test
    void pinnedSkillsAreSessionEventsAndSurviveReopen() {
        var service = service(30);
        var id = service.create("pins");
        var pin = new PinnedSkill("review", "PROJECT");

        service.pinSkill(id, pin);
        assertThat(service.get(id).pinnedSkills()).containsExactly(pin);

        var reopened = service(30).open(id);
        assertThat(reopened.pinnedSkills()).containsExactly(pin);

        service.unpinSkill(id, pin);
        assertThat(service.get(id).pinnedSkills()).isEmpty();
    }

    @Test
    void eventCodecRoundTripsReminderAttributes() {
        var message = UserReminder.create("guardrail reminder");
        var event = new MessageAdded(7, NOW, "Peon-Dev", MessageKind.REMINDER, message);

        var decoded = (MessageAdded) SessionEventCodec.fromJson(SessionEventCodec.toJson(event));

        assertThat(decoded.seq()).isEqualTo(7);
        assertThat(decoded.kind()).isEqualTo(MessageKind.REMINDER);
        assertThat(UserReminder.isReminder(decoded.message())).isTrue();
    }
}
