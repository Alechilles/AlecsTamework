package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Revision-fenced persistence authority for command-roster summon leases.
 *
 * <p>Summon and storage are two-phase boundaries: prepare records idempotency, claim publishes
 * {@code RESTORING}/{@code STORING}, and commit publishes the final state. Capacity therefore
 * remains occupied throughout storage and recovery can distinguish an absent projection from an
 * interrupted destructive mutation.
 */
public final class CommandTimedSummonRepository {
    private static final String SESSION_COLUMNS = """
            SELECT owner_uuid, command_family_id, profile_id, row_revision, summon_state,
                   summon_session_id, summon_remaining_ms, resummon_cooldown_until_ms,
                   summon_config_id, summon_config_revision, summon_policy_json,
                   warning_receipts_json, summon_last_checkpoint_at_ms,
                   active_operation_id, created_at_ms, updated_at_ms
            FROM command_timed_summon_sessions
            """;
    private static final String OPERATION_COLUMNS = """
            SELECT operation_id, caller_namespace, idempotency_key, owner_uuid,
                   command_family_id, profile_id, operation_kind, operation_state,
                   expected_state, expected_row_revision, expected_profile_revision,
                   population_operation_id, projection_npc_uuid, resulting_row_revision,
                   summon_session_id, result_state, reason, created_at_ms, updated_at_ms,
                   completed_at_ms
            FROM command_timed_summon_operations
            """;

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final ConcurrentHashMap<SessionKey, CommandTimedSummonSessionRecord> sessionCache =
            new ConcurrentHashMap<>();

    public CommandTimedSummonRepository(@Nonnull SqliteConnectionManager connectionManager,
                                        @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> createSessionAsync(
            @Nonnull CommandTimedSummonSessionRecord requested) {
        return writeQueue.submitTracked(
                "command_timed_summon_create_session",
                connection -> createSession(connection, requested),
                null
        );
    }

    /** Resets the active lease after a paid revival has committed the canonical live projection. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> activateAfterRevivalAsync(
            @Nonnull CommandTimedSummonSessionRecord active) {
        Objects.requireNonNull(active, "active");
        return writeQueue.submitTracked("command_timed_summon_activate_after_revival", connection -> {
            CommandTimedSummonSessionRecord current = findSession(
                    connection, active.ownerUuid(), active.commandFamilyId(), active.profileId());
            if (current == null) return createSession(connection, active);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE command_timed_summon_sessions SET
                        row_revision = row_revision + 1,
                        summon_state = 'ACTIVE', summon_session_id = ?, summon_remaining_ms = ?,
                        resummon_cooldown_until_ms = 0, summon_config_id = ?,
                        summon_config_revision = ?, summon_policy_json = ?,
                        warning_receipts_json = '[]', summon_last_checkpoint_at_ms = ?,
                        active_operation_id = NULL, updated_at_ms = ?
                    WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                    """)) {
                int index = 1;
                statement.setString(index++, active.summonSessionId());
                if (active.summonRemainingMs() == null) statement.setNull(index++, Types.BIGINT);
                else statement.setLong(index++, active.summonRemainingMs());
                statement.setString(index++, active.summonConfigId());
                if (active.summonConfigRevision() == null) statement.setNull(index++, Types.BIGINT);
                else statement.setLong(index++, active.summonConfigRevision());
                statement.setString(index++, CommandTimedSummonPolicySnapshot.toJson(active.summonPolicy()));
                statement.setLong(index++, active.summonLastCheckpointAtMs());
                statement.setLong(index++, active.updatedAtMs());
                statement.setString(index++, active.ownerUuid().toString());
                statement.setString(index++, active.commandFamilyId());
                statement.setString(index, active.profileId());
                statement.executeUpdate();
            }
            return result(Status.COMMITTED, findSession(
                    connection, active.ownerUuid(), active.commandFamilyId(), active.profileId()),
                    null, "revival-active-lease-registered");
        }, null);
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> prepareAsync(
            @Nonnull CommandTimedSummonOperationRecord requested) {
        if (requested.operationState() != CommandTimedSummonOperationRecord.OperationState.PREPARED
                || requested.resultingRowRevision() != null) {
            throw new IllegalArgumentException("New timed summon operations must begin PREPARED.");
        }
        return writeQueue.submitTracked(
                "command_timed_summon_prepare",
                connection -> prepare(connection, requested),
                null
        );
    }

    /** Publishes RESTORING/STORING before the caller mutates the world projection. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> claimAsync(@Nonnull ClaimMutation mutation) {
        return writeQueue.submitTracked(
                "command_timed_summon_claim",
                connection -> claim(connection, mutation),
                null
        );
    }

    /** Publishes ACTIVE/ROSTER_STORED only after the projection mutation is proven. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> commitAsync(@Nonnull CommitMutation mutation) {
        return writeQueue.submitTracked(
                "command_timed_summon_commit",
                connection -> commit(connection, mutation),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> checkpointAsync(
            @Nonnull CheckpointMutation mutation) {
        return writeQueue.submitTracked(
                "command_timed_summon_checkpoint",
                connection -> checkpoint(connection, mutation),
                null
        );
    }

    /** Preserves the same lease while a live projection crosses the loaded/unloaded boundary. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> setProjectionAvailabilityAsync(
            @Nonnull ProjectionAvailabilityMutation mutation) {
        return writeQueue.submitTracked(
                "command_timed_summon_projection_availability",
                connection -> setProjectionAvailability(connection, mutation),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> cancelPreparedAsync(
            @Nonnull String operationId,
            @Nonnull String reason,
            long nowMs) {
        String requiredId = requireText(operationId, "operationId");
        String requiredReason = requireText(reason, "reason");
        return writeQueue.submitTracked(
                "command_timed_summon_cancel",
                connection -> cancelPrepared(connection, requiredId, requiredReason, nowMs),
                null
        );
    }

    /**
     * Reverts an entered apply boundary only with state-specific proof that the world mutation
     * did not take effect. Ambiguous outcomes must remain recoverable instead.
     */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> rollbackApplyingAsync(
            @Nonnull String operationId,
            @Nonnull ApplyAbsenceProof proof,
            @Nonnull String reason,
            long nowMs) {
        String requiredId = requireText(operationId, "operationId");
        String requiredReason = requireText(reason, "reason");
        Objects.requireNonNull(proof, "proof");
        return writeQueue.submitTracked(
                "command_timed_summon_rollback",
                connection -> rollbackApplying(connection, requiredId, proof, requiredReason, nowMs),
                null
        );
    }

    @Nullable
    public CommandTimedSummonSessionRecord findSession(@Nonnull UUID ownerUuid,
                                                       @Nonnull String commandFamilyId,
                                                       @Nonnull String profileId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findSession(connection, ownerUuid, commandFamilyId, profileId);
        }
    }

    /** Pure in-memory API view; callers schedule an asynchronous refresh on a miss. */
    @Nullable
    public CommandTimedSummonSessionRecord cachedSession(@Nonnull UUID ownerUuid,
                                                         @Nonnull String commandFamilyId,
                                                         @Nonnull String profileId) {
        return sessionCache.get(new SessionKey(ownerUuid,
                requireText(commandFamilyId, "commandFamilyId"),
                requireText(profileId, "profileId")));
    }

    @Nullable
    public CommandTimedSummonOperationRecord findOperation(@Nonnull String operationId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findOperation(connection, requireText(operationId, "operationId"));
        }
    }

    @Nonnull
    public List<CommandTimedSummonSessionRecord> loadProjectedSessions() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SESSION_COLUMNS + " WHERE summon_state IN ('RESTORING','ACTIVE','UNLOADED','STORING') "
                             + "ORDER BY owner_uuid, command_family_id, profile_id");
             ResultSet result = statement.executeQuery()) {
            List<CommandTimedSummonSessionRecord> sessions = new ArrayList<>();
            while (result.next()) {
                sessions.add(cache(readSession(result)));
            }
            return List.copyOf(sessions);
        }
    }

    @Nonnull
    public List<CommandTimedSummonSessionRecord> loadSessionsForOwner(@Nonnull UUID ownerUuid) throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SESSION_COLUMNS + " WHERE owner_uuid = ? "
                             + "ORDER BY command_family_id, profile_id")) {
            statement.setString(1, Objects.requireNonNull(ownerUuid, "ownerUuid").toString());
            try (ResultSet result = statement.executeQuery()) {
                List<CommandTimedSummonSessionRecord> sessions = new ArrayList<>();
                while (result.next()) sessions.add(cache(readSession(result)));
                return List.copyOf(sessions);
            }
        }
    }

    /** Warms the synchronous public-API view before the capability is advertised. */
    @Nonnull
    public List<CommandTimedSummonSessionRecord> loadAllSessions() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SESSION_COLUMNS + " ORDER BY owner_uuid, command_family_id, profile_id");
             ResultSet result = statement.executeQuery()) {
            List<CommandTimedSummonSessionRecord> sessions = new ArrayList<>();
            while (result.next()) sessions.add(cache(readSession(result)));
            return List.copyOf(sessions);
        }
    }

    /** Background-only profile lookup used by projection load/unload lifecycle fencing. */
    @Nullable
    public CommandTimedSummonSessionRecord findProjectedSession(@Nonnull String profileId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findProjectedSessionForProfile(connection, requireText(profileId, "profileId"));
        }
    }

    @Nonnull
    public List<CommandTimedSummonOperationRecord> loadRecoverableOperations() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     OPERATION_COLUMNS + " WHERE operation_state IN ('PREPARED','APPLYING','QUARANTINED') "
                             + "ORDER BY created_at_ms, operation_id");
             ResultSet result = statement.executeQuery()) {
            List<CommandTimedSummonOperationRecord> operations = new ArrayList<>();
            while (result.next()) {
                operations.add(readOperation(result));
            }
            return List.copyOf(operations);
        }
    }

    /** Stores the last complete restorable projection before an intentional roster despawn. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<ProjectionSnapshot> saveProjectionSnapshotAsync(
            @Nonnull ProjectionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return writeQueue.submitTracked(
                "command_timed_summon_save_projection_snapshot",
                connection -> saveProjectionSnapshot(connection, snapshot),
                null);
    }

    /** Captures a loaded projection at chunk-unload without a synchronous world-thread lookup. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<ProjectionSnapshot> saveProjectedSnapshotAsync(
            @Nonnull String profileId, @Nonnull UUID sourceNpcUuid,
            @Nonnull String snapshotJson, @Nonnull String snapshotSha256, long nowMs) {
        String requiredProfile = requireText(profileId, "profileId");
        return writeQueue.submitTracked(
                "command_timed_summon_save_unload_snapshot",
                connection -> {
                    CommandTimedSummonSessionRecord session = findProjectedSessionForProfile(
                            connection, requiredProfile);
                    if (session == null) {
                        throw new IllegalStateException("Projected timed session is unavailable.");
                    }
                    return saveProjectionSnapshot(connection, new ProjectionSnapshot(
                            session.ownerUuid(), session.commandFamilyId(), session.profileId(),
                            sourceNpcUuid, snapshotJson, snapshotSha256, nowMs));
                }, null);
    }

    @Nonnull
    public List<ProjectionSnapshot> loadProjectionSnapshots() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT owner_uuid, command_family_id, profile_id, source_npc_uuid,
                            snapshot_json, snapshot_sha256, updated_at_ms
                     FROM command_timed_summon_snapshots
                     ORDER BY owner_uuid, command_family_id, profile_id
                     """);
             ResultSet result = statement.executeQuery()) {
            List<ProjectionSnapshot> snapshots = new ArrayList<>();
            while (result.next()) {
                snapshots.add(readProjectionSnapshot(result));
            }
            return List.copyOf(snapshots);
        }
    }

    @Nullable
    public ProjectionSnapshot findProjectionSnapshot(@Nonnull UUID ownerUuid,
                                                      @Nonnull String commandFamilyId,
                                                      @Nonnull String profileId) throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT owner_uuid, command_family_id, profile_id, source_npc_uuid,
                            snapshot_json, snapshot_sha256, updated_at_ms
                     FROM command_timed_summon_snapshots
                     WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                     """)) {
            statement.setString(1, Objects.requireNonNull(ownerUuid, "ownerUuid").toString());
            statement.setString(2, requireText(commandFamilyId, "commandFamilyId"));
            statement.setString(3, requireText(profileId, "profileId"));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return readProjectionSnapshot(result);
            }
        }
    }

    @Nullable
    private CommandTimedSummonSessionRecord findProjectedSessionForProfile(
            Connection connection, String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                SESSION_COLUMNS + " WHERE profile_id = ? AND summon_state IN "
                        + "('ACTIVE','UNLOADED','STORING','ROSTER_STORED') "
                        + "ORDER BY updated_at_ms DESC LIMIT 1")) {
            statement.setString(1, profileId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? cache(readSession(result)) : null;
            }
        }
    }

    private static ProjectionSnapshot readProjectionSnapshot(ResultSet result) throws Exception {
        return new ProjectionSnapshot(
                UUID.fromString(result.getString("owner_uuid")),
                result.getString("command_family_id"), result.getString("profile_id"),
                UUID.fromString(result.getString("source_npc_uuid")),
                result.getString("snapshot_json"), result.getString("snapshot_sha256"),
                result.getLong("updated_at_ms"));
    }

    private ProjectionSnapshot saveProjectionSnapshot(Connection connection,
                                                      ProjectionSnapshot snapshot) throws Exception {
        CommandTimedSummonSessionRecord session = findSession(
                connection, snapshot.ownerUuid(), snapshot.commandFamilyId(), snapshot.profileId());
        if (session == null || (session.state() != CommandTimedSummonSessionRecord.State.ACTIVE
                && session.state() != CommandTimedSummonSessionRecord.State.UNLOADED
                && session.state() != CommandTimedSummonSessionRecord.State.STORING
                && session.state() != CommandTimedSummonSessionRecord.State.ROSTER_STORED)) {
            throw new IllegalStateException("Timed projection snapshot requires projected/storage authority.");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO command_timed_summon_snapshots (
                    owner_uuid, command_family_id, profile_id, source_npc_uuid,
                    snapshot_json, snapshot_sha256, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(owner_uuid, command_family_id, profile_id) DO UPDATE SET
                    source_npc_uuid = excluded.source_npc_uuid,
                    snapshot_json = excluded.snapshot_json,
                    snapshot_sha256 = excluded.snapshot_sha256,
                    updated_at_ms = excluded.updated_at_ms
                """)) {
            statement.setString(1, snapshot.ownerUuid().toString());
            statement.setString(2, snapshot.commandFamilyId());
            statement.setString(3, snapshot.profileId());
            statement.setString(4, snapshot.sourceNpcUuid().toString());
            statement.setString(5, snapshot.snapshotJson());
            statement.setString(6, snapshot.snapshotSha256());
            statement.setLong(7, snapshot.updatedAtMs());
            statement.executeUpdate();
        }
        return snapshot;
    }

    private MutationResult createSession(Connection connection,
                                         CommandTimedSummonSessionRecord requested) throws Exception {
        CommandTimedSummonSessionRecord existing = findSession(
                connection, requested.ownerUuid(), requested.commandFamilyId(), requested.profileId());
        if (existing != null) {
            return sameInitialSession(existing, requested)
                    ? result(Status.IDEMPOTENT, existing, null, "session_exists")
                    : result(Status.CONFLICT, existing, null, "session_identity_in_use");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO command_timed_summon_sessions (
                    owner_uuid, command_family_id, profile_id, row_revision, summon_state,
                    summon_session_id, summon_remaining_ms, resummon_cooldown_until_ms,
                    summon_config_id, summon_config_revision, summon_policy_json,
                    warning_receipts_json, summon_last_checkpoint_at_ms,
                    active_operation_id, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindSession(statement, requested);
            statement.executeUpdate();
        }
        return result(Status.CREATED, cache(requested), null, null);
    }

    private MutationResult prepare(Connection connection,
                                   CommandTimedSummonOperationRecord requested) throws Exception {
        CommandTimedSummonOperationRecord existing = findExistingOperation(connection, requested);
        if (existing != null) {
            CommandTimedSummonSessionRecord session = findSession(connection, existing);
            return sameOperation(existing, requested)
                    ? result(Status.IDEMPOTENT, session, existing, "operation_exists")
                    : result(Status.CONFLICT, session, existing, "idempotency_key_in_use");
        }
        CommandTimedSummonSessionRecord session = findSession(connection, requested);
        String denial = validateExpectedSession(session, requested);
        if (denial != null) {
            return result(Status.DENIED, session, null, denial);
        }
        insertOperation(connection, requested);
        return result(Status.PREPARED, session, requested, null);
    }

    private MutationResult claim(Connection connection, ClaimMutation mutation) throws Exception {
        CommandTimedSummonOperationRecord operation = findOperation(connection, mutation.operationId());
        if (operation == null) {
            return result(Status.NOT_FOUND, null, null, "operation_not_found");
        }
        CommandTimedSummonSessionRecord session = findSession(connection, operation);
        if (operation.operationState() == CommandTimedSummonOperationRecord.OperationState.APPLYING) {
            return session != null && mutationCompatibleWithClaim(session, operation, mutation)
                    ? result(Status.IDEMPOTENT, session, operation, "operation_already_claimed")
                    : result(Status.CONFLICT, session, operation, "claimed_transition_changed");
        }
        if (operation.operationState() != CommandTimedSummonOperationRecord.OperationState.PREPARED) {
            return result(Status.INVALID_STATE, session, operation, "operation_not_prepared");
        }
        String denial = validateExpectedSession(session, operation);
        if (denial != null) {
            return result(Status.CONFLICT, session, operation, denial);
        }
        CommandTimedSummonSessionRecord.State transitional = transitionalState(operation.kind());
        if (transitional == null) {
            return result(Status.INVALID_STATE, session, operation, "operation_has_no_world_apply_boundary");
        }
        validateClaimMutation(session, operation, mutation);
        CommandTimedSummonPolicySnapshot claimedPolicy = operation.kind()
                == CommandTimedSummonOperationRecord.Kind.SUMMON
                ? mutation.summonPolicy() : session.summonPolicy();
        Set<Long> claimedWarnings = operation.kind() == CommandTimedSummonOperationRecord.Kind.SUMMON
                ? Set.of() : session.emittedWarningThresholdsMs();
        long nextRevision = incrementRevision(session.rowRevision());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_timed_summon_sessions
                SET row_revision = ?, summon_state = ?, summon_session_id = ?,
                    summon_remaining_ms = ?, summon_config_id = ?, summon_config_revision = ?,
                    summon_policy_json = ?, warning_receipts_json = ?,
                    summon_last_checkpoint_at_ms = ?, active_operation_id = ?, updated_at_ms = ?
                WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                  AND row_revision = ? AND summon_state = ? AND active_operation_id IS NULL
                """)) {
            int index = 1;
            statement.setLong(index++, nextRevision);
            statement.setString(index++, transitional.name());
            statement.setString(index++, mutation.summonSessionId());
            setLong(statement, index++, mutation.summonRemainingMs());
            setText(statement, index++, mutation.summonConfigId());
            setLong(statement, index++, mutation.summonConfigRevision());
            statement.setString(index++, CommandTimedSummonPolicySnapshot.toJson(claimedPolicy));
            statement.setString(index++, warningReceiptsToJson(claimedWarnings));
            statement.setLong(index++, mutation.nowMs());
            statement.setString(index++, operation.operationId());
            statement.setLong(index++, mutation.nowMs());
            statement.setString(index++, operation.ownerUuid().toString());
            statement.setString(index++, operation.commandFamilyId());
            statement.setString(index++, operation.profileId());
            statement.setLong(index++, operation.expectedRowRevision());
            statement.setString(index, operation.expectedState().name());
            if (statement.executeUpdate() != 1) {
                return result(Status.CONFLICT, findSession(connection, operation), operation,
                        "session_changed_during_claim");
            }
        }
        updateOperationState(connection, operation.operationId(),
                CommandTimedSummonOperationRecord.OperationState.PREPARED,
                CommandTimedSummonOperationRecord.OperationState.APPLYING,
                null, null, mutation.nowMs(), 0L);
        return result(Status.APPLYING, findSession(connection, operation),
                findOperation(connection, operation.operationId()), null);
    }

    private MutationResult commit(Connection connection, CommitMutation mutation) throws Exception {
        CommandTimedSummonOperationRecord operation = findOperation(connection, mutation.operationId());
        if (operation == null) {
            return result(Status.NOT_FOUND, null, null, "operation_not_found");
        }
        CommandTimedSummonSessionRecord session = findSession(connection, operation);
        if (operation.operationState() == CommandTimedSummonOperationRecord.OperationState.COMMITTED) {
            return operation.resultState() == mutation.finalState()
                    ? result(Status.IDEMPOTENT, session, operation, "operation_already_committed")
                    : result(Status.CONFLICT, session, operation, "committed_result_changed");
        }
        if (operation.operationState() != CommandTimedSummonOperationRecord.OperationState.APPLYING) {
            return result(Status.INVALID_STATE, session, operation, "operation_not_applying");
        }
        if (mutation.finalState() != operation.resultState()) {
            return result(Status.CONFLICT, session, operation, "operation_result_changed");
        }
        CommandTimedSummonSessionRecord.State transitional = transitionalState(operation.kind());
        if (session == null || session.state() != transitional
                || !operation.operationId().equals(session.activeOperationId())) {
            return result(Status.CONFLICT, session, operation, "session_apply_reservation_changed");
        }
        validateCommitMutation(session, operation, mutation);
        long nextRevision = incrementRevision(session.rowRevision());
        boolean dormant = !mutation.finalState().hasProjectionSession();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_timed_summon_sessions
                SET row_revision = ?, summon_state = ?, summon_session_id = ?,
                    summon_remaining_ms = ?, resummon_cooldown_until_ms = ?,
                    summon_config_id = ?, summon_config_revision = ?, summon_policy_json = ?,
                    warning_receipts_json = ?,
                    summon_last_checkpoint_at_ms = ?, active_operation_id = NULL, updated_at_ms = ?
                WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                  AND row_revision = ? AND summon_state = ? AND active_operation_id = ?
                """)) {
            int index = 1;
            statement.setLong(index++, nextRevision);
            statement.setString(index++, mutation.finalState().name());
            setText(statement, index++, dormant ? null : session.summonSessionId());
            setLong(statement, index++, dormant ? null : session.summonRemainingMs());
            statement.setLong(index++, mutation.resummonCooldownUntilMs());
            setText(statement, index++, session.summonConfigId());
            setLong(statement, index++, session.summonConfigRevision());
            statement.setString(index++, CommandTimedSummonPolicySnapshot.toJson(session.summonPolicy()));
            statement.setString(index++, warningReceiptsToJson(session.emittedWarningThresholdsMs()));
            setLong(statement, index++, dormant ? null : mutation.nowMs());
            statement.setLong(index++, mutation.nowMs());
            statement.setString(index++, operation.ownerUuid().toString());
            statement.setString(index++, operation.commandFamilyId());
            statement.setString(index++, operation.profileId());
            statement.setLong(index++, session.rowRevision());
            statement.setString(index++, transitional.name());
            statement.setString(index, operation.operationId());
            if (statement.executeUpdate() != 1) {
                return result(Status.CONFLICT, findSession(connection, operation), operation,
                        "session_changed_during_commit");
            }
        }
        updateOperationState(connection, operation.operationId(),
                CommandTimedSummonOperationRecord.OperationState.APPLYING,
                CommandTimedSummonOperationRecord.OperationState.COMMITTED,
                nextRevision, mutation.reason(), mutation.nowMs(), mutation.nowMs());
        return result(Status.COMMITTED, findSession(connection, operation),
                findOperation(connection, operation.operationId()), null);
    }

    private MutationResult checkpoint(Connection connection, CheckpointMutation mutation) throws Exception {
        CommandTimedSummonSessionRecord session = findSession(
                connection, mutation.ownerUuid(), mutation.commandFamilyId(), mutation.profileId());
        if (session == null) {
            return result(Status.NOT_FOUND, null, null, "session_not_found");
        }
        if ((session.state() != CommandTimedSummonSessionRecord.State.ACTIVE
                && session.state() != CommandTimedSummonSessionRecord.State.UNLOADED)
                || session.rowRevision() != mutation.expectedRowRevision()
                || !Objects.equals(session.summonSessionId(), mutation.summonSessionId())) {
            return result(Status.CONFLICT, session, null, "active_session_changed");
        }
        if (session.unlimitedLease()) {
            if (mutation.remainingMs() != null) {
                return result(Status.DENIED, session, null, "unlimited_lease_cannot_become_finite");
            }
        } else if (mutation.remainingMs() == null
                || mutation.remainingMs() > Objects.requireNonNull(session.summonRemainingMs())) {
            return result(Status.DENIED, session, null, "checkpoint_cannot_replenish_lease");
        }
        if (mutation.nowMs() < Objects.requireNonNull(session.summonLastCheckpointAtMs())) {
            return result(Status.DENIED, session, null, "checkpoint_time_moved_backwards");
        }
        Set<Long> warningReceipts = validateWarningReceipts(session, mutation.emittedWarningThresholdsMs());
        long nextRevision = incrementRevision(session.rowRevision());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_timed_summon_sessions
                SET row_revision = ?, summon_remaining_ms = ?, warning_receipts_json = ?,
                    summon_last_checkpoint_at_ms = ?, updated_at_ms = ?
                WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                  AND row_revision = ? AND summon_state = ? AND summon_session_id = ?
                """)) {
            statement.setLong(1, nextRevision);
            setLong(statement, 2, mutation.remainingMs());
            statement.setString(3, warningReceiptsToJson(warningReceipts));
            statement.setLong(4, mutation.nowMs());
            statement.setLong(5, mutation.nowMs());
            statement.setString(6, mutation.ownerUuid().toString());
            statement.setString(7, mutation.commandFamilyId());
            statement.setString(8, mutation.profileId());
            statement.setLong(9, mutation.expectedRowRevision());
            statement.setString(10, session.state().name());
            statement.setString(11, mutation.summonSessionId());
            if (statement.executeUpdate() != 1) {
                return result(Status.CONFLICT, findSession(connection, mutation.ownerUuid(),
                        mutation.commandFamilyId(), mutation.profileId()), null,
                        "session_changed_during_checkpoint");
            }
        }
        return result(Status.CHECKPOINTED, findSession(connection, mutation.ownerUuid(),
                mutation.commandFamilyId(), mutation.profileId()), null, null);
    }

    private MutationResult setProjectionAvailability(Connection connection,
                                                      ProjectionAvailabilityMutation mutation) throws Exception {
        CommandTimedSummonSessionRecord session = findSession(
                connection, mutation.ownerUuid(), mutation.commandFamilyId(), mutation.profileId());
        if (session == null) return result(Status.NOT_FOUND, null, null, "session_not_found");
        if (session.rowRevision() != mutation.expectedRowRevision()
                || !Objects.equals(session.summonSessionId(), mutation.summonSessionId())) {
            return result(Status.CONFLICT, session, null, "projection_session_changed");
        }
        if (session.state() == mutation.targetState()) {
            return result(Status.IDEMPOTENT, session, null, "projection_availability_unchanged");
        }
        if ((session.state() != CommandTimedSummonSessionRecord.State.ACTIVE
                && session.state() != CommandTimedSummonSessionRecord.State.UNLOADED)
                || (mutation.targetState() != CommandTimedSummonSessionRecord.State.ACTIVE
                && mutation.targetState() != CommandTimedSummonSessionRecord.State.UNLOADED)) {
            return result(Status.INVALID_STATE, session, null, "projection_availability_transition_invalid");
        }
        Long remaining = session.remainingAt(mutation.nowMs());
        long nextRevision = incrementRevision(session.rowRevision());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_timed_summon_sessions
                SET row_revision = ?, summon_state = ?, summon_remaining_ms = ?,
                    summon_last_checkpoint_at_ms = ?, updated_at_ms = ?
                WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                  AND row_revision = ? AND summon_state = ? AND summon_session_id = ?
                """)) {
            statement.setLong(1, nextRevision);
            statement.setString(2, mutation.targetState().name());
            setLong(statement, 3, remaining);
            statement.setLong(4, mutation.nowMs());
            statement.setLong(5, mutation.nowMs());
            statement.setString(6, mutation.ownerUuid().toString());
            statement.setString(7, mutation.commandFamilyId());
            statement.setString(8, mutation.profileId());
            statement.setLong(9, mutation.expectedRowRevision());
            statement.setString(10, session.state().name());
            statement.setString(11, mutation.summonSessionId());
            if (statement.executeUpdate() != 1) {
                return result(Status.CONFLICT, findSession(connection, mutation.ownerUuid(),
                        mutation.commandFamilyId(), mutation.profileId()), null,
                        "projection_availability_changed_during_commit");
            }
        }
        return result(Status.CHECKPOINTED, findSession(connection, mutation.ownerUuid(),
                mutation.commandFamilyId(), mutation.profileId()), null, null);
    }

    private MutationResult cancelPrepared(Connection connection,
                                          String operationId,
                                          String reason,
                                          long nowMs) throws Exception {
        CommandTimedSummonOperationRecord operation = findOperation(connection, operationId);
        if (operation == null) {
            return result(Status.NOT_FOUND, null, null, "operation_not_found");
        }
        CommandTimedSummonSessionRecord session = findSession(connection, operation);
        if (operation.operationState() == CommandTimedSummonOperationRecord.OperationState.CANCELED) {
            return result(Status.IDEMPOTENT, session, operation, "operation_already_canceled");
        }
        if (operation.operationState() != CommandTimedSummonOperationRecord.OperationState.PREPARED) {
            return result(Status.INVALID_STATE, session, operation, "operation_apply_boundary_entered");
        }
        updateOperationState(connection, operationId,
                CommandTimedSummonOperationRecord.OperationState.PREPARED,
                CommandTimedSummonOperationRecord.OperationState.CANCELED,
                null, reason, nowMs, 0L);
        return result(Status.CANCELED, session, findOperation(connection, operationId), null);
    }

    private MutationResult rollbackApplying(Connection connection,
                                            String operationId,
                                            ApplyAbsenceProof proof,
                                            String reason,
                                            long nowMs) throws Exception {
        CommandTimedSummonOperationRecord operation = findOperation(connection, operationId);
        if (operation == null) return result(Status.NOT_FOUND, null, null, "operation_not_found");
        CommandTimedSummonSessionRecord session = findSession(connection, operation);
        if (operation.operationState() == CommandTimedSummonOperationRecord.OperationState.CANCELED) {
            return result(Status.IDEMPOTENT, session, operation, "operation_already_canceled");
        }
        if (operation.operationState() != CommandTimedSummonOperationRecord.OperationState.APPLYING
                || session == null || !operationId.equals(session.activeOperationId())) {
            return result(Status.INVALID_STATE, session, operation, "operation_not_rollback_eligible");
        }
        CommandTimedSummonSessionRecord.State rollbackState;
        if (operation.kind() == CommandTimedSummonOperationRecord.Kind.SUMMON
                && proof == ApplyAbsenceProof.PROJECTION_NEVER_CREATED) {
            rollbackState = CommandTimedSummonSessionRecord.State.ROSTER_STORED;
        } else if (operation.kind() == CommandTimedSummonOperationRecord.Kind.STORE
                && proof == ApplyAbsenceProof.PROJECTION_RETAINED) {
            rollbackState = operation.expectedState();
        } else {
            return result(Status.DENIED, session, operation, "rollback_proof_does_not_match_operation");
        }
        long nextRevision = incrementRevision(session.rowRevision());
        boolean dormant = rollbackState == CommandTimedSummonSessionRecord.State.ROSTER_STORED;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_timed_summon_sessions
                SET row_revision = ?, summon_state = ?, summon_session_id = ?,
                    summon_remaining_ms = ?, summon_last_checkpoint_at_ms = ?,
                    active_operation_id = NULL, updated_at_ms = ?
                WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                  AND row_revision = ? AND active_operation_id = ?
                """)) {
            statement.setLong(1, nextRevision);
            statement.setString(2, rollbackState.name());
            setText(statement, 3, dormant ? null : session.summonSessionId());
            setLong(statement, 4, dormant ? null : session.summonRemainingMs());
            setLong(statement, 5, dormant ? null : nowMs);
            statement.setLong(6, nowMs);
            statement.setString(7, operation.ownerUuid().toString());
            statement.setString(8, operation.commandFamilyId());
            statement.setString(9, operation.profileId());
            statement.setLong(10, session.rowRevision());
            statement.setString(11, operationId);
            if (statement.executeUpdate() != 1) {
                return result(Status.CONFLICT, findSession(connection, operation), operation,
                        "session_changed_during_rollback");
            }
        }
        updateOperationState(connection, operationId,
                CommandTimedSummonOperationRecord.OperationState.APPLYING,
                CommandTimedSummonOperationRecord.OperationState.CANCELED,
                null, reason, nowMs, 0L);
        return result(Status.ROLLED_BACK, findSession(connection, operation),
                findOperation(connection, operationId), null);
    }

    @Nullable
    private CommandTimedSummonSessionRecord findSession(Connection connection,
                                                        UUID ownerUuid,
                                                        String commandFamilyId,
                                                        String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                SESSION_COLUMNS + " WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ? LIMIT 1")) {
            statement.setString(1, Objects.requireNonNull(ownerUuid, "ownerUuid").toString());
            statement.setString(2, requireText(commandFamilyId, "commandFamilyId"));
            statement.setString(3, requireText(profileId, "profileId"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? cache(readSession(result)) : null;
            }
        }
    }

    @Nullable
    private CommandTimedSummonSessionRecord findSession(Connection connection,
                                                        CommandTimedSummonOperationRecord operation) throws Exception {
        return findSession(connection, operation.ownerUuid(), operation.commandFamilyId(), operation.profileId());
    }

    @Nullable
    private CommandTimedSummonOperationRecord findOperation(Connection connection,
                                                            String operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                OPERATION_COLUMNS + " WHERE operation_id = ? LIMIT 1")) {
            statement.setString(1, requireText(operationId, "operationId"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readOperation(result) : null;
            }
        }
    }

    @Nullable
    private CommandTimedSummonOperationRecord findExistingOperation(
            Connection connection,
            CommandTimedSummonOperationRecord requested) throws Exception {
        CommandTimedSummonOperationRecord direct = findOperation(connection, requested.operationId());
        if (direct != null) {
            return direct;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                OPERATION_COLUMNS + " WHERE caller_namespace = ? AND idempotency_key = ? LIMIT 1")) {
            statement.setString(1, requested.callerNamespace());
            statement.setString(2, requested.idempotencyKey());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readOperation(result) : null;
            }
        }
    }

    private void insertOperation(Connection connection,
                                 CommandTimedSummonOperationRecord operation) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO command_timed_summon_operations (
                    operation_id, caller_namespace, idempotency_key, owner_uuid,
                    command_family_id, profile_id, operation_kind, operation_state,
                    expected_state, expected_row_revision, expected_profile_revision,
                    population_operation_id, projection_npc_uuid, resulting_row_revision,
                    summon_session_id, result_state, reason, created_at_ms, updated_at_ms,
                    completed_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int index = 1;
            statement.setString(index++, operation.operationId());
            statement.setString(index++, operation.callerNamespace());
            statement.setString(index++, operation.idempotencyKey());
            statement.setString(index++, operation.ownerUuid().toString());
            statement.setString(index++, operation.commandFamilyId());
            statement.setString(index++, operation.profileId());
            statement.setString(index++, operation.kind().name());
            statement.setString(index++, operation.operationState().name());
            statement.setString(index++, operation.expectedState().name());
            statement.setLong(index++, operation.expectedRowRevision());
            setLong(statement, index++, operation.expectedProfileRevision());
            setText(statement, index++, operation.populationOperationId());
            setText(statement, index++, operation.projectionNpcUuid() == null
                    ? null : operation.projectionNpcUuid().toString());
            setLong(statement, index++, operation.resultingRowRevision());
            setText(statement, index++, operation.summonSessionId());
            statement.setString(index++, operation.resultState().name());
            setText(statement, index++, operation.reason());
            statement.setLong(index++, operation.createdAtMs());
            statement.setLong(index++, operation.updatedAtMs());
            statement.setLong(index, operation.completedAtMs());
            statement.executeUpdate();
        }
    }

    private void updateOperationState(Connection connection,
                                      String operationId,
                                      CommandTimedSummonOperationRecord.OperationState expected,
                                      CommandTimedSummonOperationRecord.OperationState next,
                                      @Nullable Long resultingRevision,
                                      @Nullable String reason,
                                      long updatedAtMs,
                                      long completedAtMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_timed_summon_operations
                SET operation_state = ?, resulting_row_revision = ?, reason = ?,
                    updated_at_ms = ?, completed_at_ms = ?
                WHERE operation_id = ? AND operation_state = ?
                """)) {
            statement.setString(1, next.name());
            setLong(statement, 2, resultingRevision);
            setText(statement, 3, reason);
            statement.setLong(4, updatedAtMs);
            statement.setLong(5, completedAtMs);
            statement.setString(6, operationId);
            statement.setString(7, expected.name());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Timed summon operation changed during transition.");
            }
        }
    }

    private CommandTimedSummonSessionRecord readSession(ResultSet result) throws Exception {
        return new CommandTimedSummonSessionRecord(
                UUID.fromString(result.getString("owner_uuid")),
                result.getString("command_family_id"), result.getString("profile_id"),
                result.getLong("row_revision"),
                CommandTimedSummonSessionRecord.State.valueOf(result.getString("summon_state")),
                result.getString("summon_session_id"), nullableLong(result, "summon_remaining_ms"),
                result.getLong("resummon_cooldown_until_ms"), result.getString("summon_config_id"),
                nullableLong(result, "summon_config_revision"),
                CommandTimedSummonPolicySnapshot.fromJson(result.getString("summon_policy_json")),
                warningReceiptsFromJson(result.getString("warning_receipts_json")),
                nullableLong(result, "summon_last_checkpoint_at_ms"),
                result.getString("active_operation_id"), result.getLong("created_at_ms"),
                result.getLong("updated_at_ms"));
    }

    private CommandTimedSummonSessionRecord cache(CommandTimedSummonSessionRecord session) {
        SessionKey key = new SessionKey(
                session.ownerUuid(), session.commandFamilyId(), session.profileId());
        sessionCache.compute(key, (ignored, current) -> current == null
                || session.rowRevision() >= current.rowRevision() ? session : current);
        return sessionCache.get(key);
    }

    private CommandTimedSummonOperationRecord readOperation(ResultSet result) throws Exception {
        return new CommandTimedSummonOperationRecord(
                result.getString("operation_id"), result.getString("caller_namespace"),
                result.getString("idempotency_key"), UUID.fromString(result.getString("owner_uuid")),
                result.getString("command_family_id"), result.getString("profile_id"),
                CommandTimedSummonOperationRecord.Kind.valueOf(result.getString("operation_kind")),
                CommandTimedSummonOperationRecord.OperationState.valueOf(result.getString("operation_state")),
                CommandTimedSummonSessionRecord.State.valueOf(result.getString("expected_state")),
                result.getLong("expected_row_revision"), nullableLong(result, "expected_profile_revision"),
                result.getString("population_operation_id"), nullableUuid(result, "projection_npc_uuid"),
                nullableLong(result, "resulting_row_revision"),
                result.getString("summon_session_id"),
                CommandTimedSummonSessionRecord.State.valueOf(result.getString("result_state")),
                result.getString("reason"), result.getLong("created_at_ms"),
                result.getLong("updated_at_ms"), result.getLong("completed_at_ms"));
    }

    private static void bindSession(PreparedStatement statement,
                                    CommandTimedSummonSessionRecord session) throws Exception {
        int index = 1;
        statement.setString(index++, session.ownerUuid().toString());
        statement.setString(index++, session.commandFamilyId());
        statement.setString(index++, session.profileId());
        statement.setLong(index++, session.rowRevision());
        statement.setString(index++, session.state().name());
        setText(statement, index++, session.summonSessionId());
        setLong(statement, index++, session.summonRemainingMs());
        statement.setLong(index++, session.resummonCooldownUntilMs());
        setText(statement, index++, session.summonConfigId());
        setLong(statement, index++, session.summonConfigRevision());
        statement.setString(index++, CommandTimedSummonPolicySnapshot.toJson(session.summonPolicy()));
        statement.setString(index++, warningReceiptsToJson(session.emittedWarningThresholdsMs()));
        setLong(statement, index++, session.summonLastCheckpointAtMs());
        setText(statement, index++, session.activeOperationId());
        statement.setLong(index++, session.createdAtMs());
        statement.setLong(index, session.updatedAtMs());
    }

    @Nullable
    private static String validateExpectedSession(@Nullable CommandTimedSummonSessionRecord session,
                                                  CommandTimedSummonOperationRecord operation) {
        if (session == null) return "session_not_found";
        if (session.rowRevision() != operation.expectedRowRevision()) return "session_revision_changed";
        if (session.state() != operation.expectedState()) return "session_state_changed";
        if (session.activeOperationId() != null) return "session_operation_in_progress";
        if (operation.kind() == CommandTimedSummonOperationRecord.Kind.SUMMON) {
            if (session.state() != CommandTimedSummonSessionRecord.State.ROSTER_STORED) {
                return "summon_requires_roster_stored";
            }
            if (operation.resultState() != CommandTimedSummonSessionRecord.State.ACTIVE) {
                return "summon_result_must_be_active";
            }
        } else if (operation.kind() == CommandTimedSummonOperationRecord.Kind.STORE) {
            if (session.state() != CommandTimedSummonSessionRecord.State.ACTIVE
                    && session.state() != CommandTimedSummonSessionRecord.State.UNLOADED) {
                return "store_requires_active";
            }
            if (operation.resultState() != CommandTimedSummonSessionRecord.State.ROSTER_STORED) {
                return "store_result_must_be_roster_stored";
            }
            if (!Objects.equals(operation.summonSessionId(), session.summonSessionId())) {
                return "summon_session_changed";
            }
        }
        return null;
    }

    private static void validateClaimMutation(CommandTimedSummonSessionRecord session,
                                              CommandTimedSummonOperationRecord operation,
                                              ClaimMutation mutation) {
        if (mutation.nowMs() < session.updatedAtMs()) {
            throw new IllegalArgumentException("claim time cannot move backwards.");
        }
        if (operation.kind() == CommandTimedSummonOperationRecord.Kind.SUMMON) {
            if (!Objects.equals(operation.summonSessionId(), mutation.summonSessionId())) {
                throw new IllegalArgumentException("Summon claim session ID changed.");
            }
        } else if (operation.kind() == CommandTimedSummonOperationRecord.Kind.STORE) {
            if (!Objects.equals(session.summonSessionId(), mutation.summonSessionId())) {
                throw new IllegalArgumentException("Storage claim session ID changed.");
            }
            if (session.summonRemainingMs() != null && (mutation.summonRemainingMs() == null
                    || mutation.summonRemainingMs() > session.summonRemainingMs())) {
                throw new IllegalArgumentException("Storage claim cannot replenish remaining time.");
            }
        }
    }

    private static void validateCommitMutation(CommandTimedSummonSessionRecord session,
                                               CommandTimedSummonOperationRecord operation,
                                               CommitMutation mutation) {
        if (mutation.nowMs() < session.updatedAtMs()) {
            throw new IllegalArgumentException("commit time cannot move backwards.");
        }
        if (mutation.resummonCooldownUntilMs() < 0L) {
            throw new IllegalArgumentException("resummonCooldownUntilMs must be non-negative.");
        }
        if (operation.kind() == CommandTimedSummonOperationRecord.Kind.SUMMON
                && mutation.resummonCooldownUntilMs() != 0L) {
            throw new IllegalArgumentException("A successful summon clears its prior cooldown.");
        }
        if (operation.kind() == CommandTimedSummonOperationRecord.Kind.STORE
                && mutation.resummonCooldownUntilMs() < mutation.nowMs()) {
            throw new IllegalArgumentException("Storage cooldown cannot expire before commit.");
        }
    }

    private static boolean mutationCompatibleWithClaim(CommandTimedSummonSessionRecord session,
                                                       CommandTimedSummonOperationRecord operation,
                                                       ClaimMutation mutation) {
        return Objects.equals(session.activeOperationId(), operation.operationId())
                && Objects.equals(session.summonSessionId(), mutation.summonSessionId())
                && Objects.equals(session.summonRemainingMs(), mutation.summonRemainingMs());
    }

    private static CommandTimedSummonSessionRecord.State transitionalState(
            CommandTimedSummonOperationRecord.Kind kind) {
        return switch (kind) {
            case SUMMON -> CommandTimedSummonSessionRecord.State.RESTORING;
            case STORE -> CommandTimedSummonSessionRecord.State.STORING;
            default -> null;
        };
    }

    private static boolean sameInitialSession(CommandTimedSummonSessionRecord left,
                                              CommandTimedSummonSessionRecord right) {
        return left.ownerUuid().equals(right.ownerUuid())
                && left.commandFamilyId().equals(right.commandFamilyId())
                && left.profileId().equals(right.profileId())
                && left.state() == right.state()
                && Objects.equals(left.summonSessionId(), right.summonSessionId());
    }

    private static boolean sameOperation(CommandTimedSummonOperationRecord left,
                                         CommandTimedSummonOperationRecord right) {
        return left.ownerUuid().equals(right.ownerUuid())
                && left.commandFamilyId().equals(right.commandFamilyId())
                && left.profileId().equals(right.profileId())
                && left.kind() == right.kind()
                && left.expectedState() == right.expectedState()
                && left.expectedRowRevision() == right.expectedRowRevision()
                && Objects.equals(left.expectedProfileRevision(), right.expectedProfileRevision())
                && Objects.equals(left.populationOperationId(), right.populationOperationId())
                && Objects.equals(left.projectionNpcUuid(), right.projectionNpcUuid())
                && left.resultState() == right.resultState()
                && Objects.equals(left.summonSessionId(), right.summonSessionId());
    }

    private static long incrementRevision(long revision) {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("Timed summon session revision exhausted.");
        }
        return revision + 1L;
    }

    @Nullable
    private static Long nullableLong(ResultSet result, String column) throws Exception {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static void setText(PreparedStatement statement, int index, @Nullable String value)
            throws Exception {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setLong(PreparedStatement statement, int index, @Nullable Long value)
            throws Exception {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }

    @Nullable
    private static UUID nullableUuid(ResultSet result, String column) throws Exception {
        String value = result.getString(column);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    @Nonnull
    private static Set<Long> validateWarningReceipts(CommandTimedSummonSessionRecord session,
                                                     Set<Long> requested) {
        Set<Long> normalized = requested == null ? Set.of() : Set.copyOf(requested);
        if (!normalized.containsAll(session.emittedWarningThresholdsMs())) {
            throw new IllegalArgumentException("Warning receipts cannot be removed within a session.");
        }
        Set<Long> configured = new LinkedHashSet<>();
        for (long threshold : session.summonPolicy().expiryWarningThresholdsMs()) configured.add(threshold);
        if (!configured.containsAll(normalized)) {
            throw new IllegalArgumentException("Warning receipts must reference configured thresholds.");
        }
        return normalized;
    }

    @Nonnull
    private static String warningReceiptsToJson(Set<Long> receipts) {
        JsonArray array = new JsonArray();
        if (receipts != null) receipts.stream().sorted(java.util.Comparator.reverseOrder()).forEach(array::add);
        return array.toString();
    }

    @Nonnull
    private static Set<Long> warningReceiptsFromJson(String json) {
        if (json == null || json.isBlank()) return Set.of();
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonArray()) throw new IllegalArgumentException("warning_receipts_json must be an array.");
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        for (JsonElement element : parsed.getAsJsonArray()) values.add(element.getAsLong());
        return Set.copyOf(values);
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank.");
        return normalized;
    }

    private static MutationResult result(Status status,
                                         @Nullable CommandTimedSummonSessionRecord session,
                                         @Nullable CommandTimedSummonOperationRecord operation,
                                         @Nullable String reason) {
        return new MutationResult(status, session, operation, reason);
    }

    private record SessionKey(@Nonnull UUID ownerUuid,
                              @Nonnull String commandFamilyId,
                              @Nonnull String profileId) {
    }

    public enum Status {
        CREATED,
        PREPARED,
        APPLYING,
        CHECKPOINTED,
        COMMITTED,
        CANCELED,
        ROLLED_BACK,
        IDEMPOTENT,
        DENIED,
        NOT_FOUND,
        INVALID_STATE,
        CONFLICT
    }

    public enum ApplyAbsenceProof {
        PROJECTION_NEVER_CREATED,
        PROJECTION_RETAINED
    }

    public record ProjectionSnapshot(@Nonnull UUID ownerUuid,
                                     @Nonnull String commandFamilyId,
                                     @Nonnull String profileId,
                                     @Nonnull UUID sourceNpcUuid,
                                     @Nonnull String snapshotJson,
                                     @Nonnull String snapshotSha256,
                                     long updatedAtMs) {
        public ProjectionSnapshot {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
            profileId = requireText(profileId, "profileId");
            Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
            snapshotJson = requireText(snapshotJson, "snapshotJson");
            snapshotSha256 = requireText(snapshotSha256, "snapshotSha256").toLowerCase();
            if (!snapshotSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("snapshotSha256 must be lowercase SHA-256.");
            }
            if (updatedAtMs < 0L) throw new IllegalArgumentException("updatedAtMs must be non-negative.");
        }
    }

    public record MutationResult(@Nonnull Status status,
                                 @Nullable CommandTimedSummonSessionRecord session,
                                 @Nullable CommandTimedSummonOperationRecord operation,
                                 @Nullable String reason) {
    }

    public record ClaimMutation(@Nonnull String operationId,
                                @Nonnull String summonSessionId,
                                @Nullable Long summonRemainingMs,
                                @Nullable String summonConfigId,
                                @Nullable Long summonConfigRevision,
                                @Nonnull CommandTimedSummonPolicySnapshot summonPolicy,
                                long nowMs) {
        public ClaimMutation {
            operationId = requireText(operationId, "operationId");
            summonSessionId = requireText(summonSessionId, "summonSessionId");
            Objects.requireNonNull(summonPolicy, "summonPolicy");
            summonConfigId = summonConfigId == null || summonConfigId.isBlank()
                    ? null : summonConfigId.trim();
            if (summonRemainingMs != null && summonRemainingMs < 0L) {
                throw new IllegalArgumentException("summonRemainingMs must be non-negative.");
            }
            if (summonConfigRevision != null && summonConfigRevision < 0L) {
                throw new IllegalArgumentException("summonConfigRevision must be non-negative.");
            }
            if (nowMs < 0L) throw new IllegalArgumentException("nowMs must be non-negative.");
        }
    }

    public record CommitMutation(@Nonnull String operationId,
                                 @Nonnull CommandTimedSummonSessionRecord.State finalState,
                                 long resummonCooldownUntilMs,
                                 @Nullable String reason,
                                 long nowMs) {
        public CommitMutation {
            operationId = requireText(operationId, "operationId");
            Objects.requireNonNull(finalState, "finalState");
            reason = reason == null || reason.isBlank() ? null : reason.trim();
            if (resummonCooldownUntilMs < 0L || nowMs < 0L) {
                throw new IllegalArgumentException("Commit times must be non-negative.");
            }
        }
    }

    public record CheckpointMutation(@Nonnull UUID ownerUuid,
                                     @Nonnull String commandFamilyId,
                                     @Nonnull String profileId,
                                     long expectedRowRevision,
                                     @Nonnull String summonSessionId,
                                     @Nullable Long remainingMs,
                                     @Nonnull Set<Long> emittedWarningThresholdsMs,
                                     long nowMs) {
        public CheckpointMutation {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
            profileId = requireText(profileId, "profileId");
            summonSessionId = requireText(summonSessionId, "summonSessionId");
            emittedWarningThresholdsMs = emittedWarningThresholdsMs == null
                    ? Set.of() : Set.copyOf(emittedWarningThresholdsMs);
            if (expectedRowRevision < 1L || (remainingMs != null && remainingMs < 0L) || nowMs < 0L) {
                throw new IllegalArgumentException("Checkpoint revision and times must be non-negative.");
            }
        }
    }

    public record ProjectionAvailabilityMutation(
            @Nonnull UUID ownerUuid,
            @Nonnull String commandFamilyId,
            @Nonnull String profileId,
            long expectedRowRevision,
            @Nonnull String summonSessionId,
            @Nonnull CommandTimedSummonSessionRecord.State targetState,
            long nowMs) {
        public ProjectionAvailabilityMutation {
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
            profileId = requireText(profileId, "profileId");
            summonSessionId = requireText(summonSessionId, "summonSessionId");
            Objects.requireNonNull(targetState, "targetState");
            if (expectedRowRevision < 1L || nowMs < 0L) {
                throw new IllegalArgumentException("Projection availability revision/time is invalid.");
            }
        }
    }
}
