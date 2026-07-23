package com.alechilles.alecstamework.persistence.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.annotation.Nonnull;

/** Canonical filenames for the legacy source and replacement persistence lineage. */
public final class PersistenceFiles {
    public static final String LEGACY_DATABASE = "tamework.sqlite";
    public static final String REPLACEMENT_DATABASE = "tamework-state.sqlite";
    public static final String ENGINE_MANIFEST = "persistence-engine.json";

    private PersistenceFiles() {
    }

    /** Resolves the replacement database without creating or modifying any file. */
    @Nonnull
    public static Path replacementDatabase(@Nonnull Path dataDirectory) {
        return requireDirectory(dataDirectory).resolve(REPLACEMENT_DATABASE);
    }

    /** Resolves the legacy import source without creating or modifying any file. */
    @Nonnull
    public static Path legacyDatabase(@Nonnull Path dataDirectory) {
        return requireDirectory(dataDirectory).resolve(LEGACY_DATABASE);
    }

    /** Returns a file size for diagnostics without turning absence or read failure into startup failure. */
    public static long sizeOrZero(@Nonnull Path path) {
        try {
            return path != null && Files.exists(path) ? Files.size(path) : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** Backs up and removes one SQLite database plus its WAL and shared-memory sidecars. */
    public static void backupAndRemoveSqliteFamily(
            @Nonnull Path database,
            @Nonnull String backupFilename
    ) throws IOException {
        Path backup = database.resolveSibling(backupFilename);
        Files.copy(database, backup, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(database);
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-wal"));
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-shm"));
    }

    private static Path requireDirectory(Path dataDirectory) {
        if (dataDirectory == null) {
            throw new IllegalArgumentException("Persistence data directory is required");
        }
        return dataDirectory.toAbsolutePath().normalize();
    }
}
