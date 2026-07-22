package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable idempotency receipt for a timed summon or storage boundary. */
public record CommandTimedSummonOperationRecord(
        @Nonnull String operationId,
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nonnull UUID ownerUuid,
        @Nonnull String commandFamilyId,
        @Nonnull String profileId,
        @Nonnull Kind kind,
        @Nonnull OperationState operationState,
        @Nonnull CommandTimedSummonSessionRecord.State expectedState,
        long expectedRowRevision,
        @Nullable Long expectedProfileRevision,
        @Nullable String populationOperationId,
        @Nullable UUID projectionNpcUuid,
        @Nullable Long resultingRowRevision,
        @Nullable String summonSessionId,
        @Nonnull CommandTimedSummonSessionRecord.State resultState,
        @Nullable String reason,
        long createdAtMs,
        long updatedAtMs,
        long completedAtMs
) {
    public CommandTimedSummonOperationRecord {
        operationId = requireText(operationId, "operationId");
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        profileId = requireText(profileId, "profileId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operationState, "operationState");
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(resultState, "resultState");
        summonSessionId = normalizeText(summonSessionId);
        populationOperationId = normalizeText(populationOperationId);
        reason = normalizeText(reason);
        if (expectedRowRevision < 0L) {
            throw new IllegalArgumentException("expectedRowRevision must be non-negative.");
        }
        if (resultingRowRevision != null && resultingRowRevision < 1L) {
            throw new IllegalArgumentException("resultingRowRevision must be positive.");
        }
        if (expectedProfileRevision != null && expectedProfileRevision < 0L) {
            throw new IllegalArgumentException("expectedProfileRevision must be non-negative.");
        }
        if (createdAtMs < 0L || updatedAtMs < createdAtMs || completedAtMs < 0L) {
            throw new IllegalArgumentException("Operation timestamps are invalid.");
        }
        if (operationState == OperationState.COMMITTED) {
            if (resultingRowRevision == null || completedAtMs == 0L) {
                throw new IllegalArgumentException("Committed operations require a resulting revision and timestamp.");
            }
        } else if (resultingRowRevision != null || completedAtMs != 0L) {
            throw new IllegalArgumentException("Non-committed operations cannot carry committed result fields.");
        }
    }

    public enum Kind {
        SUMMON,
        ACTIVATE,
        CHECKPOINT,
        STORE,
        COMPLETE_STORAGE,
        MARK_DEAD,
        MARK_LOST
    }

    public enum OperationState {
        PREPARED,
        APPLYING,
        COMMITTED,
        CANCELED,
        QUARANTINED
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }

    @Nullable
    private static String normalizeText(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
