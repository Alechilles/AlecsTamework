package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.google.gson.Gson;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** SQLite authority for paid revival idempotency, frozen costs, reservations, and recovery. */
public final class PaidCommandRevivalRepository {
    private static final String OP_COLUMNS = """
            SELECT operation_id, caller_namespace, idempotency_key, owner_uuid, profile_id,
                   command_family_id, role_id, config_id, config_revision, death_revision,
                   profile_revision, population_admission_operation_id, placement_fingerprint,
                   revive_projection_operation_id, state, detail, created_at_ms, updated_at_ms,
                   completed_at_ms
            FROM paid_command_revival_operations
            """;

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public PaidCommandRevivalRepository(@Nonnull SqliteConnectionManager connectionManager,
                                        @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> prepareAsync(
            @Nonnull PaidCommandRevivalRecord requested) {
        if (requested.state() != PaidCommandRevivalRecord.State.PREPARED
                || !requested.reservations().isEmpty() || requested.completedAtMs() != null) {
            throw new IllegalArgumentException("new paid revival must be an uncompleted PREPARED operation");
        }
        return writeQueue.submitTracked("paid_command_revival_prepare",
                connection -> prepare(connection, requested), null);
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> reserveAsync(
            @Nonnull UUID operationId,
            @Nonnull List<PaidCommandRevivalRecord.Reservation> reservations,
            long nowMs) {
        List<PaidCommandRevivalRecord.Reservation> frozen = List.copyOf(reservations);
        return writeQueue.submitTracked("paid_command_revival_reserve",
                connection -> reserve(connection, operationId, frozen, nowMs), null);
    }

    /** Freezes the population reservation returned after the PREPARED journal checkpoint. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> recordActivationAsync(
            @Nonnull UUID operationId,
            @Nonnull String populationOperationId,
            @Nullable String placementFingerprint,
            @Nonnull UUID projectionNpcUuid,
            @Nullable PaidCommandRevivalApplyCommit.TimedLease timedLease,
            long nowMs) {
        String frozenPopulation = requireText(populationOperationId, "populationOperationId");
        return writeQueue.submitTracked("paid_command_revival_activation", connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
            PaidCommandRevivalRecord current = find(connection, operationId);
            if (current == null) {
                connection.commit();
                return new MutationResult(Status.NOT_FOUND, null, "operation-not-found");
            }
            if (current.state() != PaidCommandRevivalRecord.State.PREPARED) {
                connection.commit();
                return new MutationResult(Status.CONFLICT, current, "operation-not-prepared");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE paid_command_revival_operations
                    SET population_admission_operation_id = ?, placement_fingerprint = ?,
                        revive_projection_operation_id = ?, updated_at_ms = ?
                    WHERE operation_id = ? AND state = 'PREPARED'
                    """)) {
                statement.setString(1, frozenPopulation);
                setNullable(statement, 2, placementFingerprint);
                statement.setString(3, projectionNpcUuid.toString());
                statement.setLong(4, nowMs);
                statement.setString(5, operationId.toString());
                if (statement.executeUpdate() != 1) {
                    connection.commit();
                    return new MutationResult(Status.CONFLICT, current, "activation-fence-changed");
                }
            }
            insertApplyPlan(connection, operationId, projectionNpcUuid, timedLease, nowMs);
            PaidCommandRevivalApplyPlan persisted = findApplyPlan(connection, operationId);
            PaidCommandRevivalApplyPlan requested = new PaidCommandRevivalApplyPlan(
                    operationId, projectionNpcUuid, timedLease);
            if (!requested.equals(persisted)) {
                connection.rollback();
                return new MutationResult(Status.CONFLICT, current, "activation-apply-plan-changed");
            }
            connection.commit();
            return new MutationResult(Status.APPLIED, find(connection, operationId), null);
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }, null);
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> transitionAsync(
            @Nonnull UUID operationId,
            @Nonnull PaidCommandRevivalRecord.State expected,
            @Nonnull PaidCommandRevivalRecord.State next,
            @Nullable String detail,
            long nowMs) {
        validateTransition(expected, next);
        return writeQueue.submitTracked("paid_command_revival_transition",
                connection -> transition(connection, operationId, expected, next, detail, nowMs), null);
    }

    /**
     * Commits the positive paid-revival result as one durable boundary.
     *
     * <p>The caller must first prove the deterministic projection live. This transaction then fences
     * that exact profile/owner/UUID and atomically installs the optional lease, deactivates the exact
     * death revision, publishes the roster ACTIVE state, and terminalizes the paid operation. No
     * caller may compose those writes independently.</p>
     */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> commitAppliedAsync(
            @Nonnull PaidCommandRevivalApplyCommit commit) {
        Objects.requireNonNull(commit, "commit");
        return writeQueue.submitTracked("paid_command_revival_apply_commit",
                connection -> commitApplied(connection, commit), null);
    }

    /** Claims exclusive delivery of a pending exact-cost refund before inventory mutation. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<RefundDeliveryStatus> beginRefundDeliveryAsync(
            @Nonnull UUID operationId, long nowMs) {
        return writeQueue.submitTracked("paid_command_revival_refund_begin", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE paid_command_revival_refund_claims
                    SET state = 'DELIVERING', updated_at_ms = ?
                    WHERE operation_id = ? AND state = 'PENDING'
                    """)) {
                statement.setLong(1, nowMs);
                statement.setString(2, operationId.toString());
                if (statement.executeUpdate() == 1) return RefundDeliveryStatus.STARTED;
            }
            RefundDeliveryStatus current = findRefundDeliveryStatus(connection, operationId);
            return current != null ? current : RefundDeliveryStatus.MISSING;
        }, null);
    }

    /** Returns a known failed, non-mutating delivery attempt to its retryable state. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Boolean> resetRefundDeliveryAsync(
            @Nonnull UUID operationId, long nowMs) {
        return writeQueue.submitTracked("paid_command_revival_refund_reset", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE paid_command_revival_refund_claims
                    SET state = 'PENDING', updated_at_ms = ?
                    WHERE operation_id = ? AND state = 'DELIVERING'
                    """)) {
                statement.setLong(1, nowMs);
                statement.setString(2, operationId.toString());
                return statement.executeUpdate() == 1;
            }
        }, null);
    }

    @Nullable
    public RefundDeliveryStatus findRefundDeliveryStatus(@Nonnull UUID operationId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findRefundDeliveryStatus(connection, operationId);
        }
    }

    @Nullable
    public PaidCommandRevivalRecord findByIdempotency(@Nonnull String callerNamespace,
                                                       @Nonnull String idempotencyKey) throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     OP_COLUMNS + " WHERE caller_namespace = ? AND idempotency_key = ?")) {
            statement.setString(1, requireText(callerNamespace, "callerNamespace"));
            statement.setString(2, requireText(idempotencyKey, "idempotencyKey"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(connection, result) : null;
            }
        }
    }

    @Nullable
    public PaidCommandRevivalRecord find(@Nonnull UUID operationId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return find(connection, operationId);
        }
    }

    @Nullable
    public PaidCommandRevivalApplyPlan findApplyPlan(@Nonnull UUID operationId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findApplyPlan(connection, operationId);
        }
    }

    /** Previous canonical UUID retained by the profile alias ledger for post-commit cache cleanup. */
    @Nullable
    public UUID findRevivedDeathSourceNpcUuid(@Nonnull UUID operationId) throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT a.npc_uuid
                     FROM paid_command_revival_operations o
                     JOIN npc_uuid_aliases a ON a.profile_id = o.profile_id
                     WHERE o.operation_id = ? AND a.is_current = 0
                       AND a.npc_uuid <> o.revive_projection_operation_id
                     ORDER BY a.mapped_at_ms DESC LIMIT 1
                     """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? UUID.fromString(result.getString(1)) : null;
            }
        }
    }

    @Nonnull
    public List<PaidCommandRevivalRecord> loadRecoverable() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(OP_COLUMNS
                     + " WHERE state IN ('PREPARED','RESERVED','COST_CONSUMED','APPLYING',"
                     + "'REFUND_REQUIRED','QUARANTINED') ORDER BY created_at_ms, operation_id");
             ResultSet result = statement.executeQuery()) {
            ArrayList<PaidCommandRevivalRecord> rows = new ArrayList<>();
            while (result.next()) rows.add(read(connection, result));
            return List.copyOf(rows);
        }
    }

    private MutationResult prepare(Connection connection, PaidCommandRevivalRecord requested) throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            PaidCommandRevivalRecord existing = findByIdempotency(
                    connection, requested.callerNamespace(), requested.idempotencyKey());
            if (existing != null) {
                MutationResult result = sameRequest(existing, requested)
                        ? new MutationResult(Status.IDEMPOTENT, existing, null)
                        : new MutationResult(Status.CONFLICT, existing, "idempotency-key-payload-mismatch");
                connection.commit();
                return result;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO paid_command_revival_operations (
                        operation_id, caller_namespace, idempotency_key, owner_uuid, profile_id,
                        command_family_id, role_id, config_id, config_revision, death_revision,
                        profile_revision, population_admission_operation_id, placement_fingerprint,
                        revive_projection_operation_id, state, detail, created_at_ms, updated_at_ms,
                        completed_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?, ?, NULL)
                    """)) {
                int index = 1;
                statement.setString(index++, requested.operationId().toString());
                statement.setString(index++, requested.callerNamespace());
                statement.setString(index++, requested.idempotencyKey());
                statement.setString(index++, requested.ownerUuid().toString());
                statement.setString(index++, requested.profileId());
                statement.setString(index++, requested.commandFamilyId());
                statement.setString(index++, requested.roleId());
                setNullable(statement, index++, requested.configId());
                statement.setString(index++, requested.configRevision());
                statement.setLong(index++, requested.deathRevision());
                statement.setLong(index++, requested.profileRevision());
                setNullable(statement, index++, requested.populationAdmissionOperationId());
                setNullable(statement, index++, requested.placementFingerprint());
                setNullable(statement, index++, requested.reviveProjectionOperationId());
                setNullable(statement, index++, requested.detail());
                statement.setLong(index++, requested.createdAtMs());
                statement.setLong(index, requested.updatedAtMs());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO paid_command_revival_costs(operation_id, ordinal, item_id, quantity)
                    VALUES (?, ?, ?, ?)
                    """)) {
                for (int ordinal = 0; ordinal < requested.exactCost().size(); ordinal++) {
                    ItemCostComponentView cost = requested.exactCost().get(ordinal);
                    statement.setString(1, requested.operationId().toString());
                    statement.setInt(2, ordinal);
                    statement.setString(3, cost.itemId());
                    statement.setInt(4, cost.quantity());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
            return new MutationResult(Status.APPLIED, requested, null);
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private MutationResult reserve(Connection connection, UUID operationId,
                                   List<PaidCommandRevivalRecord.Reservation> reservations,
                                   long nowMs) throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            PaidCommandRevivalRecord current = find(connection, operationId);
            if (current == null) return new MutationResult(Status.NOT_FOUND, null, "operation-not-found");
            if (current.state() == PaidCommandRevivalRecord.State.RESERVED
                    && current.reservations().equals(reservations)) {
                connection.commit();
                return new MutationResult(Status.IDEMPOTENT, current, null);
            }
            if (current.state() != PaidCommandRevivalRecord.State.PREPARED) {
                connection.commit();
                return new MutationResult(Status.CONFLICT, current, "operation-not-prepared");
            }
            validateReservations(current.exactCost(), reservations);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO paid_command_revival_reservations (
                        operation_id, cost_ordinal, stack_ordinal, compartment_id, slot_index,
                        quantity, source_stack_fingerprint, reservation_generation, state)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'HELD')
                    """)) {
                for (PaidCommandRevivalRecord.Reservation reservation : reservations) {
                    statement.setString(1, operationId.toString());
                    statement.setInt(2, reservation.costOrdinal());
                    statement.setInt(3, reservation.stackOrdinal());
                    statement.setString(4, reservation.compartmentId());
                    statement.setInt(5, reservation.slotIndex());
                    statement.setInt(6, reservation.quantity());
                    statement.setString(7, reservation.sourceStackFingerprint());
                    statement.setLong(8, reservation.reservationGeneration());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            updateState(connection, operationId, PaidCommandRevivalRecord.State.PREPARED,
                    PaidCommandRevivalRecord.State.RESERVED, null, nowMs);
            connection.commit();
            return new MutationResult(Status.APPLIED, find(connection, operationId), null);
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private MutationResult transition(Connection connection, UUID operationId,
                                      PaidCommandRevivalRecord.State expected,
                                      PaidCommandRevivalRecord.State next,
                                      String detail, long nowMs) throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            PaidCommandRevivalRecord current = find(connection, operationId);
            if (current == null) return new MutationResult(Status.NOT_FOUND, null, "operation-not-found");
            if (current.state() == next) {
                connection.commit();
                return new MutationResult(Status.IDEMPOTENT, current, null);
            }
            if (current.state() != expected) {
                connection.commit();
                return new MutationResult(Status.CONFLICT, current, "unexpected-operation-state");
            }
            updateState(connection, operationId, expected, next, detail, nowMs);
            updateReservationStates(connection, operationId, next);
            if (next == PaidCommandRevivalRecord.State.REFUND_REQUIRED) {
                upsertRefundClaim(connection, current, nowMs);
            } else if (next == PaidCommandRevivalRecord.State.REFUNDED) {
                markRefundDelivered(connection, operationId, nowMs);
            } else if (next == PaidCommandRevivalRecord.State.QUARANTINED
                    && current.state() == PaidCommandRevivalRecord.State.REFUND_REQUIRED) {
                markRefundQuarantined(connection, operationId, nowMs);
            }
            connection.commit();
            return new MutationResult(Status.APPLIED, find(connection, operationId), null);
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private MutationResult commitApplied(Connection connection,
                                         PaidCommandRevivalApplyCommit commit) throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            PaidCommandRevivalRecord operation = find(connection, commit.operationId());
            if (operation == null) {
                connection.commit();
                return new MutationResult(Status.NOT_FOUND, null, "operation-not-found");
            }
            String mismatch = applyCommitMismatch(operation, commit);
            if (mismatch != null) {
                connection.commit();
                return new MutationResult(Status.CONFLICT, operation, mismatch);
            }
            PaidCommandRevivalApplyPlan frozenPlan = findApplyPlan(connection, commit.operationId());
            if (frozenPlan == null
                    || !frozenPlan.projectionNpcUuid().equals(commit.projectionNpcUuid())
                    || !Objects.equals(frozenPlan.timedLease(), commit.timedLease())) {
                connection.commit();
                return new MutationResult(Status.CONFLICT, operation, "apply-plan-changed");
            }
            if (operation.state() == PaidCommandRevivalRecord.State.SUCCEEDED) {
                connection.commit();
                return new MutationResult(Status.IDEMPOTENT, operation, null);
            }
            if (operation.state() != PaidCommandRevivalRecord.State.APPLYING
                    && operation.state() != PaidCommandRevivalRecord.State.QUARANTINED) {
                connection.commit();
                return new MutationResult(Status.CONFLICT, operation, "operation-not-applying");
            }

            ProjectionAuthority projection = findProjectionAuthority(connection, commit.profileId());
            if (projection == null
                    || !commit.ownerUuid().equals(projection.ownerUuid())
                    || !commit.projectionNpcUuid().equals(projection.currentNpcUuid())
                    || !"ACTIVE".equals(projection.lifecycleState())
                    || projection.populationRevision() <= operation.profileRevision()) {
                connection.commit();
                return new MutationResult(Status.CONFLICT, operation,
                        "deterministic-live-projection-not-proven");
            }
            if (!hasExactActiveDeath(connection, commit.profileId(), commit.expectedDeathRevision())) {
                connection.commit();
                return new MutationResult(Status.CONFLICT, operation, "death-revision-changed");
            }
            if (!hasRevivalRosterSource(connection, commit)) {
                connection.commit();
                return new MutationResult(Status.CONFLICT, operation, "roster-revival-source-changed");
            }

            if (commit.timedLease() != null) {
                installRevivalLease(connection, commit);
            }
            deactivateExactDeath(connection, commit);
            clearProfileDeathFlag(connection, commit.profileId(), commit.nowMs());
            publishRosterActive(connection, commit, projection.populationRevision());
            updateState(connection, commit.operationId(), operation.state(),
                    PaidCommandRevivalRecord.State.SUCCEEDED, null, commit.nowMs());
            updateReservationStates(connection, commit.operationId(), PaidCommandRevivalRecord.State.SUCCEEDED);
            connection.commit();
            return new MutationResult(Status.APPLIED, find(connection, commit.operationId()), null);
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    @Nullable
    private static String applyCommitMismatch(PaidCommandRevivalRecord operation,
                                              PaidCommandRevivalApplyCommit commit) {
        if (!operation.ownerUuid().equals(commit.ownerUuid())
                || !operation.commandFamilyId().equals(commit.commandFamilyId())
                || !operation.profileId().equals(commit.profileId())) {
            return "apply-commit-operation-mismatch";
        }
        if (operation.deathRevision() != commit.expectedDeathRevision()) {
            return "apply-commit-death-revision-mismatch";
        }
        if (operation.reviveProjectionOperationId() == null
                || !operation.reviveProjectionOperationId().equals(commit.projectionNpcUuid().toString())) {
            return "apply-commit-projection-identity-mismatch";
        }
        return null;
    }

    @Nullable
    private ProjectionAuthority findProjectionAuthority(Connection connection, String profileId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.owner_uuid, p.current_npc_uuid, s.lifecycle_state, s.revision
                FROM npc_profiles p
                JOIN companion_population_state s ON s.profile_id = p.profile_id
                WHERE p.profile_id = ? LIMIT 1
                """)) {
            statement.setString(1, profileId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                String owner = result.getString("owner_uuid");
                String npc = result.getString("current_npc_uuid");
                return owner == null || npc == null ? null : new ProjectionAuthority(
                        UUID.fromString(owner), UUID.fromString(npc),
                        result.getString("lifecycle_state"), result.getLong("revision"));
            }
        }
    }

    private static boolean hasExactActiveDeath(Connection connection, String profileId,
                                               long deathRevision) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM npc_snapshots
                WHERE profile_id = ? AND snapshot_type = 'death'
                  AND snapshot_version = ? AND is_active = 1
                LIMIT 1
                """)) {
            statement.setString(1, profileId);
            statement.setLong(2, deathRevision);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean hasRevivalRosterSource(Connection connection,
                                                  PaidCommandRevivalApplyCommit commit) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT command_state, profile_revision FROM command_family_roster_memberships
                WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                LIMIT 1
                """)) {
            statement.setString(1, commit.ownerUuid().toString());
            statement.setString(2, commit.commandFamilyId());
            statement.setString(3, commit.profileId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return false;
                String state = result.getString("command_state");
                return ("DEAD_REVIVABLE".equals(state) || "RESTORING".equals(state))
                        && result.getLong("profile_revision") == operationProfileRevision(connection,
                        commit.operationId());
            }
        }
    }

    private static long operationProfileRevision(Connection connection, UUID operationId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_revision FROM paid_command_revival_operations WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("paid revival operation missing");
                return result.getLong("profile_revision");
            }
        }
    }

    private static void installRevivalLease(Connection connection,
                                            PaidCommandRevivalApplyCommit commit) throws Exception {
        PaidCommandRevivalApplyCommit.TimedLease lease = commit.timedLease();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO command_timed_summon_sessions (
                    owner_uuid, command_family_id, profile_id, row_revision, summon_state,
                    summon_session_id, summon_remaining_ms, resummon_cooldown_until_ms,
                    summon_config_id, summon_config_revision, summon_policy_json,
                    warning_receipts_json, summon_last_checkpoint_at_ms, active_operation_id,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 1, 'ACTIVE', ?, ?, 0, ?, ?, ?, '[]', ?, NULL, ?, ?)
                ON CONFLICT(owner_uuid, command_family_id, profile_id) DO UPDATE SET
                    row_revision = command_timed_summon_sessions.row_revision + 1,
                    summon_state = 'ACTIVE', summon_session_id = excluded.summon_session_id,
                    summon_remaining_ms = excluded.summon_remaining_ms,
                    resummon_cooldown_until_ms = 0,
                    summon_config_id = excluded.summon_config_id,
                    summon_config_revision = excluded.summon_config_revision,
                    summon_policy_json = excluded.summon_policy_json,
                    warning_receipts_json = '[]',
                    summon_last_checkpoint_at_ms = excluded.summon_last_checkpoint_at_ms,
                    active_operation_id = NULL, updated_at_ms = excluded.updated_at_ms
                """)) {
            int index = 1;
            statement.setString(index++, commit.ownerUuid().toString());
            statement.setString(index++, commit.commandFamilyId());
            statement.setString(index++, commit.profileId());
            statement.setString(index++, lease.sessionId());
            if (lease.remainingMs() == null) statement.setNull(index++, Types.BIGINT);
            else statement.setLong(index++, lease.remainingMs());
            setNullable(statement, index++, lease.configId());
            if (lease.configRevision() == null) statement.setNull(index++, Types.BIGINT);
            else statement.setLong(index++, lease.configRevision());
            statement.setString(index++, CommandTimedSummonPolicySnapshot.toJson(lease.policy()));
            statement.setLong(index++, commit.nowMs());
            statement.setLong(index++, commit.nowMs());
            statement.setLong(index, commit.nowMs());
            statement.executeUpdate();
        }
    }

    private static void deactivateExactDeath(Connection connection,
                                             PaidCommandRevivalApplyCommit commit) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE npc_snapshots SET is_active = 0
                WHERE profile_id = ? AND snapshot_type = 'death'
                  AND snapshot_version = ? AND is_active = 1
                """)) {
            statement.setString(1, commit.profileId());
            statement.setLong(2, commit.expectedDeathRevision());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("death snapshot changed during revival commit");
            }
        }
    }

    private static void clearProfileDeathFlag(Connection connection, String profileId, long nowMs)
            throws Exception {
        try (PreparedStatement ensure = connection.prepareStatement("""
                INSERT INTO profile_states (
                    profile_id, capture_active, death_active, lost_active, in_coop, coop_key, updated_at_ms)
                VALUES (?, 0, 0, 0, 0, NULL, ?)
                ON CONFLICT(profile_id) DO NOTHING
                """)) {
            ensure.setString(1, profileId);
            ensure.setLong(2, nowMs);
            ensure.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE profile_states SET death_active = 0, updated_at_ms = ? WHERE profile_id = ?
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, profileId);
            statement.executeUpdate();
        }
    }

    private static void publishRosterActive(Connection connection,
                                            PaidCommandRevivalApplyCommit commit,
                                            long profileRevision) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_family_roster_memberships
                SET command_state = 'ACTIVE', profile_revision = ?, updated_at_ms = ?
                WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                  AND command_state IN ('DEAD_REVIVABLE','RESTORING')
                """)) {
            statement.setLong(1, profileRevision);
            statement.setLong(2, commit.nowMs());
            statement.setString(3, commit.ownerUuid().toString());
            statement.setString(4, commit.commandFamilyId());
            statement.setString(5, commit.profileId());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("roster source changed during revival commit");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_family_rosters
                SET row_revision = row_revision + 1, updated_at_ms = ?
                WHERE owner_uuid = ? AND command_family_id = ?
                """)) {
            statement.setLong(1, commit.nowMs());
            statement.setString(2, commit.ownerUuid().toString());
            statement.setString(3, commit.commandFamilyId());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("command roster missing during revival commit");
            }
        }
    }

    private record ProjectionAuthority(UUID ownerUuid, UUID currentNpcUuid,
                                       String lifecycleState, long populationRevision) { }

    private static void insertApplyPlan(Connection connection, UUID operationId,
                                        UUID projectionNpcUuid,
                                        @Nullable PaidCommandRevivalApplyCommit.TimedLease lease,
                                        long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO paid_command_revival_apply_plans(
                    operation_id, projection_npc_uuid, summon_session_id, summon_remaining_ms,
                    summon_config_id, summon_config_revision, summon_policy_json, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(operation_id) DO NOTHING
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, projectionNpcUuid.toString());
            setNullable(statement, 3, lease == null ? null : lease.sessionId());
            if (lease == null || lease.remainingMs() == null) statement.setNull(4, Types.BIGINT);
            else statement.setLong(4, lease.remainingMs());
            setNullable(statement, 5, lease == null ? null : lease.configId());
            if (lease == null || lease.configRevision() == null) statement.setNull(6, Types.BIGINT);
            else statement.setLong(6, lease.configRevision());
            setNullable(statement, 7, lease == null ? null
                    : CommandTimedSummonPolicySnapshot.toJson(lease.policy()));
            statement.setLong(8, nowMs);
            statement.executeUpdate();
        }
    }

    @Nullable
    private static PaidCommandRevivalApplyPlan findApplyPlan(Connection connection, UUID operationId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT projection_npc_uuid, summon_session_id, summon_remaining_ms,
                       summon_config_id, summon_config_revision, summon_policy_json
                FROM paid_command_revival_apply_plans WHERE operation_id = ? LIMIT 1
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                String sessionId = result.getString("summon_session_id");
                PaidCommandRevivalApplyCommit.TimedLease lease = sessionId == null ? null
                        : new PaidCommandRevivalApplyCommit.TimedLease(
                        sessionId, nullableLong(result, "summon_remaining_ms"),
                        result.getString("summon_config_id"),
                        nullableLong(result, "summon_config_revision"),
                        CommandTimedSummonPolicySnapshot.fromJson(result.getString("summon_policy_json")));
                return new PaidCommandRevivalApplyPlan(operationId,
                        UUID.fromString(result.getString("projection_npc_uuid")), lease);
            }
        }
    }

    private void updateState(Connection connection, UUID operationId,
                             PaidCommandRevivalRecord.State expected,
                             PaidCommandRevivalRecord.State next,
                             String detail, long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE paid_command_revival_operations
                SET state = ?, detail = ?, updated_at_ms = ?, completed_at_ms = ?
                WHERE operation_id = ? AND state = ?
                """)) {
            statement.setString(1, next.name());
            setNullable(statement, 2, detail);
            statement.setLong(3, nowMs);
            if (terminal(next)) statement.setLong(4, nowMs); else statement.setNull(4, Types.BIGINT);
            statement.setString(5, operationId.toString());
            statement.setString(6, expected.name());
            if (statement.executeUpdate() != 1) throw new IllegalStateException("operation-state-cas-failed");
        }
    }

    private void updateReservationStates(Connection connection, UUID operationId,
                                         PaidCommandRevivalRecord.State next) throws Exception {
        String reservationState = switch (next) {
            case COST_CONSUMED, APPLYING, SUCCEEDED -> "CONSUMED";
            case CANCELED -> "RELEASED";
            case REFUND_REQUIRED, QUARANTINED -> "REFUND_REQUIRED";
            case REFUNDED -> "REFUNDED";
            default -> null;
        };
        if (reservationState == null) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE paid_command_revival_reservations SET state = ? WHERE operation_id = ?
                """)) {
            statement.setString(1, reservationState);
            statement.setString(2, operationId.toString());
            statement.executeUpdate();
        }
    }

    private void upsertRefundClaim(Connection connection, PaidCommandRevivalRecord operation,
                                   long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO paid_command_revival_refund_claims(
                    operation_id, owner_uuid, exact_cost_json, state, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'PENDING', ?, ?)
                ON CONFLICT(operation_id) DO UPDATE SET updated_at_ms = excluded.updated_at_ms
                """)) {
            statement.setString(1, operation.operationId().toString());
            statement.setString(2, operation.ownerUuid().toString());
            statement.setString(3, new Gson().toJson(operation.exactCost()));
            statement.setLong(4, nowMs);
            statement.setLong(5, nowMs);
            statement.executeUpdate();
        }
    }

    private void markRefundDelivered(Connection connection, UUID operationId, long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE paid_command_revival_refund_claims
                SET state = 'DELIVERED', updated_at_ms = ?
                WHERE operation_id = ? AND state IN ('PENDING','DELIVERING')
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, operationId.toString());
            statement.executeUpdate();
        }
    }

    private void markRefundQuarantined(Connection connection, UUID operationId, long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE paid_command_revival_refund_claims
                SET state = 'QUARANTINED', updated_at_ms = ?
                WHERE operation_id = ? AND state IN ('PENDING','DELIVERING')
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, operationId.toString());
            statement.executeUpdate();
        }
    }

    private RefundDeliveryStatus findRefundDeliveryStatus(Connection connection, UUID operationId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT state FROM paid_command_revival_refund_claims WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return switch (result.getString("state")) {
                    case "PENDING" -> RefundDeliveryStatus.PENDING;
                    case "DELIVERING" -> RefundDeliveryStatus.DELIVERING;
                    case "DELIVERED" -> RefundDeliveryStatus.DELIVERED;
                    default -> RefundDeliveryStatus.QUARANTINED;
                };
            }
        }
    }

    private PaidCommandRevivalRecord find(Connection connection, UUID operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(OP_COLUMNS + " WHERE operation_id = ?")) {
            statement.setString(1, operationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(connection, result) : null;
            }
        }
    }

    private PaidCommandRevivalRecord findByIdempotency(Connection connection, String callerNamespace,
                                                        String idempotencyKey) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                OP_COLUMNS + " WHERE caller_namespace = ? AND idempotency_key = ?")) {
            statement.setString(1, callerNamespace);
            statement.setString(2, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(connection, result) : null;
            }
        }
    }

    private PaidCommandRevivalRecord read(Connection connection, ResultSet result) throws Exception {
        UUID operationId = UUID.fromString(result.getString("operation_id"));
        return new PaidCommandRevivalRecord(
                operationId, result.getString("caller_namespace"), result.getString("idempotency_key"),
                UUID.fromString(result.getString("owner_uuid")), result.getString("profile_id"),
                result.getString("command_family_id"), result.getString("role_id"),
                result.getString("config_id"), result.getString("config_revision"),
                result.getLong("death_revision"), result.getLong("profile_revision"),
                result.getString("population_admission_operation_id"),
                result.getString("placement_fingerprint"),
                result.getString("revive_projection_operation_id"),
                PaidCommandRevivalRecord.State.valueOf(result.getString("state")),
                readCosts(connection, operationId), readReservations(connection, operationId),
                result.getString("detail"), result.getLong("created_at_ms"),
                result.getLong("updated_at_ms"), nullableLong(result, "completed_at_ms"));
    }

    private List<ItemCostComponentView> readCosts(Connection connection, UUID operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT item_id, quantity FROM paid_command_revival_costs
                WHERE operation_id = ? ORDER BY ordinal
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                ArrayList<ItemCostComponentView> rows = new ArrayList<>();
                while (result.next()) rows.add(new ItemCostComponentView(
                        result.getString("item_id"), result.getInt("quantity")));
                return List.copyOf(rows);
            }
        }
    }

    private List<PaidCommandRevivalRecord.Reservation> readReservations(
            Connection connection, UUID operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT cost_ordinal, stack_ordinal, compartment_id, slot_index, quantity,
                       source_stack_fingerprint, reservation_generation, state
                FROM paid_command_revival_reservations WHERE operation_id = ?
                ORDER BY cost_ordinal, stack_ordinal
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                ArrayList<PaidCommandRevivalRecord.Reservation> rows = new ArrayList<>();
                while (result.next()) rows.add(new PaidCommandRevivalRecord.Reservation(
                        result.getInt("cost_ordinal"), result.getInt("stack_ordinal"),
                        result.getString("compartment_id"), result.getInt("slot_index"),
                        result.getInt("quantity"), result.getString("source_stack_fingerprint"),
                        result.getLong("reservation_generation"),
                        PaidCommandRevivalRecord.ReservationState.valueOf(result.getString("state"))));
                return List.copyOf(rows);
            }
        }
    }

    private static void validateReservations(List<ItemCostComponentView> costs,
                                             List<PaidCommandRevivalRecord.Reservation> reservations) {
        int[] totals = new int[costs.size()];
        for (PaidCommandRevivalRecord.Reservation reservation : reservations) {
            if (reservation.costOrdinal() >= costs.size()) {
                throw new IllegalArgumentException("reservation cost ordinal out of bounds");
            }
            totals[reservation.costOrdinal()] = Math.addExact(
                    totals[reservation.costOrdinal()], reservation.quantity());
        }
        for (int ordinal = 0; ordinal < costs.size(); ordinal++) {
            if (totals[ordinal] != costs.get(ordinal).quantity()) {
                throw new IllegalArgumentException("reservation total does not match cost " + ordinal);
            }
        }
    }

    private static boolean sameRequest(PaidCommandRevivalRecord left, PaidCommandRevivalRecord right) {
        return left.ownerUuid().equals(right.ownerUuid())
                && left.profileId().equals(right.profileId())
                && left.commandFamilyId().equals(right.commandFamilyId())
                && left.roleId().equals(right.roleId())
                && Objects.equals(left.configId(), right.configId())
                && left.configRevision().equals(right.configRevision())
                && left.deathRevision() == right.deathRevision()
                && left.profileRevision() == right.profileRevision()
                && left.exactCost().equals(right.exactCost());
    }

    private static void validateTransition(PaidCommandRevivalRecord.State expected,
                                           PaidCommandRevivalRecord.State next) {
        boolean allowed = switch (expected) {
            case PREPARED -> next == PaidCommandRevivalRecord.State.CANCELED
                    || next == PaidCommandRevivalRecord.State.QUARANTINED;
            case RESERVED -> next == PaidCommandRevivalRecord.State.COST_CONSUMED
                    || next == PaidCommandRevivalRecord.State.CANCELED
                    || next == PaidCommandRevivalRecord.State.QUARANTINED;
            case COST_CONSUMED -> next == PaidCommandRevivalRecord.State.APPLYING
                    || next == PaidCommandRevivalRecord.State.REFUND_REQUIRED
                    || next == PaidCommandRevivalRecord.State.QUARANTINED;
            case APPLYING -> next == PaidCommandRevivalRecord.State.SUCCEEDED
                    || next == PaidCommandRevivalRecord.State.REFUND_REQUIRED
                    || next == PaidCommandRevivalRecord.State.QUARANTINED;
            case REFUND_REQUIRED -> next == PaidCommandRevivalRecord.State.REFUNDED
                    || next == PaidCommandRevivalRecord.State.QUARANTINED;
            case QUARANTINED -> next == PaidCommandRevivalRecord.State.REFUND_REQUIRED
                    || next == PaidCommandRevivalRecord.State.SUCCEEDED;
            default -> false;
        };
        if (!allowed) throw new IllegalArgumentException("invalid paid revival transition: " + expected + " -> " + next);
    }

    private static boolean terminal(PaidCommandRevivalRecord.State state) {
        return state == PaidCommandRevivalRecord.State.SUCCEEDED
                || state == PaidCommandRevivalRecord.State.CANCELED
                || state == PaidCommandRevivalRecord.State.REFUNDED;
    }

    private static void setNullable(PreparedStatement statement, int index, String value) throws Exception {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }

    private static Long nullableLong(ResultSet result, String column) throws Exception {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    public enum Status { APPLIED, IDEMPOTENT, CONFLICT, NOT_FOUND }
    public enum RefundDeliveryStatus { STARTED, PENDING, DELIVERING, DELIVERED, QUARANTINED, MISSING }

    public record MutationResult(@Nonnull Status status,
                                 @Nullable PaidCommandRevivalRecord operation,
                                 @Nullable String reason) {
    }
}
