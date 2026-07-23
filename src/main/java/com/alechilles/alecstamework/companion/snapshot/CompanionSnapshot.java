package com.alechilles.alecstamework.companion.snapshot;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import javax.annotation.Nonnull;

/**
 * Immutable versioned companion state evidence; never a lifecycle authority.
 *
 * @param snapshotId immutable snapshot identity
 * @param profileId stable companion profile
 * @param kind registered snapshot kind
 * @param payloadVersion positive codec version
 * @param payloadJson exact JSON payload
 * @param payloadHash SHA-256 of the exact payload
 * @param sourceLifecycleRevision lifecycle revision from which the payload was captured
 * @param current whether this is the current snapshot for its profile and kind
 * @param createdAtMs signed persisted creation time
 */
public record CompanionSnapshot(@Nonnull SnapshotId snapshotId,
                                @Nonnull ProfileId profileId,
                                @Nonnull SnapshotKind kind,
                                int payloadVersion,
                                @Nonnull String payloadJson,
                                @Nonnull Sha256Hash payloadHash,
                                @Nonnull LifecycleRevision sourceLifecycleRevision,
                                boolean current,
                                long createdAtMs) {
    public CompanionSnapshot {
        if (snapshotId == null || profileId == null || kind == null
                || payloadJson == null || payloadHash == null
                || sourceLifecycleRevision == null) {
            throw new IllegalArgumentException("Complete snapshot evidence is required");
        }
        if (payloadVersion <= 0) {
            throw new IllegalArgumentException("Snapshot payload version must be positive");
        }
        if (!payloadHash.matchesUtf8(payloadJson)) {
            throw new IllegalArgumentException("Snapshot SHA-256 does not match its payload");
        }
    }
}
