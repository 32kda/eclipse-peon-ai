package org.sterl.llmpeon.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * Simple append-only file repository: one {@code events.jsonl} per session directory.
 *
 * <p>No event is ever rewritten. The materialized session is rebuilt by replaying the log.</p>
 */
@Slf4j
public class FileSessionRepository implements SessionRepository {

    static final String EVENTS_FILE = "events.jsonl";

    private final Path root;

    public FileSessionRepository(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    @Override
    public void append(SessionId id, SessionEvent event) {
        try {
            var dir = sessionDir(id);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(EVENTS_FILE), SessionEventCodec.toJson(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to append session event for " + id, e);
        }
    }

    @Override
    public List<SessionEvent> loadEvents(SessionId id) {
        var file = sessionDir(id).resolve(EVENTS_FILE);
        if (!Files.isRegularFile(file)) return List.of();

        var result = new ArrayList<SessionEvent>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) result.add(SessionEventCodec.fromJson(line));
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load session events for " + id, e);
        }
    }

    @Override
    public List<SessionSummary> list() {
        if (!Files.isDirectory(root)) return List.of();

        var result = new ArrayList<SessionSummary>();
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                    .forEach(dir -> {
                        try {
                            var id = SessionId.parse(dir.getFileName().toString());
                            var events = loadEvents(id);
                            if (events.isEmpty()) return;
                            var session = SessionProjector.withDefaults(id, events, Instant.EPOCH);
                            result.add(session.summary());
                        } catch (RuntimeException e) {
                            log.warn("Skipping unreadable session directory " + dir, e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list sessions under " + root, e);
        }
        result.sort(Comparator.comparing(SessionSummary::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    @Override
    public boolean exists(SessionId id) {
        return Files.isRegularFile(sessionDir(id).resolve(EVENTS_FILE));
    }

    @Override
    public void delete(SessionId id) {
        var dir = sessionDir(id);
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to delete " + path, e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete session " + id, e);
        }
    }

    private Path sessionDir(SessionId id) {
        return root.resolve(id.toString());
    }
}
