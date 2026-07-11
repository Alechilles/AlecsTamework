package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Persists replay-safe NPC recovery claims and their optimistic lifecycle transitions.
 */
public final class NpcRecoveryOperationRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final NpcRecoveryOperationTransactions transactions;

    public NpcRecoveryOperationRepository(@Nonnull SqliteConnectionManager connectionManager,
                                          @Nonnull PersistenceWriteQueue writeQueue) {
        this(connectionManager, writeQueue, System::currentTimeMillis);
    }

    NpcRecoveryOperationRepository(@Nonnull SqliteConnectionManager connectionManager,
                                   @Nonnull PersistenceWriteQueue writeQueue,
                                   @Nonnull LongSupplier clock) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
        this.transactions = new NpcRecoveryOperationTransactions(
                Objects.requireNonNull(clock, "clock")
        );
    }

    /**
     * Claims a planned target before spawning. Replaying the identical operation never creates a new row.
     */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<ClaimResult> claim(@Nonnull RecoveryClaim claim) {
        Objects.requireNonNull(claim, "claim");
        return writeQueue.submitTracked(
                "npc_recovery_claim",
                connection -> transactions.claim(connection, claim),
                null
        );
    }

    /** Records the visible projection only from the spawn-claimed state and expected generation. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<TransitionResult> recordProjectionCreated(
            @Nonnull String operationId,
            @Nonnull String profileId,
            @Nonnull UUID actualTargetUuid,
            long expectedGeneration) {
        String normalizedOperationId = requireText(operationId, "operationId");
        String normalizedProfileId = requireText(profileId, "profileId");
        Objects.requireNonNull(actualTargetUuid, "actualTargetUuid");
        requireGeneration(expectedGeneration);
        return writeQueue.submitTracked(
                "npc_recovery_projection_created",
                connection -> transactions.recordProjection(
                        connection,
                        normalizedOperationId,
                        normalizedProfileId,
                        actualTargetUuid,
                        expectedGeneration
                ),
                null
        );
    }

    /**
     * Atomically finalizes a projection and every durable identity side effect.
     * The completion resolves only after the write queue commits or rejects the whole transaction.
     */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<TransitionResult> finalizeRecovery(
            @Nonnull RecoveryFinalization finalization) {
        Objects.requireNonNull(finalization, "finalization");
        return writeQueue.submitTracked(
                "npc_recovery_finalize",
                connection -> transactions.finalizeRecovery(connection, finalization),
                null
        );
    }

    /** Terminates an unresolved operation without permitting an automatic replacement spawn. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<TransitionResult> failOrQuarantine(
            @Nonnull String operationId,
            @Nonnull String profileId,
            long expectedGeneration,
            @Nonnull FailureDisposition disposition,
            @Nonnull String error) {
        String normalizedOperationId = requireText(operationId, "operationId");
        String normalizedProfileId = requireText(profileId, "profileId");
        String normalizedError = requireText(error, "error");
        Objects.requireNonNull(disposition, "disposition");
        requireGeneration(expectedGeneration);
        return writeQueue.submitTracked(
                "npc_recovery_terminate",
                connection -> transactions.terminate(
                        connection,
                        normalizedOperationId,
                        normalizedProfileId,
                        expectedGeneration,
                        disposition.targetState(),
                        normalizedError
                ),
                null
        );
    }

    /** Loads one operation while preserving SQL and integrity failures as typed results. */
    @Nonnull
    public LoadResult loadByOperationId(@Nullable String operationId) {
        String normalizedOperationId = normalizeText(operationId);
        if (normalizedOperationId == null) {
            return LoadResult.failed(ReadFailure.invalidInput("operation_id_required"));
        }
        try (Connection connection = connectionManager.openConnection()) {
            RecoveryOperation operation = transactions.findByOperationId(connection, normalizedOperationId);
            return operation == null ? LoadResult.notFound() : LoadResult.found(operation);
        } catch (SQLException exception) {
            return LoadResult.failed(ReadFailure.sql(exception));
        } catch (NpcRecoveryOperationTransactions.RepositoryIntegrityException exception) {
            return LoadResult.failed(ReadFailure.integrity(exception));
        }
    }

    /** Loads the sole active recovery for a profile and fails closed on invariant violations. */
    @Nonnull
    public LoadResult loadActiveByProfile(@Nullable String profileId) {
        String normalizedProfileId = normalizeText(profileId);
        if (normalizedProfileId == null) {
            return LoadResult.failed(ReadFailure.invalidInput("profile_id_required"));
        }
        try (Connection connection = connectionManager.openConnection()) {
            RecoveryOperation operation = transactions.findActiveByProfile(connection, normalizedProfileId, true);
            return operation == null ? LoadResult.notFound() : LoadResult.found(operation);
        } catch (SQLException exception) {
            return LoadResult.failed(ReadFailure.sql(exception));
        } catch (NpcRecoveryOperationTransactions.RepositoryIntegrityException exception) {
            return LoadResult.failed(ReadFailure.integrity(exception));
        }
    }

    /** Loads every active operation for restart reconciliation without hiding read failures. */
    @Nonnull
    public ActiveOperationsResult loadAllActive() {
        try (Connection connection = connectionManager.openConnection()) {
            return ActiveOperationsResult.loaded(transactions.findAllActive(connection));
        } catch (SQLException exception) {
            return ActiveOperationsResult.failed(ReadFailure.sql(exception));
        } catch (NpcRecoveryOperationTransactions.RepositoryIntegrityException exception) {
            return ActiveOperationsResult.failed(ReadFailure.integrity(exception));
        }
    }

    private static void requireGeneration(long generation) {
        if (generation < 0L) {
            throw new IllegalArgumentException("expectedGeneration must be non-negative");
        }
    }

    @Nonnull
    private static String requireText(@Nullable String value, @Nonnull String fieldName) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    @Nullable
    private static String normalizeText(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public enum RecoveryState {
        PREPARED,
        SPAWN_CLAIMED,
        PROJECTION_CREATED,
        FINALIZED,
        LEGACY_UNVERIFIED,
        CONFLICT,
        FAILED,
        QUARANTINED
    }

    public enum FailureDisposition {
        FAILED(RecoveryState.FAILED),
        QUARANTINED(RecoveryState.QUARANTINED);

        private final RecoveryState state;

        FailureDisposition(@Nonnull RecoveryState state) {
            this.state = state;
        }

        @Nonnull
        RecoveryState targetState() {
            return state;
        }
    }

    public enum ClaimStatus {
        CLAIMED,
        REPLAYED,
        PROFILE_NOT_FOUND,
        OPERATION_CONFLICT,
        SOURCE_CONFLICT,
        PROFILE_STATE_CONFLICT,
        LOST_NOT_AWAITING,
        LOST_SNAPSHOT_CONFLICT,
        LOST_ENVELOPE_UNVERIFIED,
        LOST_ENVELOPE_INVALID,
        PROFILE_CONFLICT,
        TARGET_CONFLICT
    }

    public enum TransitionStatus {
        APPLIED,
        REPLAYED,
        NOT_FOUND,
        PROFILE_CONFLICT,
        SOURCE_CONFLICT,
        TARGET_CONFLICT,
        GENERATION_CONFLICT,
        STATE_CONFLICT
    }

    public enum LoadStatus {
        FOUND,
        NOT_FOUND,
        FAILED
    }

    public enum ReadFailureKind {
        INVALID_INPUT,
        SQL_ERROR,
        INTEGRITY_VIOLATION
    }

    public enum ActiveOperationsStatus {
        LOADED,
        FAILED
    }

    public record RecoveryClaim(@Nonnull String operationId,
                                @Nonnull String profileId,
                                @Nullable UUID sourceNpcUuid,
                                @Nonnull UUID plannedTargetUuid) {
        public RecoveryClaim {
            operationId = requireText(operationId, "operationId");
            profileId = requireText(profileId, "profileId");
            Objects.requireNonNull(plannedTargetUuid, "plannedTargetUuid");
        }
    }

    /** Exact durable state expected when completing one recovery operation. */
    public record RecoveryFinalization(@Nonnull String operationId,
                                       @Nonnull String profileId,
                                       @Nullable UUID sourceNpcUuid,
                                       @Nonnull UUID plannedTargetUuid,
                                       @Nonnull UUID actualTargetUuid,
                                       long expectedGeneration,
                                       @Nonnull List<String> toolIds) {
        public RecoveryFinalization {
            operationId = requireText(operationId, "operationId");
            profileId = requireText(profileId, "profileId");
            Objects.requireNonNull(plannedTargetUuid, "plannedTargetUuid");
            Objects.requireNonNull(actualTargetUuid, "actualTargetUuid");
            requireGeneration(expectedGeneration);
            TreeSet<String> canonicalToolIds = new TreeSet<>();
            if (toolIds != null) {
                for (String toolId : toolIds) {
                    String normalized = normalizeText(toolId);
                    if (normalized != null) {
                        canonicalToolIds.add(normalized);
                    }
                }
            }
            toolIds = List.copyOf(canonicalToolIds);
        }
    }

    public record RecoveryOperation(@Nonnull String operationId,
                                    @Nonnull String profileId,
                                    @Nullable UUID sourceNpcUuid,
                                    @Nullable UUID plannedTargetUuid,
                                    @Nullable UUID actualTargetUuid,
                                    @Nonnull RecoveryState state,
                                    boolean active,
                                    long generation,
                                    int attemptCount,
                                    long createdAtMs,
                                    long updatedAtMs,
                                    long completedAtMs,
                                    @Nullable String lastError) {
        boolean matchesClaim(@Nonnull RecoveryClaim claim) {
            return profileId.equals(claim.profileId())
                    && Objects.equals(sourceNpcUuid, claim.sourceNpcUuid())
                    && Objects.equals(plannedTargetUuid, claim.plannedTargetUuid());
        }
    }

    public record ClaimResult(@Nonnull ClaimStatus status,
                              @Nullable RecoveryOperation operation) {
        @Nonnull
        static ClaimResult claimed(@Nonnull RecoveryOperation operation) {
            return new ClaimResult(ClaimStatus.CLAIMED, operation);
        }

        @Nonnull
        static ClaimResult replayed(@Nonnull RecoveryOperation operation) {
            return new ClaimResult(ClaimStatus.REPLAYED, operation);
        }

        @Nonnull
        static ClaimResult conflict(@Nonnull ClaimStatus status,
                                    @Nullable RecoveryOperation operation) {
            return new ClaimResult(status, operation);
        }
    }

    public record TransitionResult(@Nonnull TransitionStatus status,
                                   @Nullable RecoveryOperation operation) {
        @Nonnull
        static TransitionResult applied(@Nonnull RecoveryOperation operation) {
            return new TransitionResult(TransitionStatus.APPLIED, operation);
        }

        @Nonnull
        static TransitionResult replayed(@Nonnull RecoveryOperation operation) {
            return new TransitionResult(TransitionStatus.REPLAYED, operation);
        }

        @Nonnull
        static TransitionResult notFound() {
            return new TransitionResult(TransitionStatus.NOT_FOUND, null);
        }

        @Nonnull
        static TransitionResult conflict(@Nonnull TransitionStatus status,
                                         @Nonnull RecoveryOperation operation) {
            return new TransitionResult(status, operation);
        }
    }

    public record LoadResult(@Nonnull LoadStatus status,
                             @Nullable RecoveryOperation operation,
                             @Nullable ReadFailure failure) {
        @Nonnull
        static LoadResult found(@Nonnull RecoveryOperation operation) {
            return new LoadResult(LoadStatus.FOUND, operation, null);
        }

        @Nonnull
        static LoadResult notFound() {
            return new LoadResult(LoadStatus.NOT_FOUND, null, null);
        }

        @Nonnull
        static LoadResult failed(@Nonnull ReadFailure failure) {
            return new LoadResult(LoadStatus.FAILED, null, failure);
        }
    }

    public record ActiveOperationsResult(@Nonnull ActiveOperationsStatus status,
                                         @Nonnull List<RecoveryOperation> operations,
                                         @Nullable ReadFailure failure) {
        public ActiveOperationsResult {
            operations = List.copyOf(operations == null ? List.of() : new ArrayList<>(operations));
        }

        @Nonnull
        static ActiveOperationsResult loaded(@Nonnull List<RecoveryOperation> operations) {
            return new ActiveOperationsResult(ActiveOperationsStatus.LOADED, operations, null);
        }

        @Nonnull
        static ActiveOperationsResult failed(@Nonnull ReadFailure failure) {
            return new ActiveOperationsResult(ActiveOperationsStatus.FAILED, List.of(), failure);
        }
    }

    public record ReadFailure(@Nonnull ReadFailureKind kind,
                              @Nonnull String message,
                              @Nullable Throwable cause) {
        @Nonnull
        static ReadFailure invalidInput(@Nonnull String message) {
            return new ReadFailure(ReadFailureKind.INVALID_INPUT, message, null);
        }

        @Nonnull
        static ReadFailure sql(@Nonnull SQLException exception) {
            return new ReadFailure(ReadFailureKind.SQL_ERROR, exception.getMessage(), exception);
        }

        @Nonnull
        static ReadFailure integrity(@Nonnull RuntimeException exception) {
            return new ReadFailure(ReadFailureKind.INTEGRITY_VIOLATION, exception.getMessage(), exception);
        }
    }
}
