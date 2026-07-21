package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable idempotency and recovery record for generic companion provisioning. */
public record CompanionProvisioningOperationRecord(
        @Nonnull String operationId,
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nullable String correlationId,
        @Nonnull UUID ownerUuid,
        @Nonnull String targetRoleId,
        @Nonnull RequestedDisposition requestedDisposition,
        @Nullable String ownershipWorldName,
        @Nullable String destinationContextJson,
        @Nullable String initialProfileJson,
        @Nullable Long expectedPolicyRevision,
        @Nonnull String provisionalProfileId,
        @Nullable String canonicalProfileId,
        @Nonnull State state,
        @Nullable String dormantPopulationOperationId,
        @Nullable String activePopulationOperationId,
        @Nullable String resultCode,
        @Nullable String projectionReason,
        @Nonnull String recoveryStatus,
        long createdAtMs,
        long updatedAtMs,
        long completedAtMs
) {
    public enum RequestedDisposition {
        PROVISIONED_DORMANT,
        ACTIVE
    }

    public enum State {
        PREPARING_DORMANT,
        DORMANT_PREPARED,
        DORMANT_APPLYING,
        DORMANT_COMMITTED,
        ACTIVE_PREPARED,
        ACTIVE_APPLYING,
        COMMITTED,
        PARTIAL_DORMANT,
        DENIED,
        CANCELED,
        QUARANTINED;

        public boolean isTerminal() {
            return this == COMMITTED || this == PARTIAL_DORMANT
                    || this == DENIED || this == CANCELED;
        }

        public boolean canTransitionTo(@Nonnull State next) {
            return switch (this) {
                case PREPARING_DORMANT -> next == DORMANT_PREPARED || next == DENIED
                        || next == CANCELED || next == QUARANTINED;
                case DORMANT_PREPARED -> next == DORMANT_APPLYING || next == DENIED
                        || next == CANCELED || next == QUARANTINED;
                case DORMANT_APPLYING -> next == DORMANT_COMMITTED || next == QUARANTINED;
                case DORMANT_COMMITTED -> next == ACTIVE_PREPARED || next == COMMITTED
                        || next == PARTIAL_DORMANT || next == QUARANTINED;
                case ACTIVE_PREPARED -> next == ACTIVE_APPLYING || next == PARTIAL_DORMANT
                        || next == QUARANTINED;
                case ACTIVE_APPLYING -> next == COMMITTED || next == PARTIAL_DORMANT
                        || next == QUARANTINED;
                case QUARANTINED -> next == DORMANT_APPLYING || next == ACTIVE_APPLYING
                        || next == PARTIAL_DORMANT || next == DENIED;
                // PARTIAL_DORMANT is a durable terminal result for the failed attempt, but an
                // explicit retry may reopen only the optional projection for the same profile.
                case PARTIAL_DORMANT -> next == ACTIVE_PREPARED;
                case COMMITTED, DENIED, CANCELED -> false;
            };
        }
    }

    public CompanionProvisioningOperationRecord {
        operationId = requireText(operationId, "operationId");
        callerNamespace = requireText(callerNamespace, "callerNamespace");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        correlationId = normalize(correlationId);
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        targetRoleId = requireText(targetRoleId, "targetRoleId");
        requestedDisposition = Objects.requireNonNull(requestedDisposition, "requestedDisposition");
        ownershipWorldName = normalize(ownershipWorldName);
        destinationContextJson = normalize(destinationContextJson);
        initialProfileJson = normalize(initialProfileJson);
        provisionalProfileId = requireText(provisionalProfileId, "provisionalProfileId");
        canonicalProfileId = normalize(canonicalProfileId);
        state = Objects.requireNonNull(state, "state");
        dormantPopulationOperationId = normalize(dormantPopulationOperationId);
        activePopulationOperationId = normalize(activePopulationOperationId);
        resultCode = normalize(resultCode);
        projectionReason = normalize(projectionReason);
        recoveryStatus = requireText(recoveryStatus, "recoveryStatus");
        if (expectedPolicyRevision != null && expectedPolicyRevision < 0L) {
            throw new IllegalArgumentException("expectedPolicyRevision must be non-negative.");
        }
        if (state.ordinal() >= State.DORMANT_COMMITTED.ordinal() && canonicalProfileId == null
                && state != State.DENIED && state != State.CANCELED
                && state != State.QUARANTINED) {
            throw new IllegalArgumentException("Committed dormant stages require canonicalProfileId.");
        }
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
