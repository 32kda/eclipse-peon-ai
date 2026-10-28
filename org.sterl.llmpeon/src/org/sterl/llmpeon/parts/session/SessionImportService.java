package org.sterl.llmpeon.parts.session;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.sterl.llmpeon.session.SessionId;
import org.sterl.llmpeon.session.SessionService;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;

/**
 * Imports the old per-agent {@code *-history.jsonl} files into one session per file.
 *
 * <p>Deterministic session IDs make the import idempotent; the original files are never modified.</p>
 */
public final class SessionImportService {

    private static final ILog LOG = Platform.getLog(SessionImportService.class);
    private static final String HISTORY_SUFFIX = "-history.jsonl";

    private SessionImportService() {
    }

    public static void importLegacyAsync(SessionService sessions, Path stateDir) {
        Job.create("Import legacy chat histories", monitor -> {
            importLegacy(sessions, stateDir);
            return Status.OK_STATUS;
        }).schedule();
    }

    static void importLegacy(SessionService sessions, Path stateDir) {
        if (stateDir == null || !Files.isDirectory(stateDir)) return;
        try (var files = Files.list(stateDir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(HISTORY_SUFFIX))
                    .forEach(path -> importFile(sessions, path));
        } catch (Exception e) {
            LOG.error("Failed to scan legacy chat histories in " + stateDir, e);
        }
    }

    private static void importFile(SessionService sessions, Path historyFile) {
        var id = legacyId(historyFile);
        if (sessions.exists(id)) return;

        try {
            var messages = load(historyFile);
            if (messages.isEmpty()) return;
            var agent = agentName(historyFile);
            sessions.importSession(id, title(messages, agent), agent, messages);
        } catch (Exception e) {
            LOG.error("Failed to import legacy history " + historyFile, e);
        }
    }

    private static List<ChatMessage> load(Path file) throws Exception {
        var result = new java.util.ArrayList<ChatMessage>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                result.add(ChatMessageDeserializer.messageFromJson(line));
            } catch (Exception e) {
                LOG.warn("Skipping corrupt line in legacy history " + file, e);
            }
        }
        return result;
    }

    private static SessionId legacyId(Path file) {
        var name = "peon-legacy:" + file.toAbsolutePath().normalize();
        return new SessionId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    }

    private static String agentName(Path file) {
        var name = file.getFileName().toString();
        return name.endsWith(HISTORY_SUFFIX)
                ? name.substring(0, name.length() - HISTORY_SUFFIX.length())
                : name;
    }

    private static String title(List<ChatMessage> messages, String agent) {
        for (var message : messages) {
            if (message instanceof UserMessage user && !org.sterl.llmpeon.session.UserReminder.isReminder(user)) {
                var text = userText(user);
                if (text != null && !text.isBlank()) {
                    var title = SessionService.deriveTitle(text);
                    if (title != null) return title;
                }
            }
        }
        return agent + " (imported)";
    }

    private static String userText(UserMessage user) {
        if (user.hasSingleText()) return user.singleText();
        return user.contents().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .findFirst().orElse(null);
    }
}
