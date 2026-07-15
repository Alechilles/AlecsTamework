package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.CaptureRequest;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationStatus;
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the atomic, canonical managed-coop capture claim boundary. */
class CoopLifecycleCompositeCaptureClaimTest {
    private static final UUID SOURCE_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID UNMAPPED = UUID.fromString("10000000-0000-0000-0000-000000000099");
    private static final ManagedCoopAuthorityKey COOP =
            new ManagedCoopAuthorityKey("world", 10, 20, 30);

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceHealthService health;
    private PersistenceWriteQueue writeQueue;
    private ManagedCoopResidentRepository residents;
    private CoopLifecycleOperationRepository operations;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("composite-capture.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            insertProfile(connection, "profile-a", SOURCE_A);
            insertProfile(connection, "profile-b", SOURCE_B);
            insertAuthority(connection, COOP, "TWORK_MANAGED");
        }
        health = new PersistenceHealthService();
        writeQueue = new PersistenceWriteQueue(connections, health, null);
        residents = new ManagedCoopResidentRepository(connections, writeQueue);
        operations = new CoopLifecycleOperationRepository(connections, writeQueue, residents);
    }

    @AfterEach
    void tearDown() {
        if (writeQueue != null) {
            writeQueue.close();
        }
    }

    @Test
    void atomicallyCommitsCanonicalSnapshotAndReplaysIdempotently() throws Exception {
        CaptureRequest request = capture(COOP, "profile-a", SOURCE_A, 0, 0L, "mob_chicken", 100L);

        MutationResult first = committed(operations.claimCapture(request));
        assertEquals(MutationStatus.APPLIED, first.status());
        assertOperation(first, OperationState.SLOT_COMMITTED, 1L);
        ManagedCoopResidentRepository.ResidentRecord resident = residents.loadActiveSlot(COOP, 0);
        assertNotNull(resident);
        assertEquals(request.residentId(), resident.residentId());
        assertEquals(request.snapshotJson(), resident.snapshotJson());
        assertEquals(request.snapshotHash(), resident.snapshotHash());
        assertEquals(SOURCE_A, resident.sourceNpcUuid());

        MutationResult replay = committed(operations.claimCapture(request));
        assertEquals(MutationStatus.IDEMPOTENT, replay.status());
        assertOperation(replay, OperationState.SLOT_COMMITTED, 1L);
        assertEquals(1, count("coop_lifecycle_operations"));
        assertEquals(1, count("managed_coop_residents"));
    }

    /** Regression: an exact markerless migration row must recapture under its retained ID. */
    @Test
    void atomicallyRecapturesExactLegacyDeployedAssignment() throws Exception {
        String legacyResidentId = "legacy:world:coop_chicken:10:20:30:0";
        insertLegacyDeployedResident(legacyResidentId, "profile-a", SOURCE_A, 0);
        CaptureRequest canonical = capture(
                COOP, "profile-a", SOURCE_A, 0, 0L, "mob_chicken", 100L);
        CaptureRequest request = copyWithResidentId(canonical, legacyResidentId);

        MutationResult result = committed(operations.claimCapture(request));

        assertEquals(MutationStatus.APPLIED, result.status());
        assertOperation(result, OperationState.SLOT_COMMITTED, 1L);
        ManagedCoopResidentRepository.ResidentRecord resident =
                residents.loadActiveSlot(COOP, 0);
        assertNotNull(resident);
        assertEquals(legacyResidentId, resident.residentId());
        assertEquals(ManagedCoopResidentRepository.ResidentState.HOUSED, resident.state());
        assertEquals(1L, resident.generation());
        assertEquals(SOURCE_A, resident.sourceNpcUuid());
        assertNull(resident.deployedNpcUuid());
        assertEquals("SOURCE", scalar(
                "SELECT claim_kind FROM managed_coop_uuid_claims WHERE npc_uuid = '"
                        + SOURCE_A + "' AND active = 1"));
    }

    @Test
    void resumesCanonicalPreparedReplayWithoutChangingItsBundle() throws Exception {
        CaptureRequest request = capture(COOP, "profile-a", SOURCE_A, 0, 0L, "mob_chicken", 100L);
        MutationResult prepared = committed(operations.prepareCapture(request));
        assertEquals(MutationStatus.APPLIED, prepared.status());
        assertOperation(prepared, OperationState.PREPARED, 0L);
        assertNull(residents.loadActiveSlot(COOP, 0));

        MutationResult resumed = committed(operations.claimCapture(request));
        assertEquals(MutationStatus.APPLIED, resumed.status());
        assertOperation(resumed, OperationState.SLOT_COMMITTED, 1L);
        assertNotNull(residents.loadActiveSlot(COOP, 0));

        CaptureRequest nonCanonicalResident = copyWithResidentId(request, "resident-other");
        assertThrows(IllegalArgumentException.class, () -> operations.claimCapture(nonCanonicalResident));
        assertTrue(health.isHealthy());
    }

    @Test
    void savepointRollsBackPreparedInsertWhenSlotClaimConflicts() throws Exception {
        insertHistoricalUuidClaim(SOURCE_A, "different-resident");
        CaptureRequest request = capture(COOP, "profile-a", SOURCE_A, 0, 0L, "mob_chicken", 100L);

        MutationResult conflict = committed(operations.claimCapture(request));

        assertEquals(MutationStatus.CONFLICT, conflict.status());
        assertNull(conflict.operation());
        assertEquals(0, count("coop_lifecycle_operations"));
        assertEquals(1, count("managed_coop_residents"));
        assertNull(residents.loadActiveSlot(COOP, 0));
        assertEquals(1, count("managed_coop_uuid_claims"));
        assertTrue(health.isHealthy());
    }

    @Test
    void globallyRejectsSourceUuidClaimedByAnotherActiveOperation() throws Exception {
        CaptureRequest request = capture(COOP, "profile-a", SOURCE_A, 0, 0L, "mob_chicken", 100L);
        insertActiveSourceOperation("other-operation", "profile-b", SOURCE_A, 1, request.snapshotHash());

        MutationResult conflict = committed(operations.claimCapture(request));

        assertEquals(MutationStatus.CONFLICT, conflict.status());
        assertEquals("active_capture_source_operation_conflict", conflict.detail());
        assertEquals(1, count("coop_lifecycle_operations"));
        assertEquals(0, count("managed_coop_residents"));
    }

    @Test
    void rejectsUnverifiedOrMetadataMismatchedFullSnapshotsBeforeQueueing() {
        CaptureRequest valid = capture(COOP, "profile-a", SOURCE_A, 0, 0L, "mob_chicken", 100L);
        CaptureRequest wrongHash = canonicalCopy(valid, valid.snapshotJson(), "0".repeat(64));
        CaptureRequest wrongRolePayload = canonicalCopy(
                valid,
                valid.snapshotJson().replace("mob_chicken", "mob_cow"),
                null
        );
        CaptureRequest blankHash = new CaptureRequest(
                valid.operationId(), valid.residentId(), valid.authorityKey(), valid.coopId(),
                valid.residentSlot(), valid.profileId(), valid.roleId(), valid.sourceNpcUuid(),
                valid.snapshotJson(), " ", valid.snapshotVersion(),
                valid.expectedResidentGeneration(), valid.nowMs()
        );

        assertThrows(IllegalArgumentException.class, () -> operations.claimCapture(wrongHash));
        assertThrows(IllegalArgumentException.class, () -> operations.claimCapture(wrongRolePayload));
        assertThrows(IllegalArgumentException.class, () -> operations.claimCapture(blankHash));
        assertTrue(health.isHealthy());
        assertEquals(0, writeQueue.getLifecycleMetrics().pendingTaskCount());
    }

    @Test
    void requiresExactManagedAuthorityAndStableSourceProfileMapping() throws Exception {
        CaptureRequest unmapped = capture(COOP, "profile-a", UNMAPPED, 0, 0L, "mob_chicken", 100L);
        MutationResult mappingConflict = committed(operations.claimCapture(unmapped));
        assertEquals(MutationStatus.CONFLICT, mappingConflict.status());
        assertEquals("capture_source_profile_mapping_conflict", mappingConflict.detail());

        ManagedCoopAuthorityKey unmanagedKey = new ManagedCoopAuthorityKey("world", 40, 50, 60);
        insertAuthority(unmanagedKey, "VANILLA_DISCOVERED");
        CaptureRequest unmanaged = capture(
                unmanagedKey, "profile-b", SOURCE_B, 0, 0L, "mob_chicken", 110L);
        MutationResult authorityConflict = committed(operations.claimCapture(unmanaged));
        assertEquals(MutationStatus.CONFLICT, authorityConflict.status());
        assertEquals("managed_authority_not_found", authorityConflict.detail());

        assertEquals(0, count("managed_coop_residents"));
        assertEquals(0, count("coop_lifecycle_operations"));
    }

    private CaptureRequest capture(ManagedCoopAuthorityKey authority,
                                   String profileId,
                                   UUID sourceUuid,
                                   int slot,
                                   long expectedGeneration,
                                   String roleId,
                                   long nowMs) {
        String snapshotJson = "{\"version\":\"1\",\"npcUuid\":\"" + sourceUuid
                + "\",\"coopId\":\"coop_chicken\",\"residentSlot\":" + slot
                + ",\"roleId\":\"" + roleId + "\",\"capturedAtMs\":" + nowMs + "}";
        String snapshotHash = ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson);
        CaptureRequest provisional = new CaptureRequest(
                "pending",
                ManagedCoopCaptureClaimValidator.residentId(profileId),
                authority,
                "coop_chicken",
                slot,
                profileId,
                roleId,
                sourceUuid,
                snapshotJson,
                snapshotHash,
                1,
                expectedGeneration,
                nowMs
        );
        return copyWithOperationId(provisional, ManagedCoopCaptureClaimValidator.operationId(provisional));
    }

    private CaptureRequest canonicalCopy(CaptureRequest original,
                                         String snapshotJson,
                                         String requestedHash) {
        String hash = requestedHash != null
                ? requestedHash
                : ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson);
        CaptureRequest provisional = new CaptureRequest(
                "pending", original.residentId(), original.authorityKey(), original.coopId(),
                original.residentSlot(), original.profileId(), original.roleId(), original.sourceNpcUuid(),
                snapshotJson, hash, original.snapshotVersion(), original.expectedResidentGeneration(),
                original.nowMs()
        );
        return copyWithOperationId(provisional, ManagedCoopCaptureClaimValidator.operationId(provisional));
    }

    private CaptureRequest copyWithResidentId(CaptureRequest original, String residentId) {
        return new CaptureRequest(
                original.operationId(), residentId, original.authorityKey(), original.coopId(),
                original.residentSlot(), original.profileId(), original.roleId(), original.sourceNpcUuid(),
                original.snapshotJson(), original.snapshotHash(), original.snapshotVersion(),
                original.expectedResidentGeneration(), original.nowMs()
        );
    }

    private CaptureRequest copyWithOperationId(CaptureRequest original, String operationId) {
        return new CaptureRequest(
                operationId, original.residentId(), original.authorityKey(), original.coopId(),
                original.residentSlot(), original.profileId(), original.roleId(), original.sourceNpcUuid(),
                original.snapshotJson(), original.snapshotHash(), original.snapshotVersion(),
                original.expectedResidentGeneration(), original.nowMs()
        );
    }

    private <T> T committed(PersistenceWriteQueue.WriteSubmission<T> submission) throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<T> outcome = submission.completion().get(3, TimeUnit.SECONDS);
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status(), outcome.failureReason());
        assertNull(outcome.failure());
        assertNotNull(outcome.value());
        return outcome.value();
    }

    private void assertOperation(MutationResult result, OperationState state, long generation) {
        assertNotNull(result.operation());
        assertEquals(state, result.operation().state());
        assertEquals(generation, result.operation().generation());
        assertTrue(result.operation().active());
    }

    private int count(String table) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet resultSet = query.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement query = connection.prepareStatement(sql);
             ResultSet resultSet = query.executeQuery()) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private void insertLegacyDeployedResident(String residentId,
                                               String profileId,
                                               UUID deployedUuid,
                                               int slot) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement resident = connection.prepareStatement("""
                     INSERT INTO managed_coop_residents (
                         resident_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                         profile_id, role_id, resident_uuid, source_npc_uuid, deployed_npc_uuid,
                         snapshot_json, snapshot_version, state, generation, active,
                         captured_at_ms, released_at_ms, created_at_ms, updated_at_ms
                     ) VALUES (?, ?, ?, 'coop_chicken', ?, ?, ?, ?, ?, 'mob_chicken',
                         ?, NULL, ?, '{}', 1, 'DEPLOYED', 0, 1, 1, 2, 1, 2)
                     """);
             PreparedStatement claim = connection.prepareStatement("""
                     INSERT INTO managed_coop_uuid_claims (
                         npc_uuid, resident_id, claim_kind, active, created_at_ms, updated_at_ms
                     ) VALUES (?, ?, 'DEPLOYED', 1, 1, 2)
                     """)) {
            resident.setString(1, residentId);
            resident.setString(2, COOP.authorityId());
            resident.setString(3, COOP.worldName());
            resident.setInt(4, COOP.x());
            resident.setInt(5, COOP.y());
            resident.setInt(6, COOP.z());
            resident.setInt(7, slot);
            resident.setString(8, profileId);
            resident.setString(9, deployedUuid.toString());
            resident.setString(10, deployedUuid.toString());
            resident.executeUpdate();
            claim.setString(1, deployedUuid.toString());
            claim.setString(2, residentId);
            claim.executeUpdate();
        }
    }

    private void insertHistoricalUuidClaim(UUID sourceUuid, String residentId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement resident = connection.prepareStatement("""
                     INSERT INTO managed_coop_residents (
                         resident_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                         profile_id, role_id, resident_uuid, snapshot_version, state, generation,
                         active, captured_at_ms, released_at_ms, created_at_ms, updated_at_ms
                     ) VALUES (?, ?, ?, 'coop_chicken', ?, ?, ?, 5,
                         'profile-b', 'mob_chicken', ?, 1, 'RETIRED', 0, 0, 1, 0, 1, 1)
                     """);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO managed_coop_uuid_claims (
                         npc_uuid, resident_id, claim_kind, active, created_at_ms, updated_at_ms
                     ) VALUES (?, ?, 'SOURCE', 0, 1, 1)
                     """)) {
            resident.setString(1, residentId);
            resident.setString(2, COOP.authorityId());
            resident.setString(3, COOP.worldName());
            resident.setInt(4, COOP.x());
            resident.setInt(5, COOP.y());
            resident.setInt(6, COOP.z());
            resident.setString(7, SOURCE_B.toString());
            resident.executeUpdate();
            insert.setString(1, sourceUuid.toString());
            insert.setString(2, residentId);
            insert.executeUpdate();
        }
    }

    private void insertActiveSourceOperation(String operationId,
                                             String profileId,
                                             UUID sourceUuid,
                                             int slot,
                                             String snapshotHash) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO coop_lifecycle_operations (
                         operation_id, operation_kind, profile_id, authority_id, world_name, coop_id,
                         x, y, z, resident_slot, source_npc_uuid, state, snapshot_hash,
                         expected_generation, generation, retry_count, active,
                         created_at_ms, updated_at_ms, completed_at_ms
                     ) VALUES (?, 'CAPTURE', ?, ?, ?, 'coop_chicken', ?, ?, ?, ?, ?, 'PREPARED', ?,
                         0, 0, 0, 1, 1, 1, 0)
                     """)) {
            insert.setString(1, operationId);
            insert.setString(2, profileId);
            insert.setString(3, COOP.authorityId());
            insert.setString(4, COOP.worldName());
            insert.setInt(5, COOP.x());
            insert.setInt(6, COOP.y());
            insert.setInt(7, COOP.z());
            insert.setInt(8, slot);
            insert.setString(9, sourceUuid.toString());
            insert.setString(10, snapshotHash);
            insert.executeUpdate();
        }
    }

    private void insertAuthority(ManagedCoopAuthorityKey authority, String state) throws Exception {
        try (Connection connection = connections.openConnection()) {
            insertAuthority(connection, authority, state);
        }
    }

    private static void insertAuthority(Connection connection,
                                        ManagedCoopAuthorityKey authority,
                                        String state) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO managed_coop_authority (
                    authority_id, world_name, coop_id, x, y, z, authority_state,
                    active, import_version, created_at_ms, updated_at_ms
                ) VALUES (?, ?, 'coop_chicken', ?, ?, ?, ?, 1, 0, 1, 1)
                """)) {
            insert.setString(1, authority.authorityId());
            insert.setString(2, authority.worldName());
            insert.setInt(3, authority.x());
            insert.setInt(4, authority.y());
            insert.setInt(5, authority.z());
            insert.setString(6, state);
            insert.executeUpdate();
        }
    }

    private static void insertProfile(Connection connection, String profileId, UUID currentUuid)
            throws Exception {
        try (PreparedStatement profile = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, role_id,
                    created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, 'Mob_Chicken', 1, 1, 1)
                """);
             PreparedStatement alias = connection.prepareStatement("""
                INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms)
                VALUES (?, ?, 1, 1)
                """)) {
            profile.setString(1, profileId);
            profile.setString(2, currentUuid.toString());
            profile.executeUpdate();
            alias.setString(1, currentUuid.toString());
            alias.setString(2, profileId);
            alias.executeUpdate();
        }
    }
}
