package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import javax.annotation.Nonnull;

/** Self-contained durable captured-artifact release result for the shared outbox. */
public record CompanionCaptureReleaseOutcome(
        @Nonnull ProfileId profileId,
        @Nonnull SnapshotId sourceSnapshotId,
        @Nonnull NpcAlias targetAlias,
        @Nonnull String targetWorldKey,
        @Nonnull LifecycleRevision lifecycleRevision,
        @Nonnull String inventoryReceiptKey,
        @Nonnull String spawnReceiptKey,
        long releasedAtMs
) {
    public CompanionCaptureReleaseOutcome {
        if (profileId == null || sourceSnapshotId == null
                || targetAlias == null || lifecycleRevision == null) {
            throw new IllegalArgumentException(
                    "Complete captured-artifact release outcome is required"
            );
        }
        targetWorldKey = requireText(
                targetWorldKey,
                "Captured-artifact release world"
        );
        inventoryReceiptKey = requireText(
                inventoryReceiptKey,
                "Captured-artifact release inventory receipt"
        );
        spawnReceiptKey = requireText(
                spawnReceiptKey,
                "Captured-artifact release spawn receipt"
        );
        if (inventoryReceiptKey.equals(spawnReceiptKey)) {
            throw new IllegalArgumentException(
                    "Captured-artifact outcome receipts must be distinct"
            );
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
