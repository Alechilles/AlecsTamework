package com.alechilles.alecstamework.companion.snapshot;

import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Stable identity of one immutable companion snapshot.
 *
 * @param value snapshot UUID
 */
public record SnapshotId(@Nonnull UUID value) {
    public SnapshotId {
        if (value == null) {
            throw new IllegalArgumentException("Snapshot ID is required");
        }
    }

    /** Creates a new snapshot identity. */
    @Nonnull
    public static SnapshotId create() {
        return new SnapshotId(UUID.randomUUID());
    }

    /** Parses the canonical durable representation. */
    @Nonnull
    public static SnapshotId parse(@Nonnull String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Snapshot ID is required");
        }
        return new SnapshotId(UUID.fromString(value.trim()));
    }

    @Override
    @Nonnull
    public String toString() {
        return value.toString();
    }
}
