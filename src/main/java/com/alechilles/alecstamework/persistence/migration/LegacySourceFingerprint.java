package com.alechilles.alecstamework.persistence.migration;

import javax.annotation.Nonnull;

/** Immutable evidence captured from one consistent read-only SQLite backup. */
public record LegacySourceFingerprint(
        @Nonnull String snapshotSha256,
        long sourceSizeBytes,
        long sourceModifiedAtMs
) {
    public LegacySourceFingerprint {
        if (snapshotSha256 == null || snapshotSha256.length() != 64) {
            throw new IllegalArgumentException("Snapshot fingerprint must be a SHA-256 value");
        }
        if (sourceSizeBytes < 0) {
            throw new IllegalArgumentException("Source size cannot be negative");
        }
    }
}
