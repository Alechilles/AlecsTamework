package com.alechilles.alecstamework.persistence.control;

import javax.annotation.Nonnull;

/** Durable engine-selection evidence guarded by the process lineage lock. */
public record PersistenceEngineManifest(
        int formatVersion,
        @Nonnull PersistenceEngineLineage lineage,
        boolean startupComplete,
        boolean cleanShutdown,
        long updatedAtMs
) {
    public static final int CURRENT_FORMAT = 1;

    public PersistenceEngineManifest {
        if (formatVersion != CURRENT_FORMAT || lineage == null) {
            throw new IllegalArgumentException(
                    "Unsupported persistence engine manifest"
            );
        }
    }
}
