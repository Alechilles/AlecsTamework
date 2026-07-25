package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Self-contained durable result of one exact paid revival. */
public record PaidRevivalOutcome(
        @Nonnull ProfileId profileId,
        @Nonnull SnapshotId sourceSnapshotId,
        @Nonnull NpcAlias liveAlias,
        @Nonnull String worldKey,
        @Nonnull LifecycleRevision lifecycleRevision,
        @Nonnull String configRevision,
        @Nonnull List<RevivalCostItem> exactCost,
        @Nonnull String chargeReceiptKey,
        @Nonnull String spawnReceiptKey,
        @Nullable TimedSummonSessionId timedSessionId,
        long revivedAtMs
) {
    public PaidRevivalOutcome {
        if (profileId == null || sourceSnapshotId == null
                || liveAlias == null || lifecycleRevision == null
                || exactCost == null
                || exactCost.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Complete paid revival outcome is required"
            );
        }
        worldKey = text(worldKey, "Paid revival outcome world");
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
}
