package com.alechilles.alecstamework.persistence.kernel;

import java.nio.file.Path;
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

    private static Path requireDirectory(Path dataDirectory) {
        if (dataDirectory == null) {
            throw new IllegalArgumentException("Persistence data directory is required");
        }
        return dataDirectory.toAbsolutePath().normalize();
    }
}
