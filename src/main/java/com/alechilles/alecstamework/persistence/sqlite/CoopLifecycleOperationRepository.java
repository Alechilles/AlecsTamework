package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Commit-aware facade for replay-safe managed-coop capture and release operations.
 */
public final class CoopLifecycleOperationRepository {
    public enum OperationKind {
        CAPTURE,
        RELEASE,
        EJECT,
        IMPORT
    }

    public enum OperationState {
        PREPARED,
        SLOT_COMMITTED,
        SOURCE_RETIRE_REQUESTED,
        SPAWN_CLAIMED,
        PROJECTION_CREATED,
        FINALIZED,
        COMPLETE,
        FAILED,
        QUARANTINED
    }

    public enum MutationStatus {
        APPLIED,
        IDEMPOTENT,
        NOT_FOUND,
        CONFLICT
    }

    public record OperationRecord(@Nonnull String operationId,
                                  @Nonnull OperationKind kind,
                                  @Nonnull String profileId,
                                  @Nonnull ManagedCoopAuthorityKey authorityKey,
                                  @Nonnull String coopId,
                                  int residentSlot,
                                  @Nullable UUID sourceNpcUuid,
                                  @Nullable UUID plannedTargetUuid,
                                  @Nullable UUID actualTargetUuid,
                                  @Nonnull OperationState state,
                                  @Nullable String snapshotHash,
                                  long expectedResidentGeneration,
                                  long generation,
                                  int retryCount,
                                  boolean active,
                                  long createdAtMs,
                                  long updatedAtMs,
                                  long completedAtMs,
                                  @Nullable String lastError) {
    }

    public record MutationResult(@Nonnull MutationStatus status,
                                 @Nullable OperationRecord operation,
                                 @Nullable String detail) {
        public boolean succeeded() {
            return status == MutationStatus.APPLIED || status == MutationStatus.IDEMPOTENT;
        }
    }

    public record CaptureRequest(@Nonnull String operationId,
                                 @Nonnull String residentId,
                                 @Nonnull ManagedCoopAuthorityKey authorityKey,
                                 @Nonnull String coopId,
                                 int residentSlot,
                                 @Nonnull String profileId,
                                 @Nullable String roleId,
                                 @Nonnull UUID sourceNpcUuid,
                                 @Nullable String snapshotJson,
                                 @Nullable String snapshotHash,
                                 int snapshotVersion,
                                 long expectedResidentGeneration,
                                 long nowMs) {
    }

    public record ReleaseRequest(@Nonnull String operationId,
                                 @Nonnull String residentId,
                                 @Nonnull ManagedCoopAuthorityKey authorityKey,
                                 @Nonnull String coopId,
                                 int residentSlot,
                                 @Nonnull String profileId,
                                 @Nonnull UUID plannedTargetUuid,
                                 @Nullable String snapshotHash,
                                 long expectedResidentGeneration,
                                 long nowMs) {
    }

    /** Exact release evidence required by the canonical population commit boundary. */
    public record PopulationReleaseCommitRequest(
            @Nonnull String operationId,
            @Nonnull String residentId,
            @Nonnull ManagedCoopAuthorityKey authorityKey,
            @Nonnull String coopId,
            int residentSlot,
            @Nonnull String profileId,
            @Nonnull UUID plannedTargetUuid,
            @Nonnull UUID actualTargetUuid,
            @Nullable String snapshotHash,
            long expectedResidentGeneration,
            long expectedOperationGeneration,
            long nowMs) {
    }

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final CoopLifecycleOperationTransactions transactions;
    private final CoopLifecycleOperationReader reader = new CoopLifecycleOperationReader();

    public CoopLifecycleOperationRepository(@Nonnull SqliteConnectionManager connectionManager,
                                            @Nonnull PersistenceWriteQueue writeQueue,
                                            @Nonnull ManagedCoopResidentRepository residentRepository) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
        this.transactions = new CoopLifecycleOperationTransactions(residentRepository);
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> prepareCapture(
            @Nonnull CaptureRequest request) {
        return submit("coop_capture_prepare", connection -> transactions.prepareCapture(connection, request));
    }

    /**
     * Atomically claims the capture operation and commits its full resident snapshot/slot.
     *
     * <p>Runtime capture must use this boundary so a crash cannot strand a PREPARED row that lacks
     * the complete replay bundle.</p>
     */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> claimCapture(
            @Nonnull CaptureRequest request) {
        ManagedCoopCaptureClaimValidator.validate(request);
        return submit("coop_capture_claim", connection -> transactions.claimCapture(connection, request));
    }

    MutationResult claimCaptureInTransaction(@Nonnull Connection connection,
                                             @Nonnull CaptureRequest request) throws SQLException {
        ManagedCoopCaptureClaimValidator.validate(request);
        return transactions.claimCapture(connection, request);
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> commitCaptureSlot(
            @Nonnull CaptureRequest request,
            long expectedOperationGeneration) {
        return submit("coop_capture_slot_commit",
                connection -> transactions.commitCaptureSlot(
                        connection, request, expectedOperationGeneration));
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> requestCaptureSourceRetirement(
            @Nonnull String operationId,
            long expectedOperationGeneration,
            long nowMs) {
        return submit("coop_capture_source_retire_request",
                connection -> transactions.advance(
                        connection, operationId, OperationKind.CAPTURE,
                        OperationState.SLOT_COMMITTED, OperationState.SOURCE_RETIRE_REQUESTED,
                        expectedOperationGeneration, false, nowMs));
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> completeCapture(
            @Nonnull String operationId,
            long expectedOperationGeneration,
            long nowMs) {
        return submit("coop_capture_complete",
                connection -> transactions.advance(
                        connection, operationId, OperationKind.CAPTURE,
                        OperationState.SOURCE_RETIRE_REQUESTED, OperationState.COMPLETE,
                        expectedOperationGeneration, true, nowMs));
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> prepareRelease(
            @Nonnull ReleaseRequest request) {
        return submit("coop_release_prepare", connection -> transactions.prepareRelease(connection, request));
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> claimReleaseSpawn(
            @Nonnull String operationId,
            long expectedOperationGeneration,
            long nowMs) {
        return submit("coop_release_spawn_claim",
                connection -> transactions.advance(
                        connection, operationId, OperationKind.RELEASE,
                        OperationState.PREPARED, OperationState.SPAWN_CLAIMED,
                        expectedOperationGeneration, false, nowMs));
    }

    /**
     * Restores a resident and terminates its release after definitive proof no spawn occurred.
     */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> failReleaseBeforeProjection(
            @Nonnull String operationId,
            long expectedOperationGeneration,
            @Nonnull String error,
            long nowMs) {
        return submit("coop_release_fail_before_projection",
                connection -> transactions.failReleaseBeforeProjection(
                        connection, operationId, expectedOperationGeneration, error, nowMs));
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> markProjectionCreated(
            @Nonnull String operationId,
            long expectedOperationGeneration,
            @Nonnull UUID actualTargetUuid,
            long nowMs) {
        return submit("coop_release_projection_created",
                connection -> transactions.markProjectionCreated(
                        connection, operationId, expectedOperationGeneration, actualTargetUuid, nowMs));
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> finalizeRelease(
            @Nonnull String operationId,
            long expectedOperationGeneration,
            long nowMs) {
        return submit("coop_release_finalize",
                connection -> transactions.finalizeRelease(
                        connection, operationId, expectedOperationGeneration, nowMs));
    }

    MutationResult commitPopulationReleaseInTransaction(
            @Nonnull Connection connection,
            @Nonnull PopulationReleaseCommitRequest request) throws SQLException {
        return transactions.commitPopulationRelease(connection, request);
    }

    MutationResult failPopulationReleaseBeforeProjectionInTransaction(
            @Nonnull Connection connection,
            @Nonnull PopulationReleaseCommitRequest request,
            @Nonnull String error,
            long nowMs) throws SQLException {
        return transactions.failPopulationReleaseBeforeProjection(
                connection, request, error, nowMs);
    }

    @Nullable
    public OperationRecord load(@Nonnull String operationId) throws SQLException {
        try (Connection connection = connectionManager.openConnection()) {
            return transactions.load(connection, operationId);
        }
    }

    @Nullable
    public OperationRecord loadActiveForProfile(@Nonnull String profileId) throws SQLException {
        try (Connection connection = connectionManager.openConnection()) {
            return transactions.loadActiveForProfile(connection, profileId);
        }
    }

    /** Loads every active lifecycle operation in deterministic authority/slot order. */
    @Nonnull
    public ManagedCoopReadResult<List<OperationRecord>> loadAllActiveOperations() {
        try (Connection connection = connectionManager.openConnection()) {
            return ManagedCoopReadResult.loaded(reader.loadAllActive(connection));
        } catch (ManagedCoopIntegrityException exception) {
            return ManagedCoopReadResult.integrityFailure(exception);
        } catch (SQLException exception) {
            return ManagedCoopReadResult.sqlFailure(exception);
        }
    }

    /** Loads active lifecycle operations for one exact authority in deterministic slot order. */
    @Nonnull
    public ManagedCoopReadResult<List<OperationRecord>> loadActiveOperations(
            @Nonnull ManagedCoopAuthorityKey key,
            @Nonnull String coopId) {
        if (key == null || coopId == null || coopId.isBlank()) {
            return ManagedCoopReadResult.invalidInput("coop_id_required");
        }
        try (Connection connection = connectionManager.openConnection()) {
            List<OperationRecord> active = reader.loadActiveForAuthority(connection, key, coopId);
            return active == null
                    ? ManagedCoopReadResult.notFound()
                    : ManagedCoopReadResult.loaded(active);
        } catch (ManagedCoopIntegrityException exception) {
            return ManagedCoopReadResult.integrityFailure(exception);
        } catch (SQLException exception) {
            return ManagedCoopReadResult.sqlFailure(exception);
        }
    }

    @Nonnull
    private PersistenceWriteQueue.WriteSubmission<MutationResult> submit(
            @Nonnull String operationName,
            @Nonnull PersistenceWriteQueue.SqlWork<MutationResult> work) {
        return writeQueue.submitTracked(operationName, work, null);
    }
}
