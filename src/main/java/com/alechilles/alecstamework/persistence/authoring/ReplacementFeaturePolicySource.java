package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Supplies one immutable role/config snapshot for evidence authoring. */
@FunctionalInterface
public interface ReplacementFeaturePolicySource {
    /**
     * Returns current effective role policy, or null when current assets
     * cannot prove a complete policy snapshot.
     */
    @Nullable
    RolePolicySnapshot resolve(@Nonnull String roleId);

    /** Frozen settings shared by provisioning, timed summon, and revival. */
    record RolePolicySnapshot(
            @Nonnull String roleId,
            @Nullable String configId,
            @Nonnull String configRevision,
            int globalOwnerLimit,
            int perWorldOwnerLimit,
            boolean timedSummoningEnabled,
            @Nonnull TimedSummonPolicy timedSummonPolicy,
            boolean paidRevivalEnabled,
            long revivalCooldownMs,
            @Nonnull List<RevivalCostItem> revivalCost,
            @Nullable String insufficientCostMessage
    ) {
        public RolePolicySnapshot {
            roleId = text(roleId, "Feature policy role");
            configId = normalize(configId);
            configRevision = text(
                    configRevision, "Feature policy revision"
            );
            if (globalOwnerLimit < 0 || perWorldOwnerLimit < 0
                    || timedSummonPolicy == null
                    || revivalCooldownMs < 0 || revivalCost == null) {
                throw new IllegalArgumentException(
                        "Complete nonnegative feature policy is required"
                );
            }
            revivalCost = List.copyOf(revivalCost);
            insufficientCostMessage = normalize(insufficientCostMessage);
        }
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
