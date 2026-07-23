package com.alechilles.alecstamework.persistence.migration;

import javax.annotation.Nonnull;

/** Deterministic internal manifest committed with imported canonical rows. */
record PublicImportManifest(
        @Nonnull String importId,
        @Nonnull String sourceSha256,
        int sourceSchemaVersion,
        int importerVersion,
        @Nonnull String sourceSnapshotName,
        @Nonnull String countsJson,
        long completedAtMs
) {
    PublicImportManifest {
        if (importId == null || sourceSha256 == null || sourceSha256.length() != 64
                || sourceSchemaVersion < 2 || sourceSchemaVersion > 4
                || importerVersion <= 0 || sourceSnapshotName == null
                || sourceSnapshotName.isBlank() || countsJson == null) {
            throw new IllegalArgumentException("Complete public import manifest required");
        }
    }
}
