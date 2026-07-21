package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptSqlSupport.SELECT_COLUMNS;
import static com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptSqlSupport.read;
import static com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptSqlSupport.setDouble;
import static com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptSqlSupport.setLong;
import static com.alechilles.alecstamework.persistence.sqlite.CaptureAttemptSqlSupport.setText;

/** Persists capture attempts and atomically couples failed rolls to their actor/config cooldown. */
public final class CaptureAttemptRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final AtomicLong duplicateCallbacksSinceBoot = new AtomicLong();

    public CaptureAttemptRepository(@Nonnull SqliteConnectionManager connectionManager,
                                    @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<PrepareResult> prepareAsync(
            @Nonnull CaptureAttemptRecord attempt) {
        if (attempt.state() != CaptureAttemptRecord.State.PREPARED || attempt.resolution() != null) {
            throw new IllegalArgumentException("New capture attempts must be unresolved PREPARED records.");
        }
        return writeQueue.submitTracked(
                "capture_attempt_prepare",
                connection -> prepareInTransaction(connection, attempt),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> resolveAsync(
            @Nonnull ResolutionMutation mutation) {
        return writeQueue.submitTracked(
                "capture_attempt_resolve",
                connection -> resolveInTransaction(connection, mutation),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<MutationResult> advanceAsync(
            @Nonnull String attemptId,
            @Nonnull CaptureAttemptRecord.State expected,
            @Nonnull CaptureAttemptRecord.State next,
            @Nullable String reasonCode,
            @Nullable String lastError,
            long nowMs) {
        if (!expected.canTransitionTo(next)) {
            throw new IllegalArgumentException("Invalid capture transition: " + expected + " -> " + next);
        }
        return writeQueue.submitTracked(
                "capture_attempt_advance",
                connection -> advanceInTransaction(
                        connection, attemptId, expected, next, reasonCode, lastError, nowMs),
                null
        );
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Boolean> markEventEmittedAsync(
            @Nonnull String attemptId,
            long emittedAtMs) {
        return writeQueue.submitTracked(
                "capture_attempt_event_fence",
                connection -> markEventEmittedInTransaction(connection, attemptId, emittedAtMs),
                null
        );
    }

    /** Serializes the read behind prior attempt writes without blocking the world thread. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<FailureCooldown> findFailureCooldownAsync(
            @Nonnull UUID actorUuid,
            @Nonnull String spawnerConfigId) {
        return writeQueue.submitTracked(
                "capture_failure_cooldown_read",
                connection -> findFailureCooldownInTransaction(
                        connection, actorUuid, spawnerConfigId),
                null
        );
    }

    @Nullable
    public CaptureAttemptRecord find(@Nonnull String attemptId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findInTransaction(connection, attemptId);
        }
    }

    @Nullable
    public CaptureAttemptRecord findByCallerKey(@Nonnull String callerNamespace,
                                                @Nonnull String idempotencyKey) throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT_COLUMNS + " WHERE caller_namespace = ? AND idempotency_key = ? LIMIT 1")) {
            statement.setString(1, requireText(callerNamespace, "callerNamespace"));
            statement.setString(2, requireText(idempotencyKey, "idempotencyKey"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    @Nullable
    public FailureCooldown findFailureCooldown(@Nonnull UUID actorUuid,
                                               @Nonnull String spawnerConfigId) throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            return findFailureCooldownInTransaction(connection, actorUuid, spawnerConfigId);
        }
    }

    /** Bounded aggregate evidence for read-only operator diagnostics. */
    @Nonnull
    public DiagnosticsSummary summarizeDiagnostics() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT
                         SUM(CASE WHEN state = 'PREPARED' THEN 1 ELSE 0 END) AS prepared,
                         SUM(CASE WHEN state = 'RESOLVED_FAILURE' THEN 1 ELSE 0 END) AS resolved_failure,
                         SUM(CASE WHEN state = 'APPLYING' THEN 1 ELSE 0 END) AS applying,
                         SUM(CASE WHEN state = 'QUARANTINED' THEN 1 ELSE 0 END) AS quarantined,
                         SUM(CASE WHEN recovery_status NOT IN ('NONE', 'READY')
                                      OR reason_code LIKE 'capture-recovery-%'
                                      OR reason_code = 'capture-attempt-expired'
                                  THEN 1 ELSE 0 END) AS recovered
                     FROM capture_attempts
                     """)) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return new DiagnosticsSummary(0L, 0L, 0L, 0L, 0L,
                            duplicateCallbacksSinceBoot.get());
                }
                return new DiagnosticsSummary(
                        result.getLong("prepared"),
                        result.getLong("resolved_failure"),
                        result.getLong("applying"),
                        result.getLong("quarantined"),
                        result.getLong("recovered"),
                        duplicateCallbacksSinceBoot.get());
            }
        }
    }

    @Nullable
    private FailureCooldown findFailureCooldownInTransaction(
            @Nonnull Connection connection,
            @Nonnull UUID actorUuid,
            @Nonnull String spawnerConfigId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                     SELECT actor_uuid, spawner_config_id, attempt_id, cooldown_until_ms,
                            generation, updated_at_ms
                     FROM capture_failure_cooldowns
                     WHERE actor_uuid = ? AND spawner_config_id = ?
                     """)) {
            statement.setString(1, actorUuid.toString());
            statement.setString(2, requireText(spawnerConfigId, "spawnerConfigId"));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new FailureCooldown(
                        UUID.fromString(result.getString("actor_uuid")),
                        result.getString("spawner_config_id"),
                        result.getString("attempt_id"),
                        result.getLong("cooldown_until_ms"),
                        result.getLong("generation"),
                        result.getLong("updated_at_ms")
                );
            }
        }
    }

    @Nonnull
    public List<CaptureAttemptRecord> loadRecoverable() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT_COLUMNS + " WHERE state IN "
                             + "('PREPARED', 'RESOLVED_SUCCESS', 'APPLYING', 'COMPENSATING', 'QUARANTINED') "
                             + "ORDER BY created_at_ms, attempt_id");
             ResultSet result = statement.executeQuery()) {
            List<CaptureAttemptRecord> attempts = new ArrayList<>();
            while (result.next()) {
                attempts.add(read(result));
            }
            return List.copyOf(attempts);
        }
    }

    @Nonnull
    private PrepareResult prepareInTransaction(@Nonnull Connection connection,
                                               @Nonnull CaptureAttemptRecord attempt) throws Exception {
        CaptureAttemptRecord existing = findInTransaction(connection, attempt.identity().attemptId());
        if (existing != null) {
            if (samePreparation(existing, attempt)) {
                recordDuplicateCallback(existing);
                return new PrepareResult(PrepareStatus.IDEMPOTENT, existing, "attempt_exists");
            }
            return new PrepareResult(PrepareStatus.CONFLICT, existing, "attempt_id_in_use");
        }
        CaptureAttemptRecord byCaller = findByCallerKeyInTransaction(connection, attempt.identity());
        if (byCaller != null) {
            if (samePreparation(byCaller, attempt)) {
                recordDuplicateCallback(byCaller);
                return new PrepareResult(PrepareStatus.IDEMPOTENT, byCaller, "caller_key_exists");
            }
            return new PrepareResult(PrepareStatus.CONFLICT, byCaller, "caller_key_in_use");
        }
        insert(connection, attempt);
        return new PrepareResult(PrepareStatus.PREPARED, attempt, null);
    }

    @Nonnull
    private MutationResult resolveInTransaction(@Nonnull Connection connection,
                                                @Nonnull ResolutionMutation mutation) throws Exception {
        CaptureAttemptRecord existing = findInTransaction(connection, mutation.attemptId());
        if (existing == null) {
            return new MutationResult(MutationStatus.NOT_FOUND, null, "attempt_not_found");
        }
        CaptureAttemptRecord.State target = mutation.success()
                ? CaptureAttemptRecord.State.RESOLVED_SUCCESS
                : CaptureAttemptRecord.State.RESOLVED_FAILURE;
        if (existing.state() != CaptureAttemptRecord.State.PREPARED) {
            return existing.state() == target
                    ? new MutationResult(MutationStatus.IDEMPOTENT, existing, "already_resolved")
                    : new MutationResult(MutationStatus.INVALID_STATE, existing, "attempt_not_prepared");
        }
        validateResolution(existing, mutation);
        int changed;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE capture_attempts
                SET state = ?, population_operation_id = ?, capture_operation_id = ?,
                    power = ?, minimum_power = ?, current_health = ?, maximum_health = ?,
                    missing_health_fraction = ?, condition_bonus = ?, effective_chance = ?,
                    entropy_sample = ?, outcome = ?, reason_code = ?,
                    failure_cooldown_until_ms = ?, resolved_at_ms = ?, updated_at_ms = ?,
                    completed_at_ms = CASE WHEN ? = 'RESOLVED_FAILURE' THEN ? ELSE 0 END
                WHERE attempt_id = ? AND state = 'PREPARED'
                """)) {
            CaptureAttemptRecord.Resolution resolution = mutation.resolution();
            statement.setString(1, target.name());
            setText(statement, 2, mutation.populationOperationId());
            setText(statement, 3, mutation.captureOperationId());
            statement.setDouble(4, resolution.power());
            statement.setDouble(5, resolution.minimumPower());
            statement.setDouble(6, resolution.currentHealth());
            statement.setDouble(7, resolution.maximumHealth());
            statement.setDouble(8, resolution.missingHealthFraction());
            statement.setDouble(9, resolution.conditionBonus());
            statement.setDouble(10, resolution.effectiveChance());
            setDouble(statement, 11, resolution.entropySample());
            setText(statement, 12, mutation.success() ? null : "FAILED_ROLL");
            statement.setString(13, resolution.reasonCode());
            statement.setLong(14, mutation.success() ? 0L : resolution.failureCooldownUntilMs());
            statement.setLong(15, resolution.resolvedAtMs());
            statement.setLong(16, resolution.resolvedAtMs());
            statement.setString(17, target.name());
            statement.setLong(18, resolution.resolvedAtMs());
            statement.setString(19, mutation.attemptId());
            changed = statement.executeUpdate();
        }
        if (changed != 1) {
            return new MutationResult(MutationStatus.CONFLICT,
                    findInTransaction(connection, mutation.attemptId()), "attempt_changed");
        }
        if (!mutation.success() && mutation.resolution().failureCooldownUntilMs() != 0L) {
            upsertCooldown(connection, existing, mutation.resolution());
        }
        return new MutationResult(MutationStatus.APPLIED,
                findInTransaction(connection, mutation.attemptId()), null);
    }

    private void validateResolution(@Nonnull CaptureAttemptRecord existing,
                                    @Nonnull ResolutionMutation mutation) {
        CaptureAttemptRecord.Resolution resolution = mutation.resolution();
        if (existing.config().guaranteed() && resolution.entropySample() != null) {
            throw new IllegalArgumentException("Guaranteed attempts cannot consume entropy.");
        }
        boolean probabilisticBoundary = resolution.effectiveChance() > 0.0D
                && resolution.effectiveChance() < 1.0D;
        if (!existing.config().guaranteed() && probabilisticBoundary
                && resolution.entropySample() == null) {
            throw new IllegalArgumentException("Non-terminal probability requires one entropy sample.");
        }
        if (resolution.entropySample() != null && !probabilisticBoundary) {
            throw new IllegalArgumentException("Certain or impossible outcomes cannot consume entropy.");
        }
        if (mutation.success() && resolution.failureCooldownUntilMs() != 0L) {
            throw new IllegalArgumentException("Successful attempts cannot start a failure cooldown.");
        }
    }

    private void upsertCooldown(@Nonnull Connection connection,
                                @Nonnull CaptureAttemptRecord attempt,
                                @Nonnull CaptureAttemptRecord.Resolution resolution) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO capture_failure_cooldowns (
                    actor_uuid, spawner_config_id, attempt_id, cooldown_until_ms,
                    generation, updated_at_ms
                ) VALUES (?, ?, ?, ?, 1, ?)
                ON CONFLICT(actor_uuid, spawner_config_id) DO UPDATE SET
                    attempt_id = excluded.attempt_id,
                    cooldown_until_ms = excluded.cooldown_until_ms,
                    generation = capture_failure_cooldowns.generation + 1,
                    updated_at_ms = excluded.updated_at_ms
                """)) {
            statement.setString(1, attempt.identity().actorUuid().toString());
            statement.setString(2, attempt.config().spawnerConfigId());
            statement.setString(3, attempt.identity().attemptId());
            statement.setLong(4, resolution.failureCooldownUntilMs());
            statement.setLong(5, resolution.resolvedAtMs());
            statement.executeUpdate();
        }
    }

    @Nonnull
    private MutationResult advanceInTransaction(@Nonnull Connection connection,
                                                @Nonnull String attemptId,
                                                @Nonnull CaptureAttemptRecord.State expected,
                                                @Nonnull CaptureAttemptRecord.State next,
                                                @Nullable String reasonCode,
                                                @Nullable String lastError,
                                                long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE capture_attempts
                SET state = ?, outcome = CASE WHEN ? = 'COMMITTED' THEN 'CAPTURED' ELSE outcome END,
                    reason_code = COALESCE(?, reason_code), last_error = ?, updated_at_ms = ?,
                    completed_at_ms = CASE WHEN ? IN ('COMMITTED', 'CANCELED') THEN ? ELSE 0 END
                WHERE attempt_id = ? AND state = ?
                """)) {
            statement.setString(1, next.name());
            statement.setString(2, next.name());
            setText(statement, 3, reasonCode);
            setText(statement, 4, lastError);
            statement.setLong(5, nowMs);
            statement.setString(6, next.name());
            statement.setLong(7, nowMs);
            statement.setString(8, requireText(attemptId, "attemptId"));
            statement.setString(9, expected.name());
            if (statement.executeUpdate() == 1) {
                return new MutationResult(MutationStatus.APPLIED,
                        findInTransaction(connection, attemptId), null);
            }
        }
        CaptureAttemptRecord existing = findInTransaction(connection, attemptId);
        if (existing == null) {
            return new MutationResult(MutationStatus.NOT_FOUND, null, "attempt_not_found");
        }
        if (existing.state() == next) {
            return new MutationResult(MutationStatus.IDEMPOTENT, existing, "already_advanced");
        }
        return new MutationResult(MutationStatus.INVALID_STATE, existing, "attempt_state_changed");
    }

    private boolean markEventEmittedInTransaction(@Nonnull Connection connection,
                                                  @Nonnull String attemptId,
                                                  long emittedAtMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE capture_attempts SET event_emitted_at_ms = ?, updated_at_ms = ?
                WHERE attempt_id = ? AND event_emitted_at_ms = 0
                  AND state IN ('RESOLVED_FAILURE', 'COMMITTED')
                """)) {
            statement.setLong(1, emittedAtMs);
            statement.setLong(2, emittedAtMs);
            statement.setString(3, requireText(attemptId, "attemptId"));
            return statement.executeUpdate() == 1;
        }
    }

    private void insert(@Nonnull Connection connection,
                        @Nonnull CaptureAttemptRecord attempt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO capture_attempts (
                    attempt_id, caller_namespace, idempotency_key, actor_uuid, target_npc_uuid,
                    profile_id, expected_profile_revision, source_item_id, source_role_id,
                    source_context_json, spawner_config_id, spawner_config_revision,
                    target_policy_config_id, target_policy_config_revision, target_policy_bypassed,
                    state, guaranteed, recovery_status, expires_at_ms, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?, ?, ?, ?)
                """)) {
            CaptureAttemptRecord.Identity identity = attempt.identity();
            CaptureAttemptRecord.ConfigEvidence config = attempt.config();
            statement.setString(1, identity.attemptId());
            setText(statement, 2, identity.callerNamespace());
            setText(statement, 3, identity.idempotencyKey());
            statement.setString(4, identity.actorUuid().toString());
            statement.setString(5, identity.targetNpcUuid().toString());
            setText(statement, 6, identity.profileId());
            setLong(statement, 7, identity.expectedProfileRevision());
            statement.setString(8, identity.sourceItemId());
            setText(statement, 9, identity.sourceRoleId());
            statement.setString(10, identity.sourceContextJson());
            statement.setString(11, config.spawnerConfigId());
            statement.setLong(12, config.spawnerConfigRevision());
            setText(statement, 13, config.targetPolicyConfigId());
            setLong(statement, 14, config.targetPolicyConfigRevision());
            statement.setInt(15, config.targetPolicyBypassed() ? 1 : 0);
            statement.setInt(16, config.guaranteed() ? 1 : 0);
            statement.setString(17, attempt.recoveryStatus());
            statement.setLong(18, attempt.expiresAtMs());
            statement.setLong(19, attempt.createdAtMs());
            statement.setLong(20, attempt.updatedAtMs());
            statement.executeUpdate();
        }
    }

    @Nullable
    private CaptureAttemptRecord findInTransaction(@Nonnull Connection connection,
                                                   @Nonnull String attemptId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE attempt_id = ? LIMIT 1")) {
            statement.setString(1, requireText(attemptId, "attemptId"));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    @Nullable
    private CaptureAttemptRecord findByCallerKeyInTransaction(
            @Nonnull Connection connection,
            @Nonnull CaptureAttemptRecord.Identity identity) throws Exception {
        if (identity.callerNamespace() == null) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE caller_namespace = ? AND idempotency_key = ? LIMIT 1")) {
            statement.setString(1, identity.callerNamespace());
            statement.setString(2, identity.idempotencyKey());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    private boolean samePreparation(@Nonnull CaptureAttemptRecord existing,
                                    @Nonnull CaptureAttemptRecord requested) {
        return existing.identity().actorUuid().equals(requested.identity().actorUuid())
                && existing.identity().targetNpcUuid().equals(requested.identity().targetNpcUuid())
                && existing.identity().sourceItemId().equals(requested.identity().sourceItemId())
                && existing.config().equals(requested.config());
    }

    private void recordDuplicateCallback(@Nonnull CaptureAttemptRecord existing) {
        if (existing.state() != CaptureAttemptRecord.State.PREPARED) {
            duplicateCallbacksSinceBoot.incrementAndGet();
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

    public enum PrepareStatus {
        PREPARED,
        IDEMPOTENT,
        CONFLICT
    }

    public enum MutationStatus {
        APPLIED,
        IDEMPOTENT,
        NOT_FOUND,
        INVALID_STATE,
        CONFLICT
    }

    public record PrepareResult(@Nonnull PrepareStatus status,
                                @Nullable CaptureAttemptRecord attempt,
                                @Nullable String reason) {
    }

    public record MutationResult(@Nonnull MutationStatus status,
                                 @Nullable CaptureAttemptRecord attempt,
                                 @Nullable String reason) {
    }

    public record ResolutionMutation(@Nonnull String attemptId,
                                     boolean success,
                                     @Nonnull CaptureAttemptRecord.Resolution resolution,
                                     @Nullable String populationOperationId,
                                     @Nullable String captureOperationId) {
        public ResolutionMutation {
            attemptId = requireText(attemptId, "attemptId");
            resolution = Objects.requireNonNull(resolution, "resolution");
        }
    }

    public record FailureCooldown(@Nonnull UUID actorUuid,
                                  @Nonnull String spawnerConfigId,
                                  @Nonnull String attemptId,
                                  long cooldownUntilMs,
                                  long generation,
                                  long updatedAtMs) {
    }

    /** Aggregate counters; duplicate callbacks are intentionally scoped to the current boot. */
    public record DiagnosticsSummary(long prepared,
                                     long resolvedFailure,
                                     long applying,
                                     long quarantined,
                                     long recovered,
                                     long duplicateCallbacksSinceBoot) {
    }

}
