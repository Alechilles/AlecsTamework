package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.ClaimResult;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.ClaimStatus;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryClaim;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryOperation;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryState;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.TransitionResult;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.TransitionStatus;

/** Executes recovery operation mutations within the persistence write queue transaction. */
final class NpcRecoveryOperationTransactions {
    private static final String SELECT_COLUMNS = """
            operation_id, profile_id, source_npc_uuid, planned_target_uuid, actual_target_uuid,
            state, active, generation, attempt_count, created_at_ms, updated_at_ms,
            completed_at_ms, last_error
            """;

    private final LongSupplier clock;

    NpcRecoveryOperationTransactions(@Nonnull LongSupplier clock) {
        this.clock = clock;
    }

    @Nonnull
    ClaimResult claim(@Nonnull Connection connection, @Nonnull RecoveryClaim claim) throws Exception {
        RecoveryOperation sameId = findByOperationId(connection, claim.operationId());
        if (sameId != null) {
            return sameId.matchesClaim(claim)
                    ? ClaimResult.replayed(sameId)
                    : ClaimResult.conflict(ClaimStatus.OPERATION_CONFLICT, sameId);
        }
        if (!profileExists(connection, claim.profileId())) {
            return ClaimResult.conflict(ClaimStatus.PROFILE_NOT_FOUND, null);
        }
        RecoveryOperation active = findActiveByProfile(connection, claim.profileId(), true);
        if (active != null) {
            return ClaimResult.conflict(ClaimStatus.PROFILE_CONFLICT, active);
        }
        RecoveryOperation targetConflict = findTargetConflict(
                connection,
                claim.operationId(),
                claim.plannedTargetUuid()
        );
        if (targetConflict != null) {
            return ClaimResult.conflict(ClaimStatus.TARGET_CONFLICT, targetConflict);
        }

        long nowMs = clock.getAsLong();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_recovery_operations (
                    operation_id, profile_id, source_npc_uuid, planned_target_uuid,
                    actual_target_uuid, state, active, generation, attempt_count,
                    created_at_ms, updated_at_ms, completed_at_ms, last_error
                ) VALUES (?, ?, ?, ?, NULL, 'SPAWN_CLAIMED', 1, 0, 1, ?, ?, 0, NULL)
                """)) {
            statement.setString(1, claim.operationId());
            statement.setString(2, claim.profileId());
            bindUuid(statement, 3, claim.sourceNpcUuid());
            bindUuid(statement, 4, claim.plannedTargetUuid());
            statement.setLong(5, nowMs);
            statement.setLong(6, nowMs);
            statement.executeUpdate();
        }
        return ClaimResult.claimed(requireOperation(connection, claim.operationId()));
    }

    @Nonnull
    TransitionResult recordProjection(@Nonnull Connection connection,
                                      @Nonnull String operationId,
                                      @Nonnull String profileId,
                                      @Nonnull UUID actualTargetUuid,
                                      long expectedGeneration) throws Exception {
        RecoveryOperation current = findByOperationId(connection, operationId);
        TransitionResult precondition = projectionPrecondition(
                current,
                profileId,
                actualTargetUuid,
                expectedGeneration
        );
        if (precondition != null) {
            return precondition;
        }
        RecoveryOperation targetConflict = findTargetConflict(connection, operationId, actualTargetUuid);
        if (targetConflict != null) {
            return TransitionResult.conflict(TransitionStatus.TARGET_CONFLICT, targetConflict);
        }
        int updated = updateProjection(
                connection,
                operationId,
                profileId,
                actualTargetUuid,
                expectedGeneration,
                clock.getAsLong()
        );
        return updated == 1
                ? TransitionResult.applied(requireOperation(connection, operationId))
                : concurrentTransitionResult(connection, operationId, profileId, expectedGeneration);
    }

    @Nullable
    private TransitionResult projectionPrecondition(@Nullable RecoveryOperation current,
                                                    @Nonnull String profileId,
                                                    @Nonnull UUID actualTargetUuid,
                                                    long expectedGeneration) {
        if (current == null) {
            return TransitionResult.notFound();
        }
        if (!current.profileId().equals(profileId)) {
            return TransitionResult.conflict(TransitionStatus.PROFILE_CONFLICT, current);
        }
        if ((current.state() == RecoveryState.PROJECTION_CREATED
                || current.state() == RecoveryState.FINALIZED)
                && actualTargetUuid.equals(current.actualTargetUuid())) {
            return TransitionResult.replayed(current);
        }
        if (!current.active() || current.state() != RecoveryState.SPAWN_CLAIMED) {
            return TransitionResult.conflict(TransitionStatus.STATE_CONFLICT, current);
        }
        if (current.generation() != expectedGeneration) {
            return TransitionResult.conflict(TransitionStatus.GENERATION_CONFLICT, current);
        }
        return null;
    }

    private int updateProjection(@Nonnull Connection connection,
                                 @Nonnull String operationId,
                                 @Nonnull String profileId,
                                 @Nonnull UUID actualTargetUuid,
                                 long expectedGeneration,
                                 long nowMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE npc_recovery_operations
                SET actual_target_uuid = ?, state = 'PROJECTION_CREATED',
                    generation = generation + 1, updated_at_ms = ?
                WHERE operation_id = ? AND profile_id = ? AND state = 'SPAWN_CLAIMED'
                  AND active = 1 AND generation = ?
                """)) {
            bindUuid(statement, 1, actualTargetUuid);
            statement.setLong(2, nowMs);
            statement.setString(3, operationId);
            statement.setString(4, profileId);
            statement.setLong(5, expectedGeneration);
            return statement.executeUpdate();
        }
    }

    @Nonnull
    TransitionResult finalizeOperation(@Nonnull Connection connection,
                                       @Nonnull String operationId,
                                       @Nonnull String profileId,
                                       long expectedGeneration) throws Exception {
        RecoveryOperation current = findByOperationId(connection, operationId);
        TransitionResult precondition = terminalPrecondition(
                current,
                profileId,
                expectedGeneration,
                RecoveryState.FINALIZED,
                true
        );
        if (precondition != null) {
            return precondition;
        }
        int updated = updateTerminal(
                connection,
                operationId,
                profileId,
                RecoveryState.PROJECTION_CREATED,
                RecoveryState.FINALIZED,
                expectedGeneration,
                clock.getAsLong(),
                null
        );
        return updated == 1
                ? TransitionResult.applied(requireOperation(connection, operationId))
                : concurrentTransitionResult(connection, operationId, profileId, expectedGeneration);
    }

    @Nonnull
    TransitionResult terminate(@Nonnull Connection connection,
                               @Nonnull String operationId,
                               @Nonnull String profileId,
                               long expectedGeneration,
                               @Nonnull RecoveryState targetState,
                               @Nonnull String error) throws Exception {
        RecoveryOperation current = findByOperationId(connection, operationId);
        TransitionResult precondition = terminalPrecondition(
                current,
                profileId,
                expectedGeneration,
                targetState,
                false
        );
        if (precondition != null) {
            return precondition;
        }
        int updated = updateTerminal(
                connection,
                operationId,
                profileId,
                current.state(),
                targetState,
                expectedGeneration,
                clock.getAsLong(),
                error
        );
        return updated == 1
                ? TransitionResult.applied(requireOperation(connection, operationId))
                : concurrentTransitionResult(connection, operationId, profileId, expectedGeneration);
    }

    @Nullable
    private TransitionResult terminalPrecondition(@Nullable RecoveryOperation current,
                                                  @Nonnull String profileId,
                                                  long expectedGeneration,
                                                  @Nonnull RecoveryState targetState,
                                                  boolean requireProjection) {
        if (current == null) {
            return TransitionResult.notFound();
        }
        if (!current.profileId().equals(profileId)) {
            return TransitionResult.conflict(TransitionStatus.PROFILE_CONFLICT, current);
        }
        if (!current.active() && current.state() == targetState) {
            return TransitionResult.replayed(current);
        }
        if (!current.active()
                || current.state() == RecoveryState.FINALIZED
                || (requireProjection && current.state() != RecoveryState.PROJECTION_CREATED)) {
            return TransitionResult.conflict(TransitionStatus.STATE_CONFLICT, current);
        }
        if (current.generation() != expectedGeneration) {
            return TransitionResult.conflict(TransitionStatus.GENERATION_CONFLICT, current);
        }
        return null;
    }

    private int updateTerminal(@Nonnull Connection connection,
                               @Nonnull String operationId,
                               @Nonnull String profileId,
                               @Nonnull RecoveryState sourceState,
                               @Nonnull RecoveryState targetState,
                               long expectedGeneration,
                               long nowMs,
                               @Nullable String error) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE npc_recovery_operations
                SET state = ?, active = 0, generation = generation + 1,
                    updated_at_ms = ?, completed_at_ms = ?, last_error = ?
                WHERE operation_id = ? AND profile_id = ? AND state = ?
                  AND active = 1 AND generation = ?
                """)) {
            statement.setString(1, targetState.name());
            statement.setLong(2, nowMs);
            statement.setLong(3, nowMs);
            statement.setString(4, error);
            statement.setString(5, operationId);
            statement.setString(6, profileId);
            statement.setString(7, sourceState.name());
            statement.setLong(8, expectedGeneration);
            return statement.executeUpdate();
        }
    }

    @Nonnull
    private TransitionResult concurrentTransitionResult(@Nonnull Connection connection,
                                                        @Nonnull String operationId,
                                                        @Nonnull String profileId,
                                                        long expectedGeneration) throws SQLException {
        RecoveryOperation latest = findByOperationId(connection, operationId);
        if (latest == null) {
            return TransitionResult.notFound();
        }
        if (!latest.profileId().equals(profileId)) {
            return TransitionResult.conflict(TransitionStatus.PROFILE_CONFLICT, latest);
        }
        return TransitionResult.conflict(
                latest.generation() != expectedGeneration
                        ? TransitionStatus.GENERATION_CONFLICT
                        : TransitionStatus.STATE_CONFLICT,
                latest
        );
    }

    private boolean profileExists(@Nonnull Connection connection,
                                  @Nonnull String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM npc_profiles WHERE profile_id = ? LIMIT 1"
        )) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Nullable
    RecoveryOperation findActiveByProfile(@Nonnull Connection connection,
                                          @Nonnull String profileId,
                                          boolean verifyUnique) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + " FROM npc_recovery_operations "
                        + "WHERE profile_id = ? AND active = 1 ORDER BY created_at_ms, operation_id LIMIT 2"
        )) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                RecoveryOperation operation = readOperation(resultSet);
                if (verifyUnique && resultSet.next()) {
                    throw new RepositoryIntegrityException("multiple_active_recoveries_for_profile:" + profileId);
                }
                return operation;
            }
        }
    }

    @Nullable
    private RecoveryOperation findTargetConflict(@Nonnull Connection connection,
                                                 @Nonnull String operationId,
                                                 @Nonnull UUID targetUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + " FROM npc_recovery_operations "
                        + "WHERE operation_id <> ? AND (planned_target_uuid = ? OR actual_target_uuid = ?) "
                        + "ORDER BY active DESC, created_at_ms, operation_id LIMIT 1"
        )) {
            statement.setString(1, operationId);
            bindUuid(statement, 2, targetUuid);
            bindUuid(statement, 3, targetUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readOperation(resultSet) : null;
            }
        }
    }

    @Nullable
    RecoveryOperation findByOperationId(@Nonnull Connection connection,
                                        @Nonnull String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + " FROM npc_recovery_operations WHERE operation_id = ? LIMIT 1"
        )) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readOperation(resultSet) : null;
            }
        }
    }

    @Nonnull
    private RecoveryOperation requireOperation(@Nonnull Connection connection,
                                               @Nonnull String operationId) throws SQLException {
        RecoveryOperation operation = findByOperationId(connection, operationId);
        if (operation == null) {
            throw new RepositoryIntegrityException("recovery_operation_missing_after_write:" + operationId);
        }
        return operation;
    }

    @Nonnull
    private RecoveryOperation readOperation(@Nonnull ResultSet resultSet) throws SQLException {
        int activeValue = resultSet.getInt("active");
        if (activeValue != 0 && activeValue != 1) {
            throw new RepositoryIntegrityException("invalid_recovery_active_value:" + activeValue);
        }
        return new RecoveryOperation(
                resultSet.getString("operation_id"),
                resultSet.getString("profile_id"),
                parseUuid(resultSet.getString("source_npc_uuid")),
                parseUuid(resultSet.getString("planned_target_uuid")),
                parseUuid(resultSet.getString("actual_target_uuid")),
                parseState(resultSet.getString("state")),
                activeValue == 1,
                resultSet.getLong("generation"),
                resultSet.getInt("attempt_count"),
                resultSet.getLong("created_at_ms"),
                resultSet.getLong("updated_at_ms"),
                resultSet.getLong("completed_at_ms"),
                resultSet.getString("last_error")
        );
    }

    @Nonnull
    private RecoveryState parseState(@Nullable String value) {
        try {
            return RecoveryState.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            throw new RepositoryIntegrityException("unknown_recovery_state:" + value, exception);
        }
    }

    @Nullable
    private UUID parseUuid(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new RepositoryIntegrityException("invalid_recovery_uuid:" + value, exception);
        }
    }

    private static void bindUuid(@Nonnull PreparedStatement statement,
                                 int index,
                                 @Nullable UUID uuid) throws SQLException {
        statement.setString(index, uuid == null ? null : uuid.toString());
    }

    static final class RepositoryIntegrityException extends RuntimeException {
        private RepositoryIntegrityException(@Nonnull String message) {
            super(message);
        }

        private RepositoryIntegrityException(@Nonnull String message, @Nonnull Throwable cause) {
            super(message, cause);
        }
    }
}
