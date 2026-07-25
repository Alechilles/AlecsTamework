package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Self-contained durable result of one exact paid revival. */
public record PaidRevivalOutcome(
        @Nonnull String callerNamespace,
        @Nonnull String callerIdempotencyKey,
        @Nonnull OwnerId ownerId,
        @Nonnull String commandFamilyId,
        @Nonnull CommandRosterSlotId slotId,
        @Nonnull ProfileId profileId,
        @Nonnull SnapshotId sourceSnapshotId,
        @Nonnull NpcAlias liveAlias,
        @Nonnull String worldKey,
        @Nonnull LifecycleRevision lifecycleRevision,
        @Nullable String configId,
        @Nonnull String configRevision,
        @Nonnull List<RevivalCostItem> exactCost,
        @Nonnull String chargeReceiptKey,
        @Nonnull String spawnReceiptKey,
        @Nullable TimedSummonSessionId timedSessionId,
        long revivedAtMs
) {
    public PaidRevivalOutcome {
        callerNamespace = text(
                callerNamespace, "Paid revival outcome caller namespace"
        );
        callerIdempotencyKey = text(
                callerIdempotencyKey,
                "Paid revival outcome caller idempotency key"
        );
        if (ownerId == null || slotId == null || profileId == null
                || sourceSnapshotId == null
                || liveAlias == null || lifecycleRevision == null
                || exactCost == null
                || exactCost.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Complete paid revival outcome is required"
            );
        }
        commandFamilyId = text(
                commandFamilyId, "Paid revival outcome command family"
        );
        worldKey = text(worldKey, "Paid revival outcome world");
        configId = normalize(configId);
        configRevision = text(
                configRevision, "Paid revival outcome config revision"
        );
        exactCost = List.copyOf(exactCost);
        chargeReceiptKey = text(
                chargeReceiptKey,
                "Paid revival outcome charge receipt"
        );
        spawnReceiptKey = text(
                spawnReceiptKey,
                "Paid revival outcome spawn receipt"
        );
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
