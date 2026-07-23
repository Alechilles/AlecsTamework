package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import javax.annotation.Nonnull;

/** Durable positive evidence for one completed coop release. */
public record CompanionCoopReleaseOutcome(
        @Nonnull ProfileId profileId,
        @Nonnull CoopSlotKey sourceSlot,
        @Nonnull SnapshotId sourceSnapshotId,
        @Nonnull NpcAlias targetAlias,
        @Nonnull String targetWorldKey,
        @Nonnull LifecycleRevision lifecycleRevision,
        long slotRevision,
        @Nonnull String spawnReceiptKey,
        long releasedAtMs
) {
    public CompanionCoopReleaseOutcome {
        if (profileId == null || sourceSlot == null || sourceSnapshotId == null
                || targetAlias == null || lifecycleRevision == null
                || slotRevision <= 0) {
            throw new IllegalArgumentException("Complete coop release outcome is required");
        }
        targetWorldKey = requireText(targetWorldKey, "Coop release target world");
        spawnReceiptKey = requireText(spawnReceiptKey, "Coop release spawn receipt");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
