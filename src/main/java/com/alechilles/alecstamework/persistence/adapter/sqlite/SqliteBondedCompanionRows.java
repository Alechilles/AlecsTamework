package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/** Centralizes JDBC binding and parsing for immutable bonded adapter rows. */
final class SqliteBondedCompanionRows {
    private SqliteBondedCompanionRows() {
    }

    static void bindProfile(PreparedStatement statement,
                            SqliteBondedCompanionProfileRow row)
            throws SQLException {
        statement.setString(1, row.profileId());
        statement.setString(2, row.ownerUuid().toString());
        statement.setString(3, row.rosterId());
        statement.setString(4, row.familyId());
        statement.setString(5, row.roleId());
        statement.setString(6, row.state().name());
        statement.setLong(7, row.revision());
        statement.setString(8, row.snapshotJson());
        statement.setLong(9, row.createdAtMs());
        statement.setLong(10, row.updatedAtMs());
        statement.setString(11, row.policyJson());
        statement.setString(12, row.displayName());
        statement.setString(13, row.species());
        statement.setString(14, row.gender());
        setNullableLong(statement, 15, row.diedAtMs());
        statement.setLong(16, row.summonCooldownUntilMs());
        statement.setLong(17, row.reviveCount());
        statement.setString(18, row.quarantineReason());
        setNullableLong(statement, 19, row.quarantinedAtMs());
    }

    static void bindCleanup(PreparedStatement statement,
                            SqliteBondedCompanionCleanupRow row)
            throws SQLException {
        statement.setString(1, row.cleanupId());
        statement.setString(2, row.ownerUuid().toString());
        statement.setString(3, row.rosterId());
        statement.setString(4, row.profileId());
        statement.setString(5, row.leaseToken());
        statement.setString(6, row.targetKind());
        statement.setString(7, row.targetNpcUuid().toString());
        statement.setString(8, row.cleanupReason());
        statement.setString(9, row.worldKey());
        statement.setString(10, row.cleanupState());
        statement.setInt(11, row.attemptCount());
        statement.setLong(12, row.nextAttemptAtMs());
        statement.setLong(13, row.createdAtMs());
        statement.setLong(14, row.retainedUntilMs());
    }

    static void bindExtension(PreparedStatement statement,
                              SqliteBondedCompanionExtensionDataRow row)
            throws SQLException {
        statement.setString(1, row.profileId());
        statement.setString(2, row.namespace());
        statement.setString(3, row.jsonPayload());
        statement.setLong(4, row.revision());
        statement.setLong(5, row.updatedAtMs());
    }

    static SqliteBondedCompanionProfileRow readProfile(ResultSet row)
            throws SQLException {
        return new SqliteBondedCompanionProfileRow(
                row.getString("profile_id"),
                UUID.fromString(row.getString("owner_uuid")),
                row.getString("roster_id"), row.getString("family_id"),
                row.getString("role_id"),
                BondedCompanionState.valueOf(row.getString("state")),
                row.getLong("revision"), row.getString("snapshot_json"),
                row.getLong("created_at_ms"), row.getLong("updated_at_ms"),
                row.getString("policy_json"), row.getString("display_name"),
                row.getString("species"), row.getString("gender"),
                nullableLong(row, "died_at_ms"),
                row.getLong("summon_cooldown_until_ms"),
                row.getLong("revive_count"), row.getString("quarantine_reason"),
                nullableLong(row, "quarantined_at_ms")
        );
    }

    static SqliteBondedCompanionLeaseRow readLease(ResultSet row)
            throws SQLException {
        return new SqliteBondedCompanionLeaseRow(
                row.getString("profile_id"), row.getString("lease_token"),
                UUID.fromString(row.getString("live_npc_uuid")),
                row.getString("world_key"), row.getLong("started_at_ms"),
                row.getLong("expires_at_ms"), row.getString("projection_state")
        );
    }

    static SqliteBondedCompanionExtensionDataRow readExtension(ResultSet row)
            throws SQLException {
        return new SqliteBondedCompanionExtensionDataRow(
                row.getString("profile_id"), row.getString("namespace"),
                row.getString("json_payload"), row.getLong("revision"),
                row.getLong("updated_at_ms")
        );
    }

    static SqliteBondedCompanionCleanupRow readCleanup(ResultSet row)
            throws SQLException {
        return new SqliteBondedCompanionCleanupRow(
                row.getString("cleanup_id"),
                UUID.fromString(row.getString("owner_uuid")),
                row.getString("roster_id"), row.getString("profile_id"),
                row.getString("lease_token"), row.getString("target_kind"),
                UUID.fromString(row.getString("target_npc_uuid")),
                row.getString("world_key"), row.getString("cleanup_reason"),
                row.getString("cleanup_state"),
                row.getInt("attempt_count"), row.getLong("next_attempt_at_ms"),
                row.getLong("created_at_ms"), row.getLong("retained_until_ms")
        );
    }

    static SqliteBondedCompanionOperationRow readOperation(ResultSet row)
            throws SQLException {
        return new SqliteBondedCompanionOperationRow(
                row.getString("caller_namespace"), row.getString("idempotency_key"),
                UUID.fromString(row.getString("owner_uuid")),
                row.getString("roster_id"), row.getString("profile_id"),
                row.getString("operation_type"), row.getString("request_hash"),
                row.getString("operation_state"), row.getString("result_json"),
                row.getLong("created_at_ms"), row.getLong("updated_at_ms"),
                row.getLong("expires_at_ms")
        );
    }

    private static Long nullableLong(ResultSet row, String column)
            throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement,
                                        int index,
                                        Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
