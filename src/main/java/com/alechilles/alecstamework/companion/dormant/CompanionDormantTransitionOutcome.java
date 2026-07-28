package com.alechilles.alecstamework.companion.dormant;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import javax.annotation.Nonnull;

/** Self-contained durable death-or-lost result published through the shared outbox. */
public record CompanionDormantTransitionOutcome(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleState state,
        @Nonnull SnapshotId snapshotId,
        @Nonnull LifecycleRevision lifecycleRevision,
        @Nonnull String sourceReceiptKey,
        long transitionedAtMs
) {
    public CompanionDormantTransitionOutcome {
        if (profileId == null || state == null || snapshotId == null
                || lifecycleRevision == null) {
            throw new IllegalArgumentException("Complete dormant transition outcome is required");
        }
        if (state != LifecycleState.DEAD_REVIVABLE
                && state != LifecycleState.LOST) {
            throw new IllegalArgumentException("Dormant outcome requires death or lost state");
        }
        if (sourceReceiptKey == null || sourceReceiptKey.isBlank()) {
            throw new IllegalArgumentException("Dormant source receipt is required");
        }
        sourceReceiptKey = sourceReceiptKey.trim();
    }
}
