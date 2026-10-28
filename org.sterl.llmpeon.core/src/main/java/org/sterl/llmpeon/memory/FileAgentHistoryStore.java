package org.sterl.llmpeon.memory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FileAgentHistoryStore implements AgentHistoryStore {

    private final Path historyFile;

    public FileAgentHistoryStore(Path historyFile) {
        this.historyFile = historyFile;
    }

    @Override
    public List<ChatMessage> load() {
        if (!Files.isRegularFile(historyFile)) return List.of();

        var messages = new ArrayList<ChatMessage>();
        try {
            for (String line : Files.readAllLines(historyFile)) {
                if (!line.isBlank()) messages.add(ChatMessageDeserializer.messageFromJson(line));
            }
            return messages;
        } catch (Exception e) {
            log.error("Failed to load history {}; deleting corrupt file", historyFile.getFileName(), e);
            deleteIfExists(historyFile);
            return List.of();
        }
    }

    @Override
    public void append(ChatMessage message) throws IOException {
        append(List.of(message));
    }

    @Override
    public void append(List<ChatMessage> messages) throws IOException {
        if (messages == null || messages.isEmpty()) return;
        Files.createDirectories(historyFile.getParent());
        Files.writeString(historyFile, toJsonl(messages), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public void persist(List<ChatMessage> messages) throws IOException {
        Files.createDirectories(historyFile.getParent());
        if (messages == null || messages.isEmpty()) {
            Files.deleteIfExists(historyFile);
            return;
        }

        var tmp = Files.createTempFile(historyFile.getParent(), historyFile.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, toJsonl(messages), StandardOpenOption.TRUNCATE_EXISTING);
            moveReplace(tmp, historyFile);
        } catch (IOException | RuntimeException e) {
            deleteIfExists(tmp);
            throw e;
        }
    }

    @Override
    public void clear() throws IOException {
        Files.deleteIfExists(historyFile);
    }

    public Path historyFile() {
        return historyFile;
    }

    @Override
    public java.util.Optional<Path> backingFile() {
        return java.util.Optional.of(historyFile);
    }

    private static String toJsonl(List<ChatMessage> messages) {
        var content = new StringBuilder();
        for (var message : messages) {
            content.append(ChatMessageSerializer.messageToJson(message)).append(System.lineSeparator());
        }
        return content.toString();
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteIfExists(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best effort cleanup; the next write rewrites the file.
        }
    }
}
