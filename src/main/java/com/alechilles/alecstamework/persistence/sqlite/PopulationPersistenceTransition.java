package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Persistence inputs for preparing and atomically finalizing one population mutation.
 */
public final class PopulationPersistenceTransition {
    private PopulationPersistenceTransition() {
    }

    public record Prepare(
            @Nonnull CompanionPopulationOperationRecord operation,
            @Nonnull CompanionPopulationStateRecord baselineState
    ) {
        public Prepare {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(baselineState, "baselineState");
            if (operation.state() != CompanionPopulationOperationRecord.State.PREPARED) {
                throw new IllegalArgumentException("A prepared transition must use PREPARED journal state.");
            }
            if (!operation.profileId().equals(baselineState.profileId())) {
                throw new IllegalArgumentException("Operation and baseline profile IDs must match.");
            }
            if (operation.expectedRevision() != baselineState.revision()) {
                throw new IllegalArgumentException("Operation and baseline revisions must match.");
            }
        }
    }

    public record Commit(
            @Nonnull String operationId,
            @Nonnull String profileId,
            long expectedRevision,
            @Nonnull ProfileOwnerMutation ownerMutation,
            @Nullable UUID currentNpcUuid,
            @Nullable String ownershipWorldName,
            @Nonnull String lifecycleState,
            @Nullable String physicalWorldName,
            @Nullable Integer physicalChunkX,
            @Nullable Integer physicalChunkZ,
            @Nullable String source
    ) {
        public Commit {
            operationId = requireText(operationId, "operationId");
            profileId = requireText(profileId, "profileId");
            if (expectedRevision < 0L) {
                throw new IllegalArgumentException("expectedRevision must be non-negative.");
            }
            Objects.requireNonNull(ownerMutation, "ownerMutation");
            lifecycleState = requireText(lifecycleState, "lifecycleState");
            boolean noPhysicalLocation = physicalWorldName == null
                    && physicalChunkX == null
                    && physicalChunkZ == null;
            boolean completePhysicalLocation = physicalWorldName != null
                    && !physicalWorldName.isBlank()
                    && physicalChunkX != null
                    && physicalChunkZ != null;
            if (!noPhysicalLocation && !completePhysicalLocation) {
                throw new IllegalArgumentException("Physical location must be entirely present or absent.");
            }
        }
    }

    public enum ResultStatus {
        PREPARED,
        COMMITTED,
        IDEMPOTENT,
        REVISION_CONFLICT,
        IDENTITY_CONFLICT,
        OPERATION_CONFLICT,
        INVALID_STATE,
        NOT_FOUND
    }

    public record Result(@Nonnull ResultStatus status,
                         long revision,
                         @Nullable String reason) {
        public boolean isSuccess() {
            return status == ResultStatus.PREPARED
                    || status == ResultStatus.COMMITTED
                    || status == ResultStatus.IDEMPOTENT;
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
}
