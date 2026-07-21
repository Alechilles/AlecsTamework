package com.alechilles.alecstamework.persistence.sqlite;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Frozen old/new group evidence replayed without consulting post-crash live config. */
public record PopulationGroupOperationRecord(
        @Nonnull String operationId,
        @Nullable String populationOperationId,
        @Nonnull String profileId,
        @Nonnull String operationType,
        @Nonnull State state,
        long expectedPopulationRevision,
        long classificationRevision,
        @Nullable UUID oldOwnerUuid,
        @Nullable UUID newOwnerUuid,
        @Nullable String oldRoleId,
        @Nullable String newRoleId,
        @Nonnull List<String> oldGroupIds,
        @Nonnull List<String> newGroupIds,
        @Nullable String oldLifecycleState,
        @Nullable String newLifecycleState,
        @Nullable String oldOwnershipWorldName,
        @Nullable String newOwnershipWorldName,
        @Nullable String reasonCode,
        @Nonnull String recoveryStatus,
        long createdAtMs,
        long updatedAtMs,
        long completedAtMs
) {
    public enum State {
        PREPARED,
        APPLYING,
        APPLIED,
        COMMITTED,
        CANCELED,
        COMPENSATING,
        QUARANTINED,
        FAILED;

        public boolean isTerminal() {
            return this == COMMITTED || this == CANCELED || this == FAILED;
        }

        public boolean canTransitionTo(@Nonnull State next) {
            return switch (this) {
                case PREPARED -> next == APPLYING || next == CANCELED || next == QUARANTINED;
                case APPLYING -> next == APPLIED || next == COMPENSATING || next == QUARANTINED;
                case APPLIED -> next == COMMITTED || next == COMPENSATING || next == QUARANTINED;
                case COMPENSATING -> next == FAILED || next == QUARANTINED;
                case QUARANTINED -> next == APPLYING || next == COMPENSATING || next == FAILED;
                case COMMITTED, CANCELED, FAILED -> false;
            };
        }
    }

    public PopulationGroupOperationRecord {
        operationId = requireText(operationId, "operationId");
        populationOperationId = normalize(populationOperationId);
        profileId = requireText(profileId, "profileId");
        operationType = requireText(operationType, "operationType");
        state = Objects.requireNonNull(state, "state");
        oldRoleId = normalize(oldRoleId);
        newRoleId = normalize(newRoleId);
        oldGroupIds = sorted(oldGroupIds, "oldGroupIds");
        newGroupIds = sorted(newGroupIds, "newGroupIds");
        oldLifecycleState = normalize(oldLifecycleState);
        newLifecycleState = normalize(newLifecycleState);
        oldOwnershipWorldName = normalize(oldOwnershipWorldName);
        newOwnershipWorldName = normalize(newOwnershipWorldName);
        reasonCode = normalize(reasonCode);
        recoveryStatus = requireText(recoveryStatus, "recoveryStatus");
        if (expectedPopulationRevision < 0L || classificationRevision < 0L) {
            throw new IllegalArgumentException("Revisions must be non-negative.");
        }
    }

    private static List<String> sorted(List<String> values, String field) {
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : Objects.requireNonNull(values, field)) {
            normalized.add(requireText(value, field));
        }
        return List.copyOf(normalized);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank.");
        return normalized;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
