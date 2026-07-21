package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Encapsulates population-group row mapping and mechanical SQL writes. */
final class PopulationGroupSqlStore {
    static final String CLASSIFICATION_COLUMNS = """
            SELECT profile_id, role_id, group_ids_json, classification_revision,
                   status, source, created_at_ms, updated_at_ms
            FROM companion_population_group_classifications
            """;

    static final String OPERATION_COLUMNS = """
            SELECT operation_id, population_operation_id, profile_id, operation_type, state,
                   expected_population_revision, classification_revision,
                   old_owner_uuid, new_owner_uuid, old_role_id, new_role_id,
                   old_group_ids_json, new_group_ids_json,
                   old_lifecycle_state, new_lifecycle_state,
                   old_ownership_world_name, new_ownership_world_name,
                   reason_code, recovery_status, created_at_ms, updated_at_ms, completed_at_ms
            FROM companion_population_group_operations
            """;

    private PopulationGroupSqlStore() {
    }

    static void upsertClassification(Connection connection,
                                     PopulationGroupClassificationRecord record) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_population_group_classifications (
                    profile_id, role_id, group_ids_json, classification_revision,
                    status, source, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(profile_id) DO UPDATE SET
                    role_id = excluded.role_id,
                    group_ids_json = excluded.group_ids_json,
                    classification_revision = excluded.classification_revision,
                    status = excluded.status,
                    source = excluded.source,
                    updated_at_ms = excluded.updated_at_ms
                """)) {
            statement.setString(1, record.profileId());
            setText(statement, 2, record.roleId());
            statement.setString(3, PopulationGroupJsonCodec.encode(record.groupIds()));
            statement.setLong(4, record.classificationRevision());
            statement.setString(5, record.status().name());
            statement.setString(6, record.source());
            statement.setLong(7, record.createdAtMs());
            statement.setLong(8, record.updatedAtMs());
            statement.executeUpdate();
        }
    }

    static void replaceAssignments(Connection connection,
                                   PopulationGroupClassificationRecord record) throws Exception {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM companion_population_group_assignments WHERE profile_id = ?")) {
            delete.setString(1, record.profileId());
            delete.executeUpdate();
        }
        if (record.roleId() == null) return;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO companion_population_group_assignments (
                    profile_id, group_id, role_id, classification_revision, created_at_ms
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            for (String groupId : record.groupIds()) {
                insert.setString(1, record.profileId());
                insert.setString(2, groupId);
                insert.setString(3, record.roleId());
                insert.setLong(4, record.classificationRevision());
                insert.setLong(5, record.updatedAtMs());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    static void insertOperation(Connection connection,
                                PopulationGroupOperationRecord record) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_population_group_operations (
                    operation_id, population_operation_id, profile_id, operation_type, state,
                    expected_population_revision, classification_revision,
                    old_owner_uuid, new_owner_uuid, old_role_id, new_role_id,
                    old_group_ids_json, new_group_ids_json,
                    old_lifecycle_state, new_lifecycle_state,
                    old_ownership_world_name, new_ownership_world_name,
                    reason_code, recovery_status, created_at_ms, updated_at_ms, completed_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int index = 1;
            statement.setString(index++, record.operationId());
            setText(statement, index++, record.populationOperationId());
            statement.setString(index++, record.profileId());
            statement.setString(index++, record.operationType());
            statement.setString(index++, record.state().name());
            statement.setLong(index++, record.expectedPopulationRevision());
            statement.setLong(index++, record.classificationRevision());
            setUuid(statement, index++, record.oldOwnerUuid());
            setUuid(statement, index++, record.newOwnerUuid());
            setText(statement, index++, record.oldRoleId());
            setText(statement, index++, record.newRoleId());
            statement.setString(index++, PopulationGroupJsonCodec.encode(record.oldGroupIds()));
            statement.setString(index++, PopulationGroupJsonCodec.encode(record.newGroupIds()));
            setText(statement, index++, record.oldLifecycleState());
            setText(statement, index++, record.newLifecycleState());
            setText(statement, index++, record.oldOwnershipWorldName());
            setText(statement, index++, record.newOwnershipWorldName());
            setText(statement, index++, record.reasonCode());
            statement.setString(index++, record.recoveryStatus());
            statement.setLong(index++, record.createdAtMs());
            statement.setLong(index++, record.updatedAtMs());
            statement.setLong(index, record.completedAtMs());
            statement.executeUpdate();
        }
    }

    static void insertEvidence(Connection connection,
                               PopulationGroupCountEvidenceRecord record) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_population_group_count_evidence (
                    operation_id, owner_uuid, group_id, scope_kind, scope_world_name,
                    committed_owned_before, committed_active_before,
                    pending_owned_before, pending_active_before, owned_delta, active_delta,
                    max_owned, max_active, policy_revision, state, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, record.operationId());
            statement.setString(2, record.ownerUuid().toString());
            statement.setString(3, record.groupId());
            statement.setString(4, record.scopeKind().name());
            statement.setString(5, record.scopeWorldName() == null ? "" : record.scopeWorldName());
            statement.setInt(6, record.committedOwnedBefore());
            statement.setInt(7, record.committedActiveBefore());
            statement.setInt(8, record.pendingOwnedBefore());
            statement.setInt(9, record.pendingActiveBefore());
            statement.setInt(10, record.ownedDelta());
            statement.setInt(11, record.activeDelta());
            statement.setInt(12, record.maxOwned());
            statement.setInt(13, record.maxActive());
            statement.setLong(14, record.policyRevision());
            statement.setString(15, record.state().name());
            statement.setLong(16, record.createdAtMs());
            statement.setLong(17, record.updatedAtMs());
            statement.executeUpdate();
        }
    }

    static void updateOperation(Connection connection, String operationId,
                                PopulationGroupOperationRecord.State expected,
                                PopulationGroupOperationRecord.State next,
                                @Nullable String reason, long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_population_group_operations
                SET state = ?, reason_code = COALESCE(?, reason_code), updated_at_ms = ?,
                    completed_at_ms = CASE WHEN ? IN ('COMMITTED', 'CANCELED', 'FAILED') THEN ? ELSE 0 END
                WHERE operation_id = ? AND state = ?
                """)) {
            statement.setString(1, next.name());
            setText(statement, 2, reason);
            statement.setLong(3, nowMs);
            statement.setString(4, next.name());
            statement.setLong(5, nowMs);
            statement.setString(6, operationId);
            statement.setString(7, expected.name());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Group operation changed during transition.");
            }
        }
    }

    static void updateEvidenceState(Connection connection, String operationId,
                                    PopulationGroupCountEvidenceRecord.State state,
                                    long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_population_group_count_evidence
                SET state = ?, updated_at_ms = ? WHERE operation_id = ?
                """)) {
            statement.setString(1, state.name());
            statement.setLong(2, nowMs);
            statement.setString(3, operationId);
            statement.executeUpdate();
        }
    }

    @Nullable
    static PopulationGroupClassificationRecord findClassification(
            Connection connection, String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                CLASSIFICATION_COLUMNS + " WHERE profile_id = ? LIMIT 1")) {
            statement.setString(1, profileId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readClassification(result) : null;
            }
        }
    }

    @Nullable
    static PopulationGroupOperationRecord findOperation(Connection connection,
                                                        String operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                OPERATION_COLUMNS + " WHERE operation_id = ? LIMIT 1")) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readOperation(result) : null;
            }
        }
    }

    @Nullable
    static PopulationGroupOperationRecord findActiveOperation(Connection connection,
                                                              String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                OPERATION_COLUMNS + " WHERE profile_id = ? AND state IN "
                        + "('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING', 'QUARANTINED') LIMIT 1")) {
            statement.setString(1, profileId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readOperation(result) : null;
            }
        }
    }

    @Nonnull
    static List<PopulationGroupCountEvidenceRecord> loadCountEvidence(
            Connection connection, String operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, owner_uuid, group_id, scope_kind, scope_world_name,
                       committed_owned_before, committed_active_before,
                       pending_owned_before, pending_active_before, owned_delta, active_delta,
                       max_owned, max_active, policy_revision, state, created_at_ms, updated_at_ms
                FROM companion_population_group_count_evidence
                WHERE operation_id = ? ORDER BY owner_uuid, group_id, scope_kind, scope_world_name
                """)) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                List<PopulationGroupCountEvidenceRecord> rows = new ArrayList<>();
                while (result.next()) rows.add(readEvidence(result));
                return List.copyOf(rows);
            }
        }
    }

    @Nonnull
    static PopulationGroupClassificationRecord readClassification(ResultSet result) throws Exception {
        return new PopulationGroupClassificationRecord(
                result.getString("profile_id"), result.getString("role_id"),
                PopulationGroupJsonCodec.decode(result.getString("group_ids_json")),
                result.getLong("classification_revision"),
                PopulationGroupClassificationRecord.Status.valueOf(result.getString("status")),
                result.getString("source"), result.getLong("created_at_ms"),
                result.getLong("updated_at_ms"));
    }

    @Nonnull
    static PopulationGroupOperationRecord readOperation(ResultSet result) throws Exception {
        return new PopulationGroupOperationRecord(
                result.getString("operation_id"), result.getString("population_operation_id"),
                result.getString("profile_id"), result.getString("operation_type"),
                PopulationGroupOperationRecord.State.valueOf(result.getString("state")),
                result.getLong("expected_population_revision"),
                result.getLong("classification_revision"),
                parseUuid(result.getString("old_owner_uuid")), parseUuid(result.getString("new_owner_uuid")),
                result.getString("old_role_id"), result.getString("new_role_id"),
                PopulationGroupJsonCodec.decode(result.getString("old_group_ids_json")),
                PopulationGroupJsonCodec.decode(result.getString("new_group_ids_json")),
                result.getString("old_lifecycle_state"), result.getString("new_lifecycle_state"),
                result.getString("old_ownership_world_name"), result.getString("new_ownership_world_name"),
                result.getString("reason_code"), result.getString("recovery_status"),
                result.getLong("created_at_ms"), result.getLong("updated_at_ms"),
                result.getLong("completed_at_ms"));
    }

    @Nonnull
    static PopulationGroupCountEvidenceRecord readEvidence(ResultSet result) throws Exception {
        PopulationGroupCountEvidenceRecord.ScopeKind scope =
                PopulationGroupCountEvidenceRecord.ScopeKind.valueOf(result.getString("scope_kind"));
        return new PopulationGroupCountEvidenceRecord(
                result.getString("operation_id"), UUID.fromString(result.getString("owner_uuid")),
                result.getString("group_id"), scope,
                scope == PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL
                        ? null : result.getString("scope_world_name"),
                result.getInt("committed_owned_before"), result.getInt("committed_active_before"),
                result.getInt("pending_owned_before"), result.getInt("pending_active_before"),
                result.getInt("owned_delta"), result.getInt("active_delta"),
                result.getInt("max_owned"), result.getInt("max_active"),
                result.getLong("policy_revision"),
                PopulationGroupCountEvidenceRecord.State.valueOf(result.getString("state")),
                result.getLong("created_at_ms"), result.getLong("updated_at_ms"));
    }

    static void setText(PreparedStatement statement, int index, @Nullable String value)
            throws Exception {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setUuid(PreparedStatement statement, int index, @Nullable UUID value)
            throws Exception {
        setText(statement, index, value == null ? null : value.toString());
    }

    @Nullable
    private static UUID parseUuid(@Nullable String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
