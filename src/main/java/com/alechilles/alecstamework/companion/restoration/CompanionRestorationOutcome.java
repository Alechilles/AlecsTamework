package com.alechilles.alecstamework.companion.restoration;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Self-contained durable restoration result published through the shared outbox. */
public record CompanionRestorationOutcome(
        @Nonnull ProfileId profileId,
        @Nonnull SnapshotId sourceSnapshotId,
        @Nonnull LifecycleState targetState,
        @Nullable NpcAlias targetAlias,
        @Nullable String targetWorldKey,
        @Nonnull LifecycleRevision lifecycleRevision,
        @Nullable String spawnReceiptKey,
        long restoredAtMs
) {
    /** Compatibility constructor for the existing active restoration event. */
    public CompanionRestorationOutcome(
            @Nonnull ProfileId profileId,
            @Nonnull SnapshotId sourceSnapshotId,
            @Nonnull NpcAlias targetAlias,
            @Nonnull String targetWorldKey,
            @Nonnull LifecycleRevision lifecycleRevision,
            @Nonnull String spawnReceiptKey,
            long restoredAtMs
    ) {
        this(
                profileId,
                sourceSnapshotId,
                LifecycleState.ACTIVE,
                targetAlias,
                targetWorldKey,
                lifecycleRevision,
                spawnReceiptKey,
                restoredAtMs
        );
    }

    public CompanionRestorationOutcome {
        if (profileId == null || sourceSnapshotId == null
                || targetState == null || lifecycleRevision == null) {
            throw new IllegalArgumentException("Complete restoration outcome is required");
        }
        if (targetState == LifecycleState.ACTIVE) {
            if (targetAlias == null) {
                throw new IllegalArgumentException(
                        "Active restoration outcome alias is required"
                );
            }
            targetWorldKey = requireText(
                    targetWorldKey, "Restoration outcome world"
            );
            spawnReceiptKey = requireText(
                    spawnReceiptKey, "Restoration outcome receipt"
            );
        } else if (targetState == LifecycleState.PROVISIONED_DORMANT) {
            if (targetAlias != null || targetWorldKey != null
                    || spawnReceiptKey != null) {
                throw new IllegalArgumentException(
                        "Dormant restoration outcome cannot declare a live target"
                );
            }
        } else {
            throw new IllegalArgumentException(
                    "Restoration outcome target is unsupported"
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
