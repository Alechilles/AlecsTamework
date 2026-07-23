package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import javax.annotation.Nonnull;

/** Durable positive evidence for one completed coop capture. */
public record CompanionCoopCaptureOutcome(
        @Nonnull ProfileId profileId,
        @Nonnull CoopSlotKey slotKey,
        @Nonnull SnapshotId snapshotId,
        @Nonnull LifecycleRevision lifecycleRevision,
        long slotRevision,
        @Nonnull String retirementReceiptKey,
        long capturedAtMs
) {
    public CompanionCoopCaptureOutcome {
        if (profileId == null || slotKey == null || snapshotId == null
                || lifecycleRevision == null || slotRevision <= 0
                || retirementReceiptKey == null
                || retirementReceiptKey.isBlank()) {
            throw new IllegalArgumentException("Complete coop capture outcome is required");
        }
        retirementReceiptKey = retirementReceiptKey.trim();
    }
}
