package com.alechilles.alecstamework.persistence.migration;

import java.nio.file.Path;
import javax.annotation.Nonnull;

/** Verified replacement target selected by the one offline startup boundary. */
public record PublicPersistenceTarget(
        @Nonnull Path databasePath,
        @Nonnull Origin origin
) {
    public PublicPersistenceTarget {
        if (databasePath == null || origin == null) {
            throw new IllegalArgumentException(
                    "Replacement target path and origin are required"
            );
        }
        databasePath = databasePath.toAbsolutePath().normalize();
    }

    public enum Origin {
        EXISTING,
        FRESH,
        IMPORTED_PUBLIC
    }
}
