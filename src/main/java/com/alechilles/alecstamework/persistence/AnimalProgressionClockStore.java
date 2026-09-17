package com.alechilles.alecstamework.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Small universe-scoped store for the owner progression clock.
 *
 * <p>Offline segment information preserves grace already consumed before restart. The restarted
 * clock resumes that segment from the new runtime epoch, so server downtime itself never counts.</p>
 */
public final class AnimalProgressionClockStore {
    public static final String FILE_NAME = "animal-progression-clock.json";

    private static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private AnimalProgressionClockStore() {
    }

    @Nonnull
    public static Path resolveFile(@Nonnull Path dataDirectory) {
        if (dataDirectory == null) {
            throw new IllegalArgumentException("Data directory is required");
        }
        return dataDirectory.resolve(FILE_NAME).toAbsolutePath().normalize();
    }

    /** Reads durable counters without treating a missing file as a failure. */
    @Nonnull
    public static LoadResult load(@Nonnull Path dataDirectory) {
        Path file = resolveFile(dataDirectory);
        if (!Files.isRegularFile(file)) {
            return LoadResult.available(Map.of(), 0L);
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return LoadResult.unavailable();
            }
            Document document = GSON.fromJson(raw, Document.class);
            if (document == null) {
                return LoadResult.unavailable();
            }
            LinkedHashMap<UUID, OwnerState> owners = new LinkedHashMap<>();
            if (document.owners != null) {
                for (Map.Entry<String, OwnerDocument> entry : document.owners.entrySet()) {
                    UUID ownerId = parseUuid(entry.getKey());
                    OwnerDocument owner = entry.getValue();
                    if (ownerId == null || owner == null) {
                        continue;
                    }
                    owners.put(ownerId, new OwnerState(
                            Math.max(0L, owner.cumulativeEligibleMs),
                            Math.max(0L, owner.offlineElapsedMs)
                    ));
                }
            }
            return LoadResult.available(owners, Math.max(0L, document.serverCumulativeRuntimeMs));
        } catch (JsonSyntaxException | IllegalStateException exception) {
            return LoadResult.unavailable();
        } catch (Exception exception) {
            return LoadResult.unavailable();
        }
    }

    /** Writes one complete snapshot with atomic replacement when supported by the filesystem. */
    public static boolean save(@Nonnull Path dataDirectory,
                               @Nonnull Map<UUID, OwnerState> owners,
                               long serverCumulativeRuntimeMs) {
        if (dataDirectory == null || owners == null) {
            throw new IllegalArgumentException("Data directory and owner states are required");
        }
        Path file = resolveFile(dataDirectory);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Document document = new Document();
            document.version = CURRENT_VERSION;
            document.serverCumulativeRuntimeMs = Math.max(0L, serverCumulativeRuntimeMs);
            document.owners = new LinkedHashMap<>();
            for (Map.Entry<UUID, OwnerState> entry : owners.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                OwnerDocument owner = new OwnerDocument();
                owner.cumulativeEligibleMs = Math.max(0L, entry.getValue().cumulativeEligibleMs());
                owner.offlineElapsedMs = Math.max(0L, entry.getValue().offlineElapsedMs());
                document.owners.put(entry.getKey().toString(), owner);
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(document) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** A durable counter and diagnostic-only offline segment length. */
    public record OwnerState(long cumulativeEligibleMs, long offlineElapsedMs) {
    }

    /** Indicates whether it is safe for the caller to replace the current file. */
    public record LoadResult(@Nonnull Map<UUID, OwnerState> owners,
                             long serverCumulativeRuntimeMs,
                             boolean available) {
        public LoadResult {
            owners = Map.copyOf(owners);
            serverCumulativeRuntimeMs = Math.max(0L, serverCumulativeRuntimeMs);
        }

        private static LoadResult available(Map<UUID, OwnerState> owners,
                                            long serverCumulativeRuntimeMs) {
            return new LoadResult(owners, serverCumulativeRuntimeMs, true);
        }

        private static LoadResult unavailable() {
            return new LoadResult(Map.of(), 0L, false);
        }
    }

    private static final class Document {
        private Integer version;
        private long serverCumulativeRuntimeMs;
        private LinkedHashMap<String, OwnerDocument> owners;
    }

    private static final class OwnerDocument {
        private long cumulativeEligibleMs;
        private long offlineElapsedMs;
    }
}
