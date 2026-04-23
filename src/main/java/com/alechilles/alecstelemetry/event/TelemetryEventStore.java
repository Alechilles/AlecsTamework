package com.alechilles.alecstelemetry.event;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Durable local queue for one project's non-crash telemetry events.
 */
public final class TelemetryEventStore {

    public record PendingEvent(@Nonnull Path path, @Nonnull String payload) {
    }

    private final Path pendingDirectory;
    private final int maxPendingEvents;
    private final HytaleLogger logger;

    public TelemetryEventStore(@Nonnull Path pendingDirectory,
                               int maxPendingEvents,
                               @Nullable HytaleLogger logger) {
        this.pendingDirectory = pendingDirectory;
        this.maxPendingEvents = Math.max(1, maxPendingEvents);
        this.logger = logger;
    }

    public synchronized boolean persist(@Nonnull TelemetryEventEnvelope event) {
        try {
            Files.createDirectories(pendingDirectory);
            pruneOldestToLimit(maxPendingEvents - 1);
            String fileName = String.format(
                    Locale.ROOT,
                    "%013d-%s-%s.json",
                    System.currentTimeMillis(),
                    sanitizeToken(event.eventType()),
                    sanitizeToken(event.eventId())
            );
            writeAtomically(pendingDirectory.resolve(fileName), event.toJson());
            return true;
        } catch (Exception ex) {
            logWarning("Failed to persist telemetry event.", ex);
            return false;
        }
    }

    @Nonnull
    public synchronized List<PendingEvent> listPendingEvents(int limit) {
        List<Path> files = sortedPendingFiles();
        int cappedLimit = limit <= 0 ? files.size() : Math.min(limit, files.size());
        ArrayList<PendingEvent> events = new ArrayList<>(cappedLimit);
        for (int i = 0; i < cappedLimit; i++) {
            Path path = files.get(i);
            try {
                events.add(new PendingEvent(path, Files.readString(path, StandardCharsets.UTF_8)));
            } catch (Exception ex) {
                logWarning("Failed to read telemetry event file: " + path, ex);
            }
        }
        return List.copyOf(events);
    }

    public synchronized int pendingCount() {
        return sortedPendingFiles().size();
    }

    public synchronized boolean delete(@Nonnull Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (Exception ex) {
            logWarning("Failed to delete telemetry event file: " + path, ex);
            return false;
        }
    }

    @Nonnull
    private List<Path> sortedPendingFiles() {
        if (!Files.isDirectory(pendingDirectory)) {
            return List.of();
        }
        try (var stream = Files.list(pendingDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName() == null ? "" : path.getFileName().toString();
                        return name.endsWith(".json");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (Exception ex) {
            logWarning("Failed to list pending telemetry events in " + pendingDirectory, ex);
            return List.of();
        }
    }

    private void pruneOldestToLimit(int limit) {
        int safeLimit = Math.max(0, limit);
        List<Path> files = sortedPendingFiles();
        for (int i = 0; i < files.size() - safeLimit; i++) {
            try {
                Files.deleteIfExists(files.get(i));
            } catch (Exception ex) {
                logWarning("Failed to prune old telemetry event: " + files.get(i), ex);
            }
        }
    }

    private void writeAtomically(@Nonnull Path destination, @Nonnull String payload) throws Exception {
        String fileName = destination.getFileName() == null ? "event.json" : destination.getFileName().toString();
        Path temp = pendingDirectory.resolve(fileName + ".tmp");
        Files.writeString(temp, payload, StandardCharsets.UTF_8);
        try {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicMoveFailure) {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Nonnull
    private static String sanitizeToken(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return "unknown";
        }
        StringBuilder out = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_') {
                out.append(c);
            }
        }
        return out.isEmpty() ? "unknown" : out.toString();
    }

    private void logWarning(@Nonnull String message, @Nullable Throwable throwable) {
        if (logger == null) {
            return;
        }
        if (throwable == null) {
            logger.at(Level.WARNING).log(message);
            return;
        }
        logger.at(Level.WARNING).withCause(throwable).log(message);
    }
}
