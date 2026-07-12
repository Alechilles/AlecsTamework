package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Durable journal row for a transition that crosses SQLite and live game state.
 */
public record CompanionPopulationOperationRecord(
        @Nonnull String operationId,
        @Nonnull String profileId,
        @Nonnull String operationType,
        @Nonnull State state,
        long expectedRevision,
        @Nonnull String oldStateJson,
        @Nonnull String newStateJson,
        @Nullable String targetContextJson,
        long createdAtMs,
        long updatedAtMs,
        long completedAtMs,
        @Nullable String lastError
) {
    public enum State {
        PREPARED,
        APPLYING,
        APPLIED,
        COMMITTED,
        COMPENSATING,
        FAILED;

        public boolean isTerminal() {
            return this == COMMITTED || this == FAILED;
        }

        public boolean canTransitionTo(@Nonnull State next) {
            return switch (this) {
                case PREPARED -> next == APPLYING || next == FAILED;
                case APPLYING -> next == APPLIED || next == COMPENSATING || next == FAILED;
                case APPLIED -> next == COMMITTED || next == COMPENSATING || next == FAILED;
                case COMPENSATING -> next == FAILED;
                case COMMITTED, FAILED -> false;
            };
        }
    }

    public CompanionPopulationOperationRecord {
        operationId = requireText(operationId, "operationId");
        profileId = requireText(profileId, "profileId");
        operationType = requireText(operationType, "operationType");
        Objects.requireNonNull(state, "state");
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must be non-negative.");
        }
        Objects.requireNonNull(oldStateJson, "oldStateJson");
        Objects.requireNonNull(newStateJson, "newStateJson");
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }
}
