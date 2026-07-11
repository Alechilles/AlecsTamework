package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;

/** Strict row mapper that converts corrupt managed-resident data into typed SQL failures. */
final class ManagedCoopResidentRowMapper {
    private ManagedCoopResidentRowMapper() {
    }

    static ResidentRecord read(ResultSet resultSet) throws SQLException {
        try {
            int activeValue = resultSet.getInt("active");
            if (activeValue != 0 && activeValue != 1) {
                throw new IllegalArgumentException("invalid active value: " + activeValue);
            }
            int residentSlot = resultSet.getInt("resident_slot");
            int snapshotVersion = resultSet.getInt("snapshot_version");
            long generation = resultSet.getLong("generation");
            if (residentSlot < 0 || snapshotVersion < 1 || generation < 0L) {
                throw new IllegalArgumentException("invalid resident slot, snapshot version, or generation");
            }
            ManagedCoopAuthorityKey key = new ManagedCoopAuthorityKey(
                    required(resultSet, "world_name"), resultSet.getInt("x"),
                    resultSet.getInt("y"), resultSet.getInt("z"));
            return new ResidentRecord(
                    required(resultSet, "resident_id"), key, required(resultSet, "coop_id"),
                    residentSlot, required(resultSet, "profile_id"),
                    resultSet.getString("role_id"), UUID.fromString(required(resultSet, "resident_uuid")),
                    parseUuid(resultSet.getString("source_npc_uuid")),
                    parseUuid(resultSet.getString("deployed_npc_uuid")),
                    resultSet.getString("snapshot_json"), resultSet.getString("snapshot_hash"),
                    snapshotVersion,
                    ResidentState.valueOf(required(resultSet, "state")),
                    generation, activeValue == 1,
                    resultSet.getLong("captured_at_ms"), resultSet.getLong("released_at_ms"),
                    resultSet.getLong("created_at_ms"), resultSet.getLong("updated_at_ms"));
        } catch (IllegalArgumentException exception) {
            throw new SQLException("invalid_managed_coop_resident_row", exception);
        }
    }

    private static String required(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        if (value == null || value.isBlank()) {
            throw new SQLException("missing_managed_resident_column:" + column);
        }
        return value;
    }

    @Nullable
    private static UUID parseUuid(@Nullable String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
