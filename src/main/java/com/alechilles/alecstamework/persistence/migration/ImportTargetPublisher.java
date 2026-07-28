package com.alechilles.alecstamework.persistence.migration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Flushes, atomically publishes, and reports one already-verified temporary target. */
final class ImportTargetPublisher {
    void publish(@Nonnull Path temporaryTarget, @Nonnull Path finalTarget) throws Exception {
        if (temporaryTarget == null || finalTarget == null) {
            throw new IllegalArgumentException("Temporary and final target paths are required");
        }
        forceFile(temporaryTarget);
        try {
            Files.move(temporaryTarget, finalTarget, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            throw new IllegalStateException("atomic_import_publication_unavailable", failure);
        }
        forceDirectoryBestEffort(finalTarget.getParent());
    }

    @Nonnull
    Optional<Path> writeReport(
            @Nonnull Path target,
            @Nonnull PublicImportManifest manifest
    ) {
        Path report = target.resolveSibling(
                "persistence-import-" + manifest.importId() + ".json"
        );
        if (Files.isRegularFile(report)) {
            return Optional.of(report);
        }
        Path staging = report.resolveSibling(report.getFileName() + ".writing");
        try {
            Files.deleteIfExists(staging);
            JsonObject json = new JsonObject();
            json.addProperty("status", "PUBLISHED");
            json.addProperty("target", target.getFileName().toString());
            json.addProperty("importId", manifest.importId());
            json.addProperty("sourceSha256", manifest.sourceSha256());
            json.addProperty("sourceSchemaVersion", manifest.sourceSchemaVersion());
            json.addProperty("importerVersion", manifest.importerVersion());
            json.addProperty("completedAtMs", manifest.completedAtMs());
            json.add("counts", JsonParser.parseString(manifest.countsJson()));
            Files.writeString(
                    staging,
                    json.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            forceFile(staging);
            Files.move(staging, report, StandardCopyOption.ATOMIC_MOVE);
            forceDirectoryBestEffort(report.getParent());
            return Optional.of(report);
        } catch (Exception failure) {
            try {
                Files.deleteIfExists(staging);
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            return Optional.empty();
        }
    }

    private void forceFile(Path path) throws Exception {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void forceDirectoryBestEffort(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception ignored) {
            // Windows does not expose a portable directory fsync; the target file itself is forced.
        }
    }
}
