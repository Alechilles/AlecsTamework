package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadValidation.normalizeCoopId;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadValidation.optionalSha256;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadValidation.optionalUuid;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadValidation.requireText;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadValidation.requireUuid;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadValidation.strictBoolean;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;

/** Strict, deterministic read projection for active managed-coop authority and occupancy. */
final class ManagedCoopResidentReader {
    private static final String AUTHORITY_COLUMNS = """
            authority_id, world_name, coop_id, x, y, z, authority_state, active,
            import_version, created_at_ms, updated_at_ms, last_error
            """;

    @Nullable
    AuthorityRecord loadAuthority(Connection connection,
                                  ManagedCoopAuthorityKey key,
                                  String expectedCoopId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + AUTHORITY_COLUMNS + " FROM managed_coop_authority "
                        + "WHERE lower(world_name) = ? AND x = ? AND y = ? AND z = ? AND active = 1 "
                        + "ORDER BY authority_id LIMIT 2")) {
            statement.setString(1, key.worldName());
            statement.setInt(2, key.x());
            statement.setInt(3, key.y());
            statement.setInt(4, key.z());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                AuthorityRecord record = readAuthority(resultSet);
                if (resultSet.next()) {
                    throw integrity("duplicate_active_managed_coop_authority:" + key.authorityId());
                }
                if (!record.authorityKey().equals(key)
                        || !record.coopId().equals(normalizeCoopId(expectedCoopId))) {
                    throw integrity("managed_coop_authority_identity_conflict:" + key.authorityId());
                }
                return record;
            }
        }
    }

    List<AuthorityRecord> loadAllActiveAuthorities(Connection connection) throws SQLException {
        ArrayList<AuthorityRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + AUTHORITY_COLUMNS + " FROM managed_coop_authority WHERE active = 1 "
                        + "ORDER BY lower(world_name), x, y, z, lower(coop_id), authority_id");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(readAuthority(resultSet));
            }
        }
        validateAuthorities(records);
        return List.copyOf(records);
    }

    List<ResidentRecord> loadAllActiveResidents(Connection connection) throws SQLException {
        ArrayList<ResidentRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM managed_coop_residents WHERE active = 1
                ORDER BY lower(world_name), x, y, z, resident_slot, profile_id, resident_id
                """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(readResident(resultSet));
            }
        }
        validateResidents(records);
        validateResidentAuthorities(records, loadAllActiveAuthorities(connection));
        return List.copyOf(records);
    }

    List<ResidentRecord> loadActiveResidents(Connection connection,
                                             ManagedCoopAuthorityKey key,
                                             String expectedCoopId) throws SQLException {
        ArrayList<ResidentRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM managed_coop_residents
                WHERE lower(world_name) = ? AND x = ? AND y = ? AND z = ? AND active = 1
                ORDER BY resident_slot, profile_id, resident_id
                """)) {
            statement.setString(1, key.worldName());
            statement.setInt(2, key.x());
            statement.setInt(3, key.y());
            statement.setInt(4, key.z());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ResidentRecord record = readResident(resultSet);
                    if (!record.authorityKey().equals(key)
                            || !record.coopId().equals(normalizeCoopId(expectedCoopId))) {
                        throw integrity("managed_coop_resident_authority_conflict:" + record.residentId());
                    }
                    records.add(record);
                }
            }
        }
        validateResidents(records);
        return List.copyOf(records);
    }

    private AuthorityRecord readAuthority(ResultSet resultSet) throws SQLException {
        try {
            String worldName = requireText(resultSet.getString("world_name"), "world_name");
            ManagedCoopAuthorityKey key = new ManagedCoopAuthorityKey(
                    worldName, resultSet.getInt("x"), resultSet.getInt("y"), resultSet.getInt("z"));
            String authorityId = requireText(resultSet.getString("authority_id"), "authority_id");
            if (!authorityId.equals(key.authorityId())) {
                throw integrity("invalid_managed_coop_authority_id:" + authorityId);
            }
            int importVersion = resultSet.getInt("import_version");
            if (importVersion < 0) {
                throw integrity("negative_managed_coop_import_version:" + authorityId);
            }
            return new AuthorityRecord(
                    authorityId,
                    key,
                    normalizeCoopId(resultSet.getString("coop_id")),
                    AuthorityState.valueOf(requireText(resultSet.getString("authority_state"), "authority_state")),
                    strictBoolean(resultSet.getInt("active"), "authority_active"),
                    importVersion,
                    resultSet.getLong("created_at_ms"),
                    resultSet.getLong("updated_at_ms"),
                    resultSet.getString("last_error")
            );
        } catch (IllegalArgumentException exception) {
            throw new ManagedCoopIntegrityException("invalid_managed_coop_authority_row", exception);
        }
    }

    private ResidentRecord readResident(ResultSet resultSet) throws SQLException {
        try {
            String worldName = requireText(resultSet.getString("world_name"), "world_name");
            ManagedCoopAuthorityKey key = new ManagedCoopAuthorityKey(
                    worldName, resultSet.getInt("x"), resultSet.getInt("y"), resultSet.getInt("z"));
            String authorityId = requireText(resultSet.getString("authority_id"), "authority_id");
            if (!authorityId.equals(key.authorityId())) {
                throw integrity("invalid_managed_resident_authority_id:" + authorityId);
            }
            int residentSlot = resultSet.getInt("resident_slot");
            int snapshotVersion = resultSet.getInt("snapshot_version");
            long generation = resultSet.getLong("generation");
            if (residentSlot < 0 || snapshotVersion < 1 || generation < 0L) {
                throw integrity("invalid_managed_resident_slot_snapshot_or_generation");
            }
            return new ResidentRecord(
                    requireText(resultSet.getString("resident_id"), "resident_id"),
                    key,
                    normalizeCoopId(resultSet.getString("coop_id")),
                    residentSlot,
                    requireText(resultSet.getString("profile_id"), "profile_id"),
                    trimToNull(resultSet.getString("role_id")),
                    requireUuid(resultSet.getString("resident_uuid"), "resident_uuid"),
                    optionalUuid(resultSet.getString("source_npc_uuid"), "source_npc_uuid"),
                    optionalUuid(resultSet.getString("deployed_npc_uuid"), "deployed_npc_uuid"),
                    resultSet.getString("snapshot_json"),
                    optionalSha256(resultSet.getString("snapshot_hash"), "resident_snapshot_hash"),
                    snapshotVersion,
                    ResidentState.valueOf(requireText(resultSet.getString("state"), "resident_state")),
                    generation,
                    strictBoolean(resultSet.getInt("active"), "resident_active"),
                    resultSet.getLong("captured_at_ms"),
                    resultSet.getLong("released_at_ms"),
                    resultSet.getLong("created_at_ms"),
                    resultSet.getLong("updated_at_ms")
            );
        } catch (IllegalArgumentException exception) {
            throw new ManagedCoopIntegrityException("invalid_managed_coop_resident_row", exception);
        }
    }

    private void validateAuthorities(List<AuthorityRecord> records) throws ManagedCoopIntegrityException {
        HashSet<String> authorityIds = new HashSet<>();
        HashSet<ManagedCoopAuthorityKey> locations = new HashSet<>();
        for (AuthorityRecord record : records) {
            if (!authorityIds.add(record.authorityId())) {
                throw integrity("duplicate_active_managed_coop_authority_id:" + record.authorityId());
            }
            if (!locations.add(record.authorityKey())) {
                throw integrity("duplicate_active_managed_coop_authority_location:"
                        + record.authorityKey().authorityId());
            }
        }
    }

    private void validateResidents(List<ResidentRecord> records) throws ManagedCoopIntegrityException {
        HashSet<String> residentIds = new HashSet<>();
        HashSet<String> profileIds = new HashSet<>();
        HashSet<String> slotKeys = new HashSet<>();
        HashMap<UUID, String> uuidOwners = new HashMap<>();
        for (ResidentRecord record : records) {
            if (!residentIds.add(record.residentId())) {
                throw integrity("duplicate_active_managed_resident_id:" + record.residentId());
            }
            if (!profileIds.add(record.profileId())) {
                throw integrity("duplicate_active_managed_resident_profile:" + record.profileId());
            }
            String slotKey = record.authorityKey().slotKey(record.residentSlot());
            if (!slotKeys.add(slotKey)) {
                throw integrity("duplicate_active_managed_resident_slot:" + slotKey);
            }
            HashSet<UUID> aliases = new HashSet<>();
            aliases.add(record.residentUuid());
            if (record.sourceNpcUuid() != null) aliases.add(record.sourceNpcUuid());
            if (record.deployedNpcUuid() != null) aliases.add(record.deployedNpcUuid());
            registerUuids(uuidOwners, aliases, record.residentId());
        }
    }

    private void validateResidentAuthorities(List<ResidentRecord> residents,
                                             List<AuthorityRecord> authorities)
            throws ManagedCoopIntegrityException {
        HashMap<ManagedCoopAuthorityKey, AuthorityRecord> authorityByKey = new HashMap<>();
        for (AuthorityRecord authority : authorities) {
            authorityByKey.put(authority.authorityKey(), authority);
        }
        for (ResidentRecord resident : residents) {
            AuthorityRecord authority = authorityByKey.get(resident.authorityKey());
            if (authority == null || !authority.coopId().equals(resident.coopId())) {
                throw integrity("active_managed_resident_without_authority:" + resident.residentId());
            }
        }
    }

    private void registerUuids(Map<UUID, String> owners,
                               Set<UUID> aliases,
                               String residentId) throws ManagedCoopIntegrityException {
        for (UUID alias : aliases) {
            String previous = owners.putIfAbsent(alias, residentId);
            if (previous != null && !previous.equals(residentId)) {
                throw integrity("duplicate_active_managed_resident_uuid:" + alias);
            }
        }
    }

    @Nullable
    private String trimToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ManagedCoopIntegrityException integrity(String detail) {
        return new ManagedCoopIntegrityException(detail);
    }
}
