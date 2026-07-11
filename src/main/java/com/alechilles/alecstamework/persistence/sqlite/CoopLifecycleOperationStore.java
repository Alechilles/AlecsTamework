package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.CaptureRequest;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.ReleaseRequest;

/** SQL storage and strict row mapping for managed-coop lifecycle operations. */
final class CoopLifecycleOperationStore {
    private static final String COLUMNS = """
            operation_id, operation_kind, profile_id, world_name, coop_id, x, y, z,
            resident_slot, source_npc_uuid, planned_target_uuid, actual_target_uuid,
            state, snapshot_hash, expected_generation, generation, retry_count, active,
            created_at_ms, updated_at_ms, completed_at_ms, last_error
            """;

    @Nullable
    OperationRecord load(Connection connection, String operationId) throws SQLException {
        return loadOne(connection,
                "SELECT " + COLUMNS + " FROM coop_lifecycle_operations WHERE operation_id = ? LIMIT 1",
                statement -> statement.setString(1, operationId));
    }

    @Nullable
    OperationRecord loadActiveForProfile(Connection connection, String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM coop_lifecycle_operations "
                        + "WHERE profile_id = ? AND active = 1 ORDER BY created_at_ms, operation_id LIMIT 2")) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                OperationRecord operation = read(resultSet);
                if (resultSet.next()) {
                    throw integrity("multiple_active_coop_operations_for_profile:" + profileId, null);
                }
                return operation;
            }
        }
    }

    @Nullable
    OperationRecord findActiveConflict(Connection connection,
                                       String operationId,
                                       String profileId,
                                       ManagedCoopAuthorityKey key,
                                       int residentSlot) throws SQLException {
        return loadOne(connection, "SELECT " + COLUMNS + " FROM coop_lifecycle_operations "
                        + "WHERE active = 1 AND operation_id <> ? AND "
                        + "(profile_id = ? OR (authority_id = ? AND resident_slot = ?)) "
                        + "ORDER BY created_at_ms, operation_id LIMIT 1",
                statement -> {
                    statement.setString(1, operationId);
                    statement.setString(2, profileId);
                    statement.setString(3, key.authorityId());
                    statement.setInt(4, residentSlot);
                });
    }

    @Nullable
    OperationRecord findTargetConflict(Connection connection,
                                       String operationId,
                                       UUID targetUuid) throws SQLException {
        return loadOne(connection, "SELECT " + COLUMNS + " FROM coop_lifecycle_operations "
                        + "WHERE operation_id <> ? AND (planned_target_uuid = ? OR actual_target_uuid = ?) "
                        + "ORDER BY active DESC, created_at_ms, operation_id LIMIT 1",
                statement -> {
                    statement.setString(1, operationId);
                    statement.setString(2, targetUuid.toString());
                    statement.setString(3, targetUuid.toString());
                });
    }

    boolean hasRecoveryTargetConflict(Connection connection, UUID targetUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM npc_recovery_operations
                WHERE planned_target_uuid = ? OR actual_target_uuid = ? LIMIT 1
                """)) {
            statement.setString(1, targetUuid.toString());
            statement.setString(2, targetUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    boolean hasUuidClaimConflict(Connection connection,
                                 String residentId,
                                 UUID targetUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM managed_coop_uuid_claims
                WHERE npc_uuid = ? AND resident_id <> ? AND active = 1 LIMIT 1
                """)) {
            statement.setString(1, targetUuid.toString());
            statement.setString(2, residentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    boolean uuidMappedToDifferentProfile(Connection connection,
                                         UUID npcUuid,
                                         String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ?
                UNION
                SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ?
                """)) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, npcUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (!profileId.equals(resultSet.getString(1))) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    boolean uuidHasProfileMapping(Connection connection, UUID npcUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM npc_uuid_aliases WHERE npc_uuid = ?
                UNION ALL
                SELECT 1 FROM npc_profiles WHERE current_npc_uuid = ?
                LIMIT 1
                """)) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, npcUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    boolean authorityIsManaged(Connection connection,
                               ManagedCoopAuthorityKey key,
                               String coopId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM managed_coop_authority
                WHERE authority_id = ? AND world_name = ? AND x = ? AND y = ? AND z = ?
                  AND lower(coop_id) = ? AND authority_state = 'TWORK_MANAGED' AND active = 1
                LIMIT 1
                """)) {
            statement.setString(1, key.authorityId());
            statement.setString(2, key.worldName());
            statement.setInt(3, key.x());
            statement.setInt(4, key.y());
            statement.setInt(5, key.z());
            statement.setString(6, normalizeCoopId(coopId));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    boolean profileExists(Connection connection, String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM npc_profiles WHERE profile_id = ? LIMIT 1")) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    void insertCapture(Connection connection, CaptureRequest request) throws SQLException {
        insert(connection, OperationKind.CAPTURE, request.operationId(), request.profileId(),
                request.authorityKey(), request.coopId(), request.residentSlot(), request.sourceNpcUuid(),
                null, request.snapshotHash(), request.expectedResidentGeneration(), request.nowMs());
    }

    void insertRelease(Connection connection, ReleaseRequest request) throws SQLException {
        insert(connection, OperationKind.RELEASE, request.operationId(), request.profileId(),
                request.authorityKey(), request.coopId(), request.residentSlot(), null,
                request.plannedTargetUuid(), request.snapshotHash(),
                request.expectedResidentGeneration(), request.nowMs());
    }

    boolean advance(Connection connection,
                    String operationId,
                    OperationKind kind,
                    OperationState expected,
                    OperationState target,
                    long expectedGeneration,
                    boolean terminal,
                    long nowMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coop_lifecycle_operations
                SET state = ?, generation = generation + 1, active = ?, updated_at_ms = ?,
                    completed_at_ms = CASE WHEN ? = 1 THEN ? ELSE completed_at_ms END
                WHERE operation_id = ? AND operation_kind = ? AND state = ?
                  AND active = 1 AND generation = ?
                """)) {
            statement.setString(1, target.name());
            statement.setInt(2, terminal ? 0 : 1);
            statement.setLong(3, nowMs);
            statement.setInt(4, terminal ? 1 : 0);
            statement.setLong(5, nowMs);
            statement.setString(6, operationId);
            statement.setString(7, kind.name());
            statement.setString(8, expected.name());
            statement.setLong(9, expectedGeneration);
            return statement.executeUpdate() == 1;
        }
    }

    boolean markProjectionCreated(Connection connection,
                                  String operationId,
                                  long expectedGeneration,
                                  UUID actualTargetUuid,
                                  long nowMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coop_lifecycle_operations
                SET actual_target_uuid = ?, state = 'PROJECTION_CREATED',
                    generation = generation + 1, updated_at_ms = ?
                WHERE operation_id = ? AND operation_kind = 'RELEASE'
                  AND state = 'SPAWN_CLAIMED' AND active = 1 AND generation = ?
                """)) {
            statement.setString(1, actualTargetUuid.toString());
            statement.setLong(2, nowMs);
            statement.setString(3, operationId);
            statement.setLong(4, expectedGeneration);
            return statement.executeUpdate() == 1;
        }
    }

    private void insert(Connection connection,
                        OperationKind kind,
                        String operationId,
                        String profileId,
                        ManagedCoopAuthorityKey key,
                        String coopId,
                        int residentSlot,
                        @Nullable UUID sourceUuid,
                        @Nullable UUID plannedUuid,
                        @Nullable String snapshotHash,
                        long expectedResidentGeneration,
                        long nowMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_lifecycle_operations (
                    operation_id, operation_kind, profile_id, authority_id, world_name, coop_id,
                    x, y, z, resident_slot, source_npc_uuid, planned_target_uuid,
                    actual_target_uuid, state, snapshot_hash, expected_generation, retry_count,
                    generation, active, created_at_ms, updated_at_ms, completed_at_ms, last_error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'PREPARED', ?, ?, 0, 0, 1, ?, ?, 0, NULL)
                """)) {
            int index = 1;
            statement.setString(index++, operationId);
            statement.setString(index++, kind.name());
            statement.setString(index++, profileId);
            statement.setString(index++, key.authorityId());
            statement.setString(index++, key.worldName());
            statement.setString(index++, normalizeCoopId(coopId));
            statement.setInt(index++, key.x());
            statement.setInt(index++, key.y());
            statement.setInt(index++, key.z());
            statement.setInt(index++, residentSlot);
            statement.setString(index++, sourceUuid == null ? null : sourceUuid.toString());
            statement.setString(index++, plannedUuid == null ? null : plannedUuid.toString());
            statement.setString(index++, snapshotHash);
            statement.setLong(index++, expectedResidentGeneration);
            statement.setLong(index++, nowMs);
            statement.setLong(index, nowMs);
            statement.executeUpdate();
        }
    }

    @Nullable
    private OperationRecord loadOne(Connection connection,
                                    String sql,
                                    Binder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? read(resultSet) : null;
            }
        }
    }

    private OperationRecord read(ResultSet resultSet) throws SQLException {
        try {
            int residentSlot = resultSet.getInt("resident_slot");
            long expectedGeneration = resultSet.getLong("expected_generation");
            long generation = resultSet.getLong("generation");
            int retryCount = resultSet.getInt("retry_count");
            if (residentSlot < 0 || expectedGeneration < 0L || generation < 0L || retryCount < 0) {
                throw new IllegalArgumentException("negative lifecycle slot, generation, or retry count");
            }
            return new OperationRecord(
                    required(resultSet, "operation_id"),
                    OperationKind.valueOf(required(resultSet, "operation_kind")),
                    required(resultSet, "profile_id"),
                    new ManagedCoopAuthorityKey(required(resultSet, "world_name"),
                            resultSet.getInt("x"), resultSet.getInt("y"), resultSet.getInt("z")),
                    required(resultSet, "coop_id"), residentSlot,
                    parseUuid(resultSet.getString("source_npc_uuid")),
                    parseUuid(resultSet.getString("planned_target_uuid")),
                    parseUuid(resultSet.getString("actual_target_uuid")),
                    OperationState.valueOf(required(resultSet, "state")),
                    resultSet.getString("snapshot_hash"), expectedGeneration,
                    generation, retryCount,
                    active(resultSet), resultSet.getLong("created_at_ms"),
                    resultSet.getLong("updated_at_ms"), resultSet.getLong("completed_at_ms"),
                    resultSet.getString("last_error"));
        } catch (IllegalArgumentException exception) {
            throw integrity("invalid_coop_lifecycle_operation_row", exception);
        }
    }

    private boolean active(ResultSet resultSet) throws SQLException {
        int value = resultSet.getInt("active");
        if (value != 0 && value != 1) {
            throw integrity("invalid_coop_operation_active:" + value, null);
        }
        return value == 1;
    }

    private String required(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        if (value == null || value.isBlank()) {
            throw integrity("missing_coop_operation_column:" + column, null);
        }
        return value;
    }

    @Nullable
    private UUID parseUuid(@Nullable String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private String normalizeCoopId(String coopId) {
        if (coopId == null || coopId.isBlank()) {
            throw new IllegalArgumentException("coopId must not be blank");
        }
        return coopId.trim().toLowerCase(Locale.ROOT);
    }

    private SQLException integrity(String message, @Nullable Throwable cause) {
        return cause == null ? new SQLException(message) : new SQLException(message, cause);
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
