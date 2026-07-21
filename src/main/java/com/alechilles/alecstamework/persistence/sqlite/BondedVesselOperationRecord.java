package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable recovery journal for one generation-fenced bonded-vessel transition. */
public record BondedVesselOperationRecord(
        @Nonnull String operationId,
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nullable String correlationId,
        @Nonnull String bindingId,
        @Nonnull String profileId,
        @Nonnull Action action,
        @Nonnull State state,
        long priorGeneration,
        long candidateGeneration,
        long expectedProfileRevision,
        @Nonnull String configId,
        long configRevision,
        @Nonnull BondedVesselBindingRecord.LifecycleState priorLifecycleState,
        @Nonnull BondedVesselBindingRecord.LifecycleState applyingLifecycleState,
        @Nonnull BondedVesselBindingRecord.LifecycleState targetLifecycleState,
        @Nonnull BondedVesselBindingRecord.ItemProjectionStatus priorProjectionStatus,
        @Nonnull BondedVesselBindingRecord.ItemProjectionStatus targetProjectionStatus,
        long priorCooldownUntilMs,
        long targetCooldownUntilMs,
        @Nullable String sourceItemId,
        @Nullable String targetItemId,
        @Nullable String sourceFingerprint,
        @Nullable String replacementFingerprint,
        @Nullable String sourceContextJson,
        @Nonnull String policySnapshotJson,
        @Nullable String populationOperationId,
        @Nullable UUID actualNpcUuid,
        @Nullable String reasonCode,
        @Nonnull String recoveryStatus,
        long leaseExpiresAtMs,
        long createdAtMs,
        long updatedAtMs,
        long appliedAtMs,
        long completedAtMs
) {
    public enum Action {
        INITIAL_BIND,
        SUMMON,
        STORE,
        MARK_DEAD,
        MARK_LOST,
        REPAIR,
        RELEASE,
        REISSUE
    }

    public enum State {
        PREPARED,
        APPLYING,
        APPLIED,
        COMMITTED,
        CANCELED,
        COMPENSATING,
        QUARANTINED,
        TERMINAL_DENIED;

        public boolean isTerminal() {
            return this == COMMITTED || this == CANCELED || this == TERMINAL_DENIED;
        }
    }

    public BondedVesselOperationRecord {
        operationId = requireText(operationId, "operationId");
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        correlationId = normalize(correlationId);
        bindingId = requireText(bindingId, "bindingId");
        profileId = requireText(profileId, "profileId");
        action = Objects.requireNonNull(action, "action");
        state = Objects.requireNonNull(state, "state");
        configId = requireText(configId, "configId");
        priorLifecycleState = Objects.requireNonNull(priorLifecycleState, "priorLifecycleState");
        applyingLifecycleState = Objects.requireNonNull(applyingLifecycleState, "applyingLifecycleState");
        targetLifecycleState = Objects.requireNonNull(targetLifecycleState, "targetLifecycleState");
        priorProjectionStatus = Objects.requireNonNull(priorProjectionStatus, "priorProjectionStatus");
        targetProjectionStatus = Objects.requireNonNull(targetProjectionStatus, "targetProjectionStatus");
        sourceItemId = normalize(sourceItemId);
        targetItemId = normalize(targetItemId);
        sourceFingerprint = normalize(sourceFingerprint);
        replacementFingerprint = normalize(replacementFingerprint);
        sourceContextJson = normalize(sourceContextJson);
        policySnapshotJson = requireText(policySnapshotJson, "policySnapshotJson");
        populationOperationId = normalize(populationOperationId);
        reasonCode = normalize(reasonCode);
        recoveryStatus = requireText(recoveryStatus, "recoveryStatus");
        if (priorGeneration < 0L || candidateGeneration != priorGeneration + 1L) {
            throw new IllegalArgumentException("candidateGeneration must be priorGeneration + 1.");
        }
        if (action != Action.INITIAL_BIND && priorGeneration == 0L) {
            throw new IllegalArgumentException("Only INITIAL_BIND may transition generation zero.");
        }
        if (expectedProfileRevision < 0L || configRevision < 0L) {
            throw new IllegalArgumentException("Revisions must be non-negative.");
        }
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
    private static String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
