package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Commit-aware facade for managed-coop authority and resident occupancy persistence.
 */
public final class ManagedCoopResidentRepository {
    public enum AuthorityState {
        VANILLA_DISCOVERED,
        IMPORTING_TO_TWORK,
        TWORK_MANAGED,
        CONFLICT,
        DISABLED
    }

    public enum ResidentState {
        HOUSED,
        RELEASING,
        DEPLOYED,
        IMPORTING,
        QUARANTINED,
        RETIRED
    }

    public enum MutationStatus {
        APPLIED,
        IDEMPOTENT,
        NOT_FOUND,
        CONFLICT
    }

    public record ResidentRecord(@Nonnull String residentId,
                                 @Nonnull ManagedCoopAuthorityKey authorityKey,
                                 @Nonnull String coopId,
                                 int residentSlot,
                                 @Nonnull String profileId,
                                 @Nullable String roleId,
                                 @Nonnull UUID residentUuid,
                                 @Nullable UUID sourceNpcUuid,
                                 @Nullable UUID deployedNpcUuid,
                                 @Nullable String snapshotJson,
                                 @Nullable String snapshotHash,
                                 int snapshotVersion,
                                 @Nonnull ResidentState state,
                                 long generation,
                                 boolean active,
                                 long capturedAtMs,
                                 long releasedAtMs,
                                 long createdAtMs,
                                  long updatedAtMs) {
    }

    /** Immutable exact-location authority projection used by runtime indexes. */
    public record AuthorityRecord(@Nonnull String authorityId,
                                  @Nonnull ManagedCoopAuthorityKey authorityKey,
                                  @Nonnull String coopId,
                                  @Nonnull AuthorityState state,
                                  boolean active,
                                  int importVersion,
                                  long createdAtMs,
                                  long updatedAtMs,
                                  @Nullable String lastError) {
    }

    public record MutationResult(@Nonnull MutationStatus status,
                                 @Nullable ResidentRecord resident,
                                 @Nullable String detail) {
        public boolean succeeded() {
            return status == MutationStatus.APPLIED || status == MutationStatus.IDEMPOTENT;
        }
    }

    public record HousedResidentClaim(@Nonnull String residentId,
                                      @Nonnull ManagedCoopAuthorityKey authorityKey,
                                      @Nonnull String coopId,
                                      int residentSlot,
                                      @Nonnull String profileId,
                                      @Nullable String roleId,
                                      @Nonnull UUID sourceNpcUuid,
                                      @Nullable String snapshotJson,
                                      @Nullable String snapshotHash,
                                      int snapshotVersion,
                                      long capturedAtMs) {
    }

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final ManagedCoopResidentTransactions transactions = new ManagedCoopResidentTransactions();
    private final ManagedCoopResidentReader reader = new ManagedCoopResidentReader();

    public ManagedCoopResidentRepository(@Nonnull SqliteConnectionManager connectionManager,
                                         @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> registerAuthority(
            @Nonnull ManagedCoopAuthorityKey key,
            @Nonnull String coopId,
            @Nonnull AuthorityState state,
            long nowMs) {
        return writeQueue.submitTracked(
                "managed_coop_authority_register",
                connection -> registerAuthorityInTransaction(connection, key, coopId, state, nowMs),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> transitionAuthority(
            @Nonnull ManagedCoopAuthorityKey key,
            @Nonnull AuthorityState expected,
            @Nonnull AuthorityState target,
            @Nullable String lastError,
            long nowMs) {
        return writeQueue.submitTracked(
                "managed_coop_authority_transition",
                connection -> transactions.transitionAuthority(
                        connection, key, expected, target, lastError, nowMs),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> claimHoused(
            @Nonnull HousedResidentClaim claim) {
        return writeQueue.submitTracked(
                "managed_coop_resident_claim_housed",
                connection -> claimHousedInTransaction(connection, claim),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> beginRelease(
            @Nonnull String residentId,
            long expectedGeneration,
            @Nonnull UUID plannedTargetUuid,
            long nowMs) {
        return writeQueue.submitTracked(
                "managed_coop_resident_begin_release",
                connection -> beginReleaseInTransaction(
                        connection, residentId, expectedGeneration, plannedTargetUuid, nowMs),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> finishRelease(
            @Nonnull String residentId,
            long expectedGeneration,
            @Nonnull UUID actualTargetUuid,
            long nowMs) {
        return writeQueue.submitTracked(
                "managed_coop_resident_finish_release",
                connection -> finishReleaseInTransaction(
                        connection, residentId, expectedGeneration, actualTargetUuid, nowMs),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> finishCapture(
            @Nonnull String residentId,
            long expectedGeneration,
            @Nonnull UUID sourceNpcUuid,
            @Nullable String snapshotJson,
            @Nullable String snapshotHash,
            int snapshotVersion,
            long nowMs) {
        return writeQueue.submitTracked(
                "managed_coop_resident_finish_capture",
                connection -> finishCaptureInTransaction(
                        connection, residentId, expectedGeneration, sourceNpcUuid,
                        snapshotJson, snapshotHash, snapshotVersion, nowMs),
                null
        );
    }

    @Nullable
    public ResidentRecord loadById(@Nonnull String residentId) throws SQLException {
        try (Connection connection = connectionManager.openConnection()) {
            return loadByIdInTransaction(connection, residentId);
        }
    }

    @Nullable
    public ResidentRecord loadActiveByProfile(@Nonnull String profileId) throws SQLException {
        try (Connection connection = connectionManager.openConnection()) {
            return transactions.loadActiveByProfile(connection, profileId);
        }
    }

    @Nullable
    public ResidentRecord loadActiveSlot(@Nonnull ManagedCoopAuthorityKey key,
                                         int residentSlot) throws SQLException {
        try (Connection connection = connectionManager.openConnection()) {
            return loadActiveSlotInTransaction(connection, key, residentSlot);
        }
    }

    public int findFirstAvailableSlot(@Nonnull ManagedCoopAuthorityKey key, int maximumResidents)
            throws SQLException {
        try (Connection connection = connectionManager.openConnection()) {
            return transactions.findFirstAvailableSlot(connection, key, maximumResidents);
        }
    }

    /** Loads one active authority by exact world, coop ID, and block coordinates. */
    @Nonnull
    public ManagedCoopReadResult<AuthorityRecord> loadAuthority(@Nonnull ManagedCoopAuthorityKey key,
                                                                @Nonnull String coopId) {
        if (key == null || coopId == null || coopId.isBlank()) {
            return ManagedCoopReadResult.invalidInput("coop_id_required");
        }
        try (Connection connection = connectionManager.openConnection()) {
            AuthorityRecord authority = reader.loadAuthority(connection, key, coopId);
            return authority == null
                    ? ManagedCoopReadResult.notFound()
                    : ManagedCoopReadResult.loaded(authority);
        } catch (ManagedCoopIntegrityException exception) {
            return ManagedCoopReadResult.integrityFailure(exception);
        } catch (SQLException exception) {
            return ManagedCoopReadResult.sqlFailure(exception);
        }
    }

    /** Loads every active authority in deterministic location order. */
    @Nonnull
    public ManagedCoopReadResult<List<AuthorityRecord>> loadAllActiveAuthorities() {
        try (Connection connection = connectionManager.openConnection()) {
            return ManagedCoopReadResult.loaded(reader.loadAllActiveAuthorities(connection));
        } catch (ManagedCoopIntegrityException exception) {
            return ManagedCoopReadResult.integrityFailure(exception);
        } catch (SQLException exception) {
            return ManagedCoopReadResult.sqlFailure(exception);
        }
    }

    /** Loads every active managed resident in deterministic authority/slot order. */
    @Nonnull
    public ManagedCoopReadResult<List<ResidentRecord>> loadAllActiveResidents() {
        try (Connection connection = connectionManager.openConnection()) {
            return ManagedCoopReadResult.loaded(reader.loadAllActiveResidents(connection));
        } catch (ManagedCoopIntegrityException exception) {
            return ManagedCoopReadResult.integrityFailure(exception);
        } catch (SQLException exception) {
            return ManagedCoopReadResult.sqlFailure(exception);
        }
    }

    /** Loads active residents for one exact authority in deterministic slot order. */
    @Nonnull
    public ManagedCoopReadResult<List<ResidentRecord>> loadActiveResidents(
            @Nonnull ManagedCoopAuthorityKey key,
            @Nonnull String coopId) {
        if (key == null || coopId == null || coopId.isBlank()) {
            return ManagedCoopReadResult.invalidInput("coop_id_required");
        }
        try (Connection connection = connectionManager.openConnection()) {
            AuthorityRecord authority = reader.loadAuthority(connection, key, coopId);
            if (authority == null) {
                return ManagedCoopReadResult.notFound();
            }
            return ManagedCoopReadResult.loaded(reader.loadActiveResidents(connection, key, coopId));
        } catch (ManagedCoopIntegrityException exception) {
            return ManagedCoopReadResult.integrityFailure(exception);
        } catch (SQLException exception) {
            return ManagedCoopReadResult.sqlFailure(exception);
        }
    }

    MutationResult registerAuthorityInTransaction(Connection connection,
                                                  ManagedCoopAuthorityKey key,
                                                  String coopId,
                                                  AuthorityState state,
                                                  long nowMs) throws SQLException {
        return transactions.registerAuthority(connection, key, coopId, state, nowMs);
    }

    MutationResult claimHousedInTransaction(Connection connection, HousedResidentClaim claim)
            throws SQLException {
        return transactions.claimHoused(connection, claim);
    }

    MutationResult beginReleaseInTransaction(Connection connection,
                                             String residentId,
                                             long expectedGeneration,
                                             UUID plannedTargetUuid,
                                             long nowMs) throws SQLException {
        return transactions.beginRelease(
                connection, residentId, expectedGeneration, plannedTargetUuid, nowMs);
    }

    MutationResult cancelReleaseBeforeProjectionInTransaction(
            Connection connection,
            String residentId,
            long expectedGeneration,
            UUID plannedTargetUuid,
            long nowMs) throws SQLException {
        return transactions.cancelReleaseBeforeProjection(
                connection, residentId, expectedGeneration, plannedTargetUuid, nowMs
        );
    }

    MutationResult reserveProjectionUuidInTransaction(Connection connection,
                                                      String residentId,
                                                      UUID targetUuid,
                                                      long nowMs) throws SQLException {
        return transactions.reserveProjectionUuid(connection, residentId, targetUuid, nowMs);
    }

    MutationResult finishReleaseInTransaction(Connection connection,
                                              String residentId,
                                              long expectedGeneration,
                                              UUID actualTargetUuid,
                                              long nowMs) throws SQLException {
        return transactions.finishRelease(
                connection, residentId, expectedGeneration, actualTargetUuid, nowMs);
    }

    MutationResult finishCaptureInTransaction(Connection connection,
                                              String residentId,
                                              long expectedGeneration,
                                              UUID sourceNpcUuid,
                                              String snapshotJson,
                                              String snapshotHash,
                                              int snapshotVersion,
                                              long nowMs) throws SQLException {
        return transactions.finishCapture(
                connection, residentId, expectedGeneration, sourceNpcUuid,
                snapshotJson, snapshotHash, snapshotVersion, nowMs);
    }

    MutationResult detachDeployedInTransaction(
            Connection connection,
            CoopLifecycleOperationRepository.PopulationDetachRequest request) throws SQLException {
        return transactions.detachDeployed(connection, request);
    }

    MutationResult retireStaleHousedInTransaction(
            Connection connection,
            ResidentRecord expected,
            long nowMs) throws SQLException {
        return transactions.retireStaleHoused(connection, expected, nowMs);
    }

    @Nullable
    ResidentRecord loadByIdInTransaction(Connection connection, String residentId) throws SQLException {
        return transactions.loadById(connection, residentId);
    }

    @Nullable
    ResidentRecord loadActiveSlotInTransaction(Connection connection,
                                               ManagedCoopAuthorityKey key,
                                               int residentSlot) throws SQLException {
        return transactions.loadActiveSlot(connection, key, residentSlot);
    }

    @Nullable
    ResidentRecord loadActiveByProfileInTransaction(Connection connection, String profileId)
            throws SQLException {
        return transactions.loadActiveByProfile(connection, profileId);
    }
}
