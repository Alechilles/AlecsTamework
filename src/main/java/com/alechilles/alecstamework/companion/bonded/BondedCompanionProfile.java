package com.alechilles.alecstamework.companion.bonded;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable canonical profile for one bonded companion. */
public record BondedCompanionProfile(
        @Nonnull String profileId,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nonnull String familyId,
        @Nonnull String roleId,
        @Nonnull BondedCompanionState state,
        long revision,
        @Nonnull BondedCompanionSnapshot snapshot,
        @Nullable BondedCompanionLease activeLease,
        long summonCooldownUntilMs,
        @Nullable Long diedAtMs,
        long reviveCount,
        @Nullable String lastOperationId
) {
    public BondedCompanionProfile {
        profileId = text(profileId, "profileId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        rosterId = text(rosterId, "rosterId");
        familyId = text(familyId, "familyId");
        roleId = text(roleId, "roleId");
        state = Objects.requireNonNull(state, "state");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        lastOperationId = optional(lastOperationId);
        if (revision < 0L || reviveCount < 0L) {
            throw new IllegalArgumentException("profile counters cannot be negative");
        }
        if ((state == BondedCompanionState.ACTIVE) != (activeLease != null)) {
            throw new IllegalArgumentException(
                    "only ACTIVE profiles carry a lease"
            );
        }
        if ((state == BondedCompanionState.DEAD) != (diedAtMs != null)) {
            throw new IllegalArgumentException(
                    "only DEAD profiles carry a death timestamp"
            );
        }
    }

    boolean wasApplied(String operationId) {
        return lastOperationId != null && lastOperationId.equals(operationId);
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
