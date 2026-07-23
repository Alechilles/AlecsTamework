package com.alechilles.alecstamework.companion.restoration;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import javax.annotation.Nonnull;

/** Self-contained durable restoration result published through the shared outbox. */
public record CompanionRestorationOutcome(
        @Nonnull ProfileId profileId,
        @Nonnull SnapshotId sourceSnapshotId,
        @Nonnull NpcAlias targetAlias,
        @Nonnull String targetWorldKey,
        @Nonnull LifecycleRevision lifecycleRevision,
        @Nonnull String spawnReceiptKey,
        long restoredAtMs
) {
    public CompanionRestorationOutcome {
        if (profileId == null || sourceSnapshotId == null || targetAlias == null
                || lifecycleRevision == null) {
            throw new IllegalArgumentException("Complete restoration outcome is required");
        }
        targetWorldKey = requireText(targetWorldKey, "Restoration outcome world");
        spawnReceiptKey = requireText(spawnReceiptKey, "Restoration outcome receipt");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
