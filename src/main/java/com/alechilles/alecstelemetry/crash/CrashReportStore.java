package com.alechilles.alecstelemetry.crash;

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
 * Durable local queue for one project's crash reports.
 */
public final class CrashReportStore {

    /**
     * Result for one persist attempt.
     */
    public enum WriteResult {
        WRITTEN,
        UPDATED,
        FAILED
    }

    /**
     * Pending file plus payload contents.
     */
    public record PendingReport(@Nonnull Path path, @Nonnull String payload) {
    }

    private final Path pendingDirectory;
    private final int maxPendingReports;
    private final HytaleLogger logger;

    public CrashReportStore(@Nonnull Path pendingDirectory,
                            int maxPendingReports,
                            @Nullable HytaleLogger logger) {
        this.pendingDirectory = pendingDirectory;
        this.maxPendingReports = Math.max(1, maxPendingReports);
        this.logger = logger;
    }

    @Nonnull
    public synchronized WriteResult persist(@Nonnull CrashReportEnvelope report) {
        if (report.fingerprint().isBlank()) {
            return WriteResult.FAILED;
        }
        try {
            Files.createDirectories(pendingDirectory);
            String sanitizedFingerprint = sanitizeToken(report.fingerprint());
            Path existing = findByFingerprint(sanitizedFingerprint);
            if (existing != null) {
                return updateExisting(existing, report);
            }
            pruneOldestToLimit(maxPendingReports - 1);
            String fileName = String.format(
                    Locale.ROOT,
                    "%013d-%s-%s.json",
                    System.currentTimeMillis(),
                    sanitizedFingerprint,
                    sanitizeToken(report.reportId())
            );
            Path destination = pendingDirectory.resolve(fileName);
            writeAtomically(destination, report.toJson());
            return WriteResult.WRITTEN;
        } catch (Exception ex) {
            logWarning("Failed to persist crash report.", ex);
            return WriteResult.FAILED;
        }
    }

    @Nonnull
    public synchronized List<PendingReport> listPendingReports(int limit) {
        List<Path> files = sortedPendingFiles();
        int cappedLimit = limit <= 0 ? files.size() : Math.min(limit, files.size());
        ArrayList<PendingReport> reports = new ArrayList<>(cappedLimit);
        for (int i = 0; i < cappedLimit; i++) {
            Path path = files.get(i);
            try {
                reports.add(new PendingReport(path, Files.readString(path, StandardCharsets.UTF_8)));
            } catch (Exception ex) {
                logWarning("Failed to read crash report file: " + path, ex);
            }
        }
        return List.copyOf(reports);
    }

    public synchronized int pendingCount() {
        return sortedPendingFiles().size();
    }

    public synchronized boolean delete(@Nonnull Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (Exception ex) {
            logWarning("Failed to delete crash report file: " + path, ex);
            return false;
        }
    }

    @Nullable
    private Path findByFingerprint(@Nonnull String fingerprint) {
        String marker = "-" + fingerprint + "-";
        for (Path path : sortedPendingFiles()) {
            String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
            if (fileName.contains(marker)) {
                return path;
            }
        }
        return null;
    }

    @Nonnull
    private WriteResult updateExisting(@Nonnull Path existingPath, @Nonnull CrashReportEnvelope incoming) {
        try {
            CrashReportEnvelope current = CrashReportEnvelope.fromJson(Files.readString(existingPath, StandardCharsets.UTF_8));
            CrashReportEnvelope merged = current.mergeDuplicateOccurrence(incoming);
            writeAtomically(existingPath, merged.toJson());
            return WriteResult.UPDATED;
        } catch (Exception ex) {
            logWarning("Failed to update existing crash report " + existingPath + ".", ex);
            return WriteResult.FAILED;
        }
    }

    private void writeAtomically(@Nonnull Path destination, @Nonnull String payload) throws Exception {
        String fileName = destination.getFileName() == null ? "report.json" : destination.getFileName().toString();
        Path temp = pendingDirectory.resolve(fileName + ".tmp");
        Files.writeString(temp, payload, StandardCharsets.UTF_8);
        try {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicMoveFailure) {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void pruneOldestToLimit(int limit) {
        int safeLimit = Math.max(0, limit);
        List<Path> files = sortedPendingFiles();
        for (int i = 0; i < files.size() - safeLimit; i++) {
            Path oldest = files.get(i);
            try {
                Files.deleteIfExists(oldest);
            } catch (Exception ex) {
                logWarning("Failed to prune old crash report: " + oldest, ex);
            }
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
            logWarning("Failed to list pending crash reports in " + pendingDirectory, ex);
            return List.of();
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
