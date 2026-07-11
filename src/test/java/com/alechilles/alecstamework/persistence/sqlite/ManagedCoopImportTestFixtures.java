package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.BeginSessionRequest;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionBinding;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionKind;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SessionEnvelope;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;

/** Deterministic SQL and immutable evidence fixtures shared by import repository tests. */
final class ManagedCoopImportTestFixtures {
    static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);
    static final String COOP_ID = "coop_chicken";

    private ManagedCoopImportTestFixtures() {
    }

    static BeginSessionRequest request(String sessionId, List<SourceEvidence> sources) {
        String auditJson = "{\"session\":\"" + sessionId + "\"}";
        String produce = "{\"items\":[]}";
        return new BeginSessionRequest(
                new SessionEnvelope(
                        sessionId,
                        AUTHORITY,
                        COOP_ID,
                        1,
                        hash("audit:" + sessionId),
                        auditJson,
                        hash(auditJson),
                        "hytale-0.5.6-coop-block-v1",
                        "Coop_Chicken",
                        "java.util.ArrayList",
                        produce,
                        hash(produce),
                        hash("begin:" + sessionId),
                        -100L
                ),
                sources
        );
    }

    static SourceEvidence source(String sourceId, int slot, int order, UUID uuid) {
        return source(sourceId, slot, order, uuid, "profile-" + sourceId);
    }

    static SourceEvidence source(String sourceId,
                                 int slot,
                                 int order,
                                 UUID uuid,
                                 String profileAtAuditId) {
        String sourceEnvelope = "{\"sourceId\":\"" + sourceId + "\"}";
        String payload = "{\"role\":\"Mob_Chicken\",\"id\":\"" + sourceId + "\"}";
        String locator = "{\"slot\":" + slot + ",\"order\":" + order + "}";
        String snapshot = "{\"profileVersion\":1,\"source\":\"" + sourceId + "\"}";
        return new SourceEvidence(
                sourceId,
                hash("fingerprint:" + sourceId),
                sourceEnvelope,
                hash(sourceEnvelope),
                payload,
                hash(payload),
                locator,
                hash(locator),
                slot,
                order,
                true,
                uuid != null,
                uuid,
                false,
                "2026-07-11T12:00:00Z",
                profileAtAuditId,
                "Mob_Chicken",
                "Hen " + sourceId,
                snapshot,
                hash(snapshot),
                1,
                "[\"ownerUuid\"]"
        );
    }

    static SourceEvidence withManagedSnapshot(SourceEvidence source,
                                              SourceEvidence snapshotSource) {
        return new SourceEvidence(
                source.sourceId(), source.sourceFingerprint(), source.sourceEnvelopeJson(),
                source.sourceEnvelopeHash(), source.sourcePayload(), source.sourcePayloadHash(),
                source.locatorHintsJson(), source.locatorHintsHash(), source.sourceSlot(),
                source.sourceOrder(), source.metadataPresent(), source.persistentRefPresent(),
                source.persistentUuid(), source.deployedToWorld(), source.lastProduced(),
                source.profileAtAuditId(), source.roleId(), source.displayName(),
                snapshotSource.managedSnapshotJson(), snapshotSource.managedSnapshotHash(),
                snapshotSource.managedSnapshotVersion(), source.unavailableFieldsJson());
    }

    static DispositionBinding managedBinding(BeginSessionRequest request,
                                             SourceEvidence source,
                                             DispositionKind disposition) {
        return managedBinding(request, source, disposition,
                "operation-" + source.sourceId(), "resident-" + source.sourceId(),
                "profile-" + source.sourceId());
    }

    static DispositionBinding managedBinding(BeginSessionRequest request,
                                              SourceEvidence source,
                                              DispositionKind disposition,
                                              String operationId,
                                              String residentId,
                                              String profileId) {
        return new DispositionBinding(
                request.envelope().sessionId(),
                source.sourceId(),
                request.envelope().auditFingerprint(),
                source.sourceFingerprint(),
                hash("bind:" + source.sourceId()),
                disposition,
                operationId,
                residentId,
                profileId,
                null,
                null,
                -80L
        );
    }

    static DispositionBinding quarantineBinding(BeginSessionRequest request,
                                                SourceEvidence source) {
        return new DispositionBinding(
                request.envelope().sessionId(),
                source.sourceId(),
                request.envelope().auditFingerprint(),
                source.sourceFingerprint(),
                hash("bind:" + source.sourceId()),
                DispositionKind.QUARANTINED,
                null,
                null,
                null,
                "conflict-" + source.sourceId(),
                "AMBIGUOUS_SOURCE",
                -80L
        );
    }

    static void insertAuthority(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_authority (
                    authority_id, world_name, coop_id, x, y, z, authority_state, active,
                    import_version, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, 'VANILLA_DISCOVERED', 1, 0, -200, -200)
                """)) {
            statement.setString(1, AUTHORITY.authorityId());
            statement.setString(2, AUTHORITY.worldName());
            statement.setString(3, COOP_ID);
            statement.setInt(4, AUTHORITY.x());
            statement.setInt(5, AUTHORITY.y());
            statement.setInt(6, AUTHORITY.z());
            statement.executeUpdate();
        }
    }

    static void insertManagedBinding(Connection connection,
                                     DispositionBinding binding,
                                     SourceEvidence source) throws SQLException {
        insertProfile(connection, binding.profileId(), source.persistentUuid());
        insertResident(connection, binding, source, source.sourceSlot(), "HOUSED", 0L);
        insertImportOperation(
                connection, binding, source, source.sourceSlot(),
                "SOURCE_RETIRE_REQUESTED", 2L, true, 0L, 0L);
    }

    static void insertConflict(Connection connection,
                               DispositionBinding binding,
                               SourceEvidence source) throws SQLException {
        insertConflict(connection, binding, source, source.sourceSlot(),
                binding.conflictKind(), "UNRESOLVED");
    }

    static void insertConflict(Connection connection,
                               DispositionBinding binding,
                               SourceEvidence source,
                               int residentSlot,
                               String conflictKind,
                               String resolutionState) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_import_conflicts (
                    conflict_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                    conflict_kind, source_fingerprint, source_payload, resolution_state,
                    created_at_ms, resolved_at_ms, resolution_note
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, -80, ?, ?)
                """)) {
            statement.setString(1, binding.conflictId());
            statement.setString(2, AUTHORITY.authorityId());
            statement.setString(3, AUTHORITY.worldName());
            statement.setString(4, COOP_ID);
            statement.setInt(5, AUTHORITY.x());
            statement.setInt(6, AUTHORITY.y());
            statement.setInt(7, AUTHORITY.z());
            statement.setInt(8, residentSlot);
            statement.setString(9, conflictKind);
            statement.setString(10, source.sourceFingerprint());
            statement.setString(11, source.sourcePayload());
            statement.setString(12, resolutionState);
            statement.setLong(13, "UNRESOLVED".equals(resolutionState) ? 0L : -70L);
            statement.setString(14, "UNRESOLVED".equals(resolutionState) ? null : "test");
            statement.executeUpdate();
        }
    }

    static void insertProfile(Connection connection,
                              String profileId,
                              UUID currentUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, role_id,
                    created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, 'Mob_Chicken', -90, -90, -90)
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, currentUuid == null ? null : currentUuid.toString());
            statement.executeUpdate();
        }
    }

    static void insertResident(Connection connection,
                               DispositionBinding binding,
                               SourceEvidence source,
                               int residentSlot,
                               String state,
                               long generation) throws SQLException {
        UUID residentUuid = source.persistentUuid() == null
                ? UUID.nameUUIDFromBytes(source.sourceId().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : source.persistentUuid();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_residents (
                    resident_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                    profile_id, role_id, resident_uuid, source_npc_uuid, snapshot_json,
                    snapshot_hash, snapshot_version, state, generation, active,
                    captured_at_ms, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'Mob_Chicken', ?, ?, ?, ?, ?,
                          ?, ?, 1, -90, -90, -90)
                """)) {
            int index = 1;
            statement.setString(index++, binding.residentId());
            statement.setString(index++, AUTHORITY.authorityId());
            statement.setString(index++, AUTHORITY.worldName());
            statement.setString(index++, COOP_ID);
            statement.setInt(index++, AUTHORITY.x());
            statement.setInt(index++, AUTHORITY.y());
            statement.setInt(index++, AUTHORITY.z());
            statement.setInt(index++, residentSlot);
            statement.setString(index++, binding.profileId());
            statement.setString(index++, residentUuid.toString());
            statement.setString(index++, source.persistentUuid() == null
                    ? null : source.persistentUuid().toString());
            statement.setString(index++, source.managedSnapshotJson());
            statement.setString(index++, source.managedSnapshotHash());
            statement.setInt(index++, source.managedSnapshotVersion());
            statement.setString(index++, state);
            statement.setLong(index, generation);
            statement.executeUpdate();
        }
    }

    static void insertImportOperation(Connection connection,
                                      DispositionBinding binding,
                                      SourceEvidence source,
                                      int residentSlot,
                                      String state,
                                      long generation,
                                      boolean active,
                                      long completedAtMs,
                                      long expectedResidentGeneration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_lifecycle_operations (
                    operation_id, operation_kind, profile_id, authority_id, world_name, coop_id,
                    x, y, z, resident_slot, source_npc_uuid, state, snapshot_hash,
                    expected_generation, generation, retry_count, active,
                    created_at_ms, updated_at_ms, completed_at_ms
                ) VALUES (?, 'IMPORT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, 0, ?, -90, -90, ?)
                """)) {
            int index = 1;
            statement.setString(index++, binding.operationId());
            statement.setString(index++, binding.profileId());
            statement.setString(index++, AUTHORITY.authorityId());
            statement.setString(index++, AUTHORITY.worldName());
            statement.setString(index++, COOP_ID);
            statement.setInt(index++, AUTHORITY.x());
            statement.setInt(index++, AUTHORITY.y());
            statement.setInt(index++, AUTHORITY.z());
            statement.setInt(index++, residentSlot);
            statement.setString(index++, source.persistentUuid() == null
                    ? null : source.persistentUuid().toString());
            statement.setString(index++, state);
            statement.setString(index++, source.managedSnapshotHash());
            statement.setLong(index++, expectedResidentGeneration);
            statement.setLong(index++, generation);
            statement.setInt(index++, active ? 1 : 0);
            statement.setLong(index, completedAtMs);
            statement.executeUpdate();
        }
    }

    static String hash(String value) {
        return ManagedCoopImportValidation.sha256(value);
    }
}
