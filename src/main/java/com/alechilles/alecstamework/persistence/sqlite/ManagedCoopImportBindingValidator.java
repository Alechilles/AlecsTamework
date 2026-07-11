package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionBinding;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionKind;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionRecord;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceRecord;

/** Proves every durable row used to authorize one exact vanilla-source neutralization. */
final class ManagedCoopImportBindingValidator {
    String validate(Connection connection,
                    SessionRecord session,
                    SourceRecord source,
                    DispositionBinding binding,
                    boolean requireComplete) throws SQLException {
        if (referenceAlreadyBound(connection, binding)) {
            return "import_durable_reference_already_bound";
        }
        if (binding.disposition() == DispositionKind.QUARANTINED) {
            return validConflict(connection, session, source, binding)
                    ? null : "quarantine_conflict_binding_invalid";
        }
        ResidentRow resident = loadResident(connection, binding.residentId());
        if (resident == null || !validResident(session, source, binding, resident)) {
            return "import_resident_binding_invalid";
        }
        OperationRow operation = loadOperation(connection, binding.operationId());
        if (operation == null || !validOperation(
                session, source, binding, resident, operation, requireComplete)) {
            return "import_operation_binding_invalid";
        }
        return validProfileIdentity(connection, source, binding, resident)
                ? null : "import_profile_binding_invalid";
    }

    private boolean referenceAlreadyBound(Connection connection, DispositionBinding binding)
            throws SQLException {
        String column = binding.disposition() == DispositionKind.QUARANTINED
                ? "conflict_id" : "operation_id";
        String value = binding.disposition() == DispositionKind.QUARANTINED
                ? binding.conflictId() : binding.operationId();
        if (sourceReferenceExists(connection, column, value, binding.sourceId(), null)) {
            return true;
        }
        return binding.disposition() == DispositionKind.IMPORTED
                && sourceReferenceExists(connection, "resident_id", binding.residentId(),
                binding.sourceId(), "IMPORTED");
    }

    private boolean sourceReferenceExists(Connection connection,
                                          String column,
                                          String value,
                                          String sourceId,
                                          String disposition) throws SQLException {
        String suffix = disposition == null ? "" : " AND disposition_kind = ?";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM managed_coop_import_sources WHERE " + column
                        + " = ? AND source_id <> ?" + suffix + " LIMIT 1")) {
            statement.setString(1, value);
            statement.setString(2, sourceId);
            if (disposition != null) {
                statement.setString(3, disposition);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean validResident(SessionRecord session,
                                  SourceRecord source,
                                  DispositionBinding binding,
                                  ResidentRow resident) {
        String expectedState = source.evidence().deployedToWorld() ? "DEPLOYED" : "HOUSED";
        if (!resident.active || !expectedState.equals(resident.state)
                || !authorityMatches(session, resident.authorityId, resident.worldName,
                resident.x, resident.y, resident.z)
                || !session.envelope().coopId().equalsIgnoreCase(resident.coopId)
                || !binding.profileId().equals(resident.profileId)) {
            return false;
        }
        if (binding.disposition() == DispositionKind.IMPORTED
                && (!source.evidence().managedSnapshotHash().equals(resident.snapshotHash)
                || source.evidence().managedSnapshotVersion() != resident.snapshotVersion)) {
            return false;
        }
        UUID persistentUuid = source.evidence().persistentUuid();
        return persistentUuid == null || binding.disposition() != DispositionKind.IMPORTED
                || resident.contains(persistentUuid.toString());
    }

    private boolean validOperation(SessionRecord session,
                                   SourceRecord source,
                                   DispositionBinding binding,
                                   ResidentRow resident,
                                   OperationRow operation,
                                   boolean requireComplete) {
        boolean stateMatches = requireComplete
                ? "COMPLETE".equals(operation.state) && operation.generation == 3L
                && !operation.active && operation.completedAtMs != 0L
                && operation.completedAtMs == source.verifiedAbsentAtMs()
                : "SOURCE_RETIRE_REQUESTED".equals(operation.state)
                && operation.generation == 2L && operation.active
                && operation.completedAtMs == 0L;
        String sourceUuid = source.evidence().persistentUuid() == null
                ? null : source.evidence().persistentUuid().toString();
        return stateMatches && "IMPORT".equals(operation.kind)
                && authorityMatches(session, operation.authorityId, operation.worldName,
                operation.x, operation.y, operation.z)
                && session.envelope().coopId().equalsIgnoreCase(operation.coopId)
                && binding.profileId().equals(operation.profileId)
                && operation.residentSlot == resident.residentSlot
                && operation.expectedGeneration == resident.generation
                && Objects.equals(operation.snapshotHash, resident.snapshotHash)
                && Objects.equals(sourceUuid, operation.sourceNpcUuid);
    }

    private boolean validProfileIdentity(Connection connection,
                                         SourceRecord source,
                                         DispositionBinding binding,
                                         ResidentRow resident) throws SQLException {
        String auditedProfile = source.evidence().profileAtAuditId();
        if (auditedProfile != null && !auditedProfile.equals(binding.profileId())) {
            return false;
        }
        UUID sourceUuid = source.evidence().persistentUuid();
        if (sourceUuid == null) {
            return profileExists(connection, binding.profileId());
        }
        return uuidMapsOnlyToProfile(connection, sourceUuid, binding.profileId())
                && (binding.disposition() != DispositionKind.IMPORTED
                || resident.contains(sourceUuid.toString()));
    }

    private boolean validConflict(Connection connection,
                                  SessionRecord session,
                                  SourceRecord source,
                                  DispositionBinding binding) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT authority_id, world_name, coop_id, x, y, z, resident_slot,
                       conflict_kind, source_fingerprint, source_payload, resolution_state
                FROM coop_import_conflicts WHERE conflict_id = ? LIMIT 2
                """)) {
            statement.setString(1, binding.conflictId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                int sourceSlot = resultSet.getInt("resident_slot");
                boolean slotPresent = !resultSet.wasNull();
                boolean matches = authorityMatches(
                        session, resultSet.getString("authority_id"),
                        resultSet.getString("world_name"), resultSet.getInt("x"),
                        resultSet.getInt("y"), resultSet.getInt("z"))
                        && session.envelope().coopId().equalsIgnoreCase(
                        resultSet.getString("coop_id"))
                        && slotPresent && sourceSlot == source.evidence().sourceSlot()
                        && binding.conflictKind().equals(resultSet.getString("conflict_kind"))
                        && source.evidence().sourceFingerprint().equals(
                        resultSet.getString("source_fingerprint"))
                        && source.evidence().sourcePayload().equals(
                        resultSet.getString("source_payload"))
                        && "UNRESOLVED".equals(resultSet.getString("resolution_state"));
                return matches && !resultSet.next();
            }
        }
    }

    private ResidentRow loadResident(Connection connection, String residentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT authority_id, world_name, coop_id, x, y, z, resident_slot, profile_id,
                       resident_uuid, source_npc_uuid, deployed_npc_uuid, snapshot_hash,
                       snapshot_version, state, generation, active
                FROM managed_coop_residents WHERE resident_id = ? LIMIT 2
                """)) {
            statement.setString(1, residentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                ResidentRow row = new ResidentRow(
                        resultSet.getString("authority_id"), resultSet.getString("world_name"),
                        resultSet.getString("coop_id"), resultSet.getInt("x"),
                        resultSet.getInt("y"), resultSet.getInt("z"),
                        resultSet.getInt("resident_slot"), resultSet.getString("profile_id"),
                        resultSet.getString("resident_uuid"), resultSet.getString("source_npc_uuid"),
                        resultSet.getString("deployed_npc_uuid"),
                        resultSet.getString("snapshot_hash"), resultSet.getInt("snapshot_version"),
                        resultSet.getString("state"), resultSet.getLong("generation"),
                        resultSet.getInt("active") == 1);
                return resultSet.next() ? null : row;
            }
        }
    }

    private OperationRow loadOperation(Connection connection, String operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_kind, authority_id, world_name, coop_id, x, y, z,
                       resident_slot, profile_id, source_npc_uuid, state, snapshot_hash,
                       expected_generation, generation, active, completed_at_ms
                FROM coop_lifecycle_operations WHERE operation_id = ? LIMIT 2
                """)) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                OperationRow row = new OperationRow(
                        resultSet.getString("operation_kind"), resultSet.getString("authority_id"),
                        resultSet.getString("world_name"), resultSet.getString("coop_id"),
                        resultSet.getInt("x"), resultSet.getInt("y"), resultSet.getInt("z"),
                        resultSet.getInt("resident_slot"), resultSet.getString("profile_id"),
                        resultSet.getString("source_npc_uuid"), resultSet.getString("state"),
                        resultSet.getString("snapshot_hash"),
                        resultSet.getLong("expected_generation"), resultSet.getLong("generation"),
                        resultSet.getInt("active") == 1, resultSet.getLong("completed_at_ms"));
                return resultSet.next() ? null : row;
            }
        }
    }

    private boolean profileExists(Connection connection, String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM npc_profiles WHERE profile_id = ? LIMIT 1")) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean uuidMapsOnlyToProfile(Connection connection, UUID uuid, String profileId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ?
                UNION ALL
                SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ?
                """)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, uuid.toString());
            boolean found = false;
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    found = true;
                    if (!profileId.equals(resultSet.getString(1))) {
                        return false;
                    }
                }
            }
            return found;
        }
    }

    private boolean authorityMatches(SessionRecord session,
                                     String authorityId,
                                     String worldName,
                                     int x,
                                     int y,
                                     int z) {
        ManagedCoopAuthorityKey key = session.envelope().authorityKey();
        return key.authorityId().equals(authorityId)
                && key.worldName().equalsIgnoreCase(worldName)
                && key.x() == x && key.y() == y && key.z() == z;
    }

    private record ResidentRow(String authorityId,
                               String worldName,
                               String coopId,
                               int x,
                               int y,
                               int z,
                               int residentSlot,
                               String profileId,
                               String residentUuid,
                               String sourceNpcUuid,
                               String deployedNpcUuid,
                               String snapshotHash,
                               int snapshotVersion,
                               String state,
                               long generation,
                               boolean active) {
        boolean contains(String uuid) {
            return uuid.equals(residentUuid) || uuid.equals(sourceNpcUuid)
                    || uuid.equals(deployedNpcUuid);
        }
    }

    private record OperationRow(String kind,
                                String authorityId,
                                String worldName,
                                String coopId,
                                int x,
                                int y,
                                int z,
                                int residentSlot,
                                String profileId,
                                String sourceNpcUuid,
                                String state,
                                String snapshotHash,
                                long expectedGeneration,
                                long generation,
                                boolean active,
                                long completedAtMs) {
    }
}
