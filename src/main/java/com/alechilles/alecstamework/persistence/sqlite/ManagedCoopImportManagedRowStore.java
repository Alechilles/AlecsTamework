package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportDispositionWriter.ManagedRows;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionBinding;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.annotation.Nullable;

/** Creates and verifies the managed profile/resident/IMPORT-operation reference graph. */
final class ManagedCoopImportManagedRowStore {
    void write(Connection connection, ManagedRows rows, DispositionBinding binding)
            throws Exception {
        if (!rows.binding().equals(binding)) {
            return;
        }
        if (rows.createResident()) {
            if (!ensureProfile(connection, rows) || !ensureResident(connection, rows)) {
                return;
            }
        } else if (!residentMatches(connection, rows)) {
            return;
        }
        ensureOperation(connection, rows);
    }

    private boolean ensureProfile(Connection connection, ManagedRows rows) throws SQLException {
        String profileId = rows.binding().profileId();
        String uuid = rows.residentUuid().toString();
        String mappedProfile = profileForUuid(connection, uuid);
        if (mappedProfile != null && !mappedProfile.equals(profileId)) {
            return false;
        }
        ProfileRow existing = profile(connection, profileId);
        if (existing == null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO npc_profiles (
                        profile_id, current_npc_uuid, owner_uuid, display_name, role_id,
                        state_json, state_hash, last_world_name,
                        created_at_ms, updated_at_ms, last_active_at_ms
                    ) VALUES (?, ?, NULL, ?, ?, NULL, NULL, NULL, ?, ?, ?)
                    """)) {
                statement.setString(1, profileId);
                statement.setString(2, uuid);
                statement.setString(3, rows.source().displayName());
                statement.setString(4, rows.roleId());
                statement.setLong(5, rows.binding().boundAtMs());
                statement.setLong(6, rows.binding().boundAtMs());
                statement.setLong(7, rows.binding().boundAtMs());
                if (statement.executeUpdate() != 1) {
                    return false;
                }
            }
        } else if (existing.currentUuid() != null && !existing.currentUuid().equals(uuid)
                && mappedProfile == null) {
            return false;
        }
        return ensureAlias(connection, uuid, profileId, existing == null, rows.binding().boundAtMs());
    }

    private boolean ensureAlias(Connection connection,
                                String uuid,
                                String profileId,
                                boolean current,
                                long mappedAtMs) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ? LIMIT 2")) {
            query.setString(1, uuid);
            try (ResultSet resultSet = query.executeQuery()) {
                if (resultSet.next()) {
                    boolean same = profileId.equals(resultSet.getString(1));
                    return same && !resultSet.next();
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms)
                VALUES (?, ?, ?, ?)
                """)) {
            insert.setString(1, uuid);
            insert.setString(2, profileId);
            insert.setInt(3, current ? 1 : 0);
            insert.setLong(4, mappedAtMs);
            return insert.executeUpdate() == 1;
        }
    }

    private boolean ensureResident(Connection connection, ManagedRows rows) throws SQLException {
        if (residentExists(connection, rows.binding().residentId())) {
            return residentMatches(connection, rows);
        }
        if (activeResidentAssignmentConflict(connection, rows)) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_residents (
                    resident_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                    profile_id, role_id, resident_uuid, source_npc_uuid, deployed_npc_uuid,
                    snapshot_json, snapshot_hash, snapshot_version, state, generation, active,
                    captured_at_ms, released_at_ms, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 1, ?, 0, ?, ?)
                """)) {
            int index = 1;
            statement.setString(index++, rows.binding().residentId());
            statement.setString(index++, rows.authorityKey().authorityId());
            statement.setString(index++, rows.authorityKey().worldName());
            statement.setString(index++, rows.coopId());
            statement.setInt(index++, rows.authorityKey().x());
            statement.setInt(index++, rows.authorityKey().y());
            statement.setInt(index++, rows.authorityKey().z());
            statement.setInt(index++, rows.residentSlot());
            statement.setString(index++, rows.binding().profileId());
            statement.setString(index++, rows.roleId());
            statement.setString(index++, rows.residentUuid().toString());
            statement.setString(index++, uuid(rows.source().persistentUuid()));
            statement.setString(index++, rows.residentState() == ResidentState.DEPLOYED
                    ? uuid(rows.source().persistentUuid()) : null);
            statement.setString(index++, rows.snapshotJson());
            statement.setString(index++, rows.snapshotHash());
            statement.setInt(index++, rows.snapshotVersion());
            statement.setString(index++, rows.residentState().name());
            statement.setLong(index++, rows.binding().boundAtMs());
            statement.setLong(index++, rows.binding().boundAtMs());
            statement.setLong(index, rows.binding().boundAtMs());
            if (statement.executeUpdate() != 1) {
                return false;
            }
        }
        return ensureUuidClaim(connection, rows);
    }

    private boolean ensureUuidClaim(Connection connection, ManagedRows rows) throws SQLException {
        String uuid = rows.residentUuid().toString();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT resident_id, claim_kind, active FROM managed_coop_uuid_claims
                WHERE npc_uuid = ? LIMIT 2
                """)) {
            query.setString(1, uuid);
            try (ResultSet resultSet = query.executeQuery()) {
                if (resultSet.next()) {
                    boolean same = rows.binding().residentId().equals(resultSet.getString(1))
                            && "SOURCE".equals(resultSet.getString(2))
                            && resultSet.getInt(3) == 1;
                    return same && !resultSet.next();
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO managed_coop_uuid_claims (
                    npc_uuid, resident_id, claim_kind, active, created_at_ms, updated_at_ms
                ) VALUES (?, ?, 'SOURCE', 1, ?, ?)
                """)) {
            insert.setString(1, uuid);
            insert.setString(2, rows.binding().residentId());
            insert.setLong(3, rows.binding().boundAtMs());
            insert.setLong(4, rows.binding().boundAtMs());
            return insert.executeUpdate() == 1;
        }
    }

    private boolean residentMatches(Connection connection, ManagedRows rows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT authority_id, world_name, coop_id, x, y, z, resident_slot, profile_id,
                       resident_uuid, snapshot_json, snapshot_hash, snapshot_version,
                       state, generation, active
                FROM managed_coop_residents WHERE resident_id = ? LIMIT 2
                """)) {
            statement.setString(1, rows.binding().residentId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                boolean same = rows.authorityKey().authorityId().equals(resultSet.getString("authority_id"))
                        && rows.authorityKey().worldName().equals(resultSet.getString("world_name"))
                        && rows.coopId().equalsIgnoreCase(resultSet.getString("coop_id"))
                        && rows.authorityKey().x() == resultSet.getInt("x")
                        && rows.authorityKey().y() == resultSet.getInt("y")
                        && rows.authorityKey().z() == resultSet.getInt("z")
                        && rows.residentSlot() == resultSet.getInt("resident_slot")
                        && rows.binding().profileId().equals(resultSet.getString("profile_id"))
                        && rows.residentUuid().toString().equals(resultSet.getString("resident_uuid"))
                        && rows.snapshotJson().equals(resultSet.getString("snapshot_json"))
                        && rows.snapshotHash().equals(resultSet.getString("snapshot_hash"))
                        && rows.snapshotVersion() == resultSet.getInt("snapshot_version")
                        && rows.residentState().name().equals(resultSet.getString("state"))
                        && rows.residentGeneration() == resultSet.getLong("generation")
                        && resultSet.getInt("active") == 1;
                return same && !resultSet.next();
            }
        }
    }

    private boolean ensureOperation(Connection connection, ManagedRows rows) throws SQLException {
        Match operation = operationMatch(connection, rows);
        if (operation != Match.ABSENT) {
            return operation == Match.EXACT;
        }
        if (activeOperationAssignmentConflict(connection, rows)) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_lifecycle_operations (
                    operation_id, operation_kind, profile_id, authority_id, world_name, coop_id,
                    x, y, z, resident_slot, source_npc_uuid, planned_target_uuid,
                    actual_target_uuid, state, snapshot_hash, expected_generation, retry_count,
                    generation, active, created_at_ms, updated_at_ms, completed_at_ms, last_error
                ) VALUES (?, 'IMPORT', ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL,
                          'SOURCE_RETIRE_REQUESTED', ?, ?, 0, 2, 1, ?, ?, 0, NULL)
                """)) {
            int index = 1;
            statement.setString(index++, rows.binding().operationId());
            statement.setString(index++, rows.binding().profileId());
            statement.setString(index++, rows.authorityKey().authorityId());
            statement.setString(index++, rows.authorityKey().worldName());
            statement.setString(index++, rows.coopId());
            statement.setInt(index++, rows.authorityKey().x());
            statement.setInt(index++, rows.authorityKey().y());
            statement.setInt(index++, rows.authorityKey().z());
            statement.setInt(index++, rows.residentSlot());
            statement.setString(index++, uuid(rows.source().persistentUuid()));
            statement.setString(index++, rows.snapshotHash());
            statement.setLong(index++, rows.residentGeneration());
            statement.setLong(index++, rows.binding().boundAtMs());
            statement.setLong(index, rows.binding().boundAtMs());
            return statement.executeUpdate() == 1;
        }
    }

    private Match operationMatch(Connection connection, ManagedRows rows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_kind, profile_id, authority_id, resident_slot, state,
                       snapshot_hash, expected_generation, generation, active
                FROM coop_lifecycle_operations WHERE operation_id = ? LIMIT 2
                """)) {
            statement.setString(1, rows.binding().operationId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Match.ABSENT;
                }
                boolean same = "IMPORT".equals(resultSet.getString("operation_kind"))
                        && rows.binding().profileId().equals(resultSet.getString("profile_id"))
                        && rows.authorityKey().authorityId().equals(resultSet.getString("authority_id"))
                        && rows.residentSlot() == resultSet.getInt("resident_slot")
                        && "SOURCE_RETIRE_REQUESTED".equals(resultSet.getString("state"))
                        && rows.snapshotHash().equals(resultSet.getString("snapshot_hash"))
                        && rows.residentGeneration() == resultSet.getLong("expected_generation")
                        && resultSet.getLong("generation") == 2L
                        && resultSet.getInt("active") == 1;
                return same && !resultSet.next() ? Match.EXACT : Match.CONFLICT;
            }
        }
    }

    @Nullable
    private ProfileRow profile(Connection connection, String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_npc_uuid FROM npc_profiles WHERE profile_id = ?")) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? new ProfileRow(resultSet.getString(1)) : null;
            }
        }
    }

    @Nullable
    private String profileForUuid(Connection connection, String uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ?
                UNION SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ?
                """)) {
            statement.setString(1, uuid);
            statement.setString(2, uuid);
            String profile = null;
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (profile != null && !profile.equals(resultSet.getString(1))) {
                        return "__ambiguous__";
                    }
                    profile = resultSet.getString(1);
                }
            }
            return profile;
        }
    }

    private boolean residentExists(Connection connection, String residentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM managed_coop_residents WHERE resident_id = ?")) {
            statement.setString(1, residentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean activeResidentAssignmentConflict(Connection connection, ManagedRows rows)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM managed_coop_residents
                WHERE active = 1 AND resident_id <> ?
                  AND (profile_id = ? OR resident_uuid = ?
                       OR (authority_id = ? AND resident_slot = ?)) LIMIT 1
                """)) {
            statement.setString(1, rows.binding().residentId());
            statement.setString(2, rows.binding().profileId());
            statement.setString(3, rows.residentUuid().toString());
            statement.setString(4, rows.authorityKey().authorityId());
            statement.setInt(5, rows.residentSlot());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean activeOperationAssignmentConflict(Connection connection, ManagedRows rows)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM coop_lifecycle_operations
                WHERE active = 1 AND operation_id <> ?
                  AND (profile_id = ? OR (authority_id = ? AND resident_slot = ?)) LIMIT 1
                """)) {
            statement.setString(1, rows.binding().operationId());
            statement.setString(2, rows.binding().profileId());
            statement.setString(3, rows.authorityKey().authorityId());
            statement.setInt(4, rows.residentSlot());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Nullable
    private static String uuid(@Nullable UUID value) {
        return value == null ? null : value.toString();
    }

    private enum Match { ABSENT, EXACT, CONFLICT }

    private record ProfileRow(@Nullable String currentUuid) {
    }
}
