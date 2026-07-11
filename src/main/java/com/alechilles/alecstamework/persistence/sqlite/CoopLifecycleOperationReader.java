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

import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadValidation.normalizeCoopId;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadValidation.optionalSha256;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadValidation.optionalUuid;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadValidation.requireText;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadValidation.strictBoolean;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;

/** Strict deterministic reader for active managed-coop lifecycle operations. */
final class CoopLifecycleOperationReader {
    private static final String COLUMNS = """
            operation_id, operation_kind, profile_id, authority_id, world_name, coop_id, x, y, z,
            resident_slot, source_npc_uuid, planned_target_uuid, actual_target_uuid,
            state, snapshot_hash, expected_generation, generation, retry_count, active,
            created_at_ms, updated_at_ms, completed_at_ms, last_error
            """;
    private final ManagedCoopResidentReader authorityReader = new ManagedCoopResidentReader();

    List<OperationRecord> loadAllActive(Connection connection) throws SQLException {
        ArrayList<OperationRecord> operations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM coop_lifecycle_operations WHERE active = 1 "
                        + "ORDER BY lower(world_name), x, y, z, resident_slot, created_at_ms, operation_id");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                operations.add(read(resultSet));
            }
        }
        validateActiveOperations(operations);
        validateOperationAuthorities(operations, authorityReader.loadAllActiveAuthorities(connection));
        return List.copyOf(operations);
    }

    @Nullable
    List<OperationRecord> loadActiveForAuthority(Connection connection,
                                                 ManagedCoopAuthorityKey key,
                                                 String expectedCoopId) throws SQLException {
        if (authorityReader.loadAuthority(connection, key, expectedCoopId) == null) {
            return null;
        }
        ArrayList<OperationRecord> operations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM coop_lifecycle_operations "
                        + "WHERE lower(world_name) = ? AND x = ? AND y = ? AND z = ? AND active = 1 "
                        + "ORDER BY resident_slot, created_at_ms, operation_id")) {
            statement.setString(1, key.worldName());
            statement.setInt(2, key.x());
            statement.setInt(3, key.y());
            statement.setInt(4, key.z());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    OperationRecord operation = read(resultSet);
                    if (!operation.authorityKey().equals(key)
                            || !operation.coopId().equals(normalizeCoopId(expectedCoopId))) {
                        throw integrity("coop_lifecycle_authority_conflict:" + operation.operationId());
                    }
                    operations.add(operation);
                }
            }
        }
        validateActiveOperations(operations);
        return List.copyOf(operations);
    }

    private OperationRecord read(ResultSet resultSet) throws SQLException {
        try {
            String worldName = requireText(resultSet.getString("world_name"), "world_name");
            ManagedCoopAuthorityKey key = new ManagedCoopAuthorityKey(
                    worldName, resultSet.getInt("x"), resultSet.getInt("y"), resultSet.getInt("z"));
            String authorityId = requireText(resultSet.getString("authority_id"), "authority_id");
            if (!authorityId.equals(key.authorityId())) {
                throw integrity("invalid_coop_lifecycle_authority_id:" + authorityId);
            }
            int residentSlot = resultSet.getInt("resident_slot");
            long expectedGeneration = resultSet.getLong("expected_generation");
            long generation = resultSet.getLong("generation");
            int retryCount = resultSet.getInt("retry_count");
            if (residentSlot < 0 || expectedGeneration < 0L || generation < 0L || retryCount < 0) {
                throw integrity("negative_coop_lifecycle_slot_generation_or_retry");
            }
            return new OperationRecord(
                    requireText(resultSet.getString("operation_id"), "operation_id"),
                    OperationKind.valueOf(requireText(resultSet.getString("operation_kind"), "operation_kind")),
                    requireText(resultSet.getString("profile_id"), "profile_id"),
                    key,
                    normalizeCoopId(resultSet.getString("coop_id")),
                    residentSlot,
                    optionalUuid(resultSet.getString("source_npc_uuid"), "source_npc_uuid"),
                    optionalUuid(resultSet.getString("planned_target_uuid"), "planned_target_uuid"),
                    optionalUuid(resultSet.getString("actual_target_uuid"), "actual_target_uuid"),
                    OperationState.valueOf(requireText(resultSet.getString("state"), "operation_state")),
                    optionalSha256(resultSet.getString("snapshot_hash"), "operation_snapshot_hash"),
                    expectedGeneration,
                    generation,
                    retryCount,
                    strictBoolean(resultSet.getInt("active"), "operation_active"),
                    resultSet.getLong("created_at_ms"),
                    resultSet.getLong("updated_at_ms"),
                    resultSet.getLong("completed_at_ms"),
                    resultSet.getString("last_error")
            );
        } catch (IllegalArgumentException exception) {
            throw new ManagedCoopIntegrityException("invalid_coop_lifecycle_operation_row", exception);
        }
    }

    private void validateActiveOperations(List<OperationRecord> operations)
            throws ManagedCoopIntegrityException {
        HashSet<String> operationIds = new HashSet<>();
        HashSet<String> profileIds = new HashSet<>();
        HashSet<String> slotKeys = new HashSet<>();
        HashMap<UUID, String> uuidOwners = new HashMap<>();
        for (OperationRecord operation : operations) {
            if (!operationIds.add(operation.operationId())) {
                throw integrity("duplicate_active_coop_lifecycle_operation:" + operation.operationId());
            }
            if (!profileIds.add(operation.profileId())) {
                throw integrity("duplicate_active_coop_lifecycle_profile:" + operation.profileId());
            }
            String slotKey = operation.authorityKey().slotKey(operation.residentSlot());
            if (!slotKeys.add(slotKey)) {
                throw integrity("duplicate_active_coop_lifecycle_slot:" + slotKey);
            }
            HashSet<UUID> operationUuids = new HashSet<>();
            if (operation.sourceNpcUuid() != null) operationUuids.add(operation.sourceNpcUuid());
            if (operation.plannedTargetUuid() != null) operationUuids.add(operation.plannedTargetUuid());
            if (operation.actualTargetUuid() != null) operationUuids.add(operation.actualTargetUuid());
            registerUuids(uuidOwners, operationUuids, operation.operationId());
        }
    }

    private void validateOperationAuthorities(
            List<OperationRecord> operations,
            List<AuthorityRecord> authorities) throws ManagedCoopIntegrityException {
        HashMap<ManagedCoopAuthorityKey, String> coopIdByAuthority = new HashMap<>();
        for (AuthorityRecord authority : authorities) {
            coopIdByAuthority.put(authority.authorityKey(), authority.coopId());
        }
        for (OperationRecord operation : operations) {
            String coopId = coopIdByAuthority.get(operation.authorityKey());
            if (coopId == null || !coopId.equals(operation.coopId())) {
                throw integrity("active_coop_lifecycle_without_authority:" + operation.operationId());
            }
        }
    }

    private void registerUuids(Map<UUID, String> owners,
                               Set<UUID> uuids,
                               String operationId) throws ManagedCoopIntegrityException {
        for (UUID uuid : uuids) {
            String previous = owners.putIfAbsent(uuid, operationId);
            if (previous != null && !previous.equals(operationId)) {
                throw integrity("duplicate_active_coop_lifecycle_uuid:" + uuid);
            }
        }
    }

    private ManagedCoopIntegrityException integrity(String detail) {
        return new ManagedCoopIntegrityException(detail);
    }
}
