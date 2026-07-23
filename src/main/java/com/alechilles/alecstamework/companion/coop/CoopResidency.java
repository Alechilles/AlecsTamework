package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonical detail connecting one profile and state snapshot to one occupied coop slot. */
public record CoopResidency(
        @Nonnull CoopSlotKey slotKey,
        @Nonnull ProfileId profileId,
        @Nullable NpcAlias housedNpcAlias,
        @Nonnull SnapshotId snapshotId,
        long capturedAtMs,
        long updatedAtMs
) {
    public CoopResidency {
        if (slotKey == null || profileId == null || snapshotId == null) {
            throw new IllegalArgumentException("Complete coop residency detail is required");
        }
    }
}
