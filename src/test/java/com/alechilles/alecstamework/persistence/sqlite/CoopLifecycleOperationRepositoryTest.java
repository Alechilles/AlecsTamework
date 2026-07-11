package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.ReleaseRequest;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for durable managed-coop capture and release crash boundaries. */
class CoopLifecycleOperationRepositoryTest {
    private static final UUID SOURCE_A = uuid("10000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE_B = uuid("10000000-0000-0000-0000-000000000002");
    private static final UUID TARGET_A = uuid("20000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_B = uuid("20000000-0000-0000-0000-000000000002");
    private static final ManagedCoopAuthorityKey COOP =
            new ManagedCoopAuthorityKey("world", 10, 20, 30);

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceWriteQueue writeQueue;
    private ManagedCoopResidentRepository residents;
    private CoopLifecycleOperationRepository operations;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("managed-coop.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            insertProfile(connection, "profile-a", SOURCE_A);
            insertProfile(connection, "profile-b", SOURCE_B);
            insertAuthority(connection);
        }
        writeQueue = new PersistenceWriteQueue(connections, new PersistenceHealthService(), null);
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
    void emptySlotCaptureTraversesDurableStatesAndKeepsResidentActive() throws Exception {
        CaptureRequest request = capture("capture-a", "resident-a", "profile-a", SOURCE_A, 0, 0L, 100L);

        MutationResult prepared = committed(operations.prepareCapture(request));
        assertOperation(prepared, MutationStatus.APPLIED, OperationState.PREPARED, true, 0L);
        assertEquals(MutationStatus.IDEMPOTENT,
                committed(operations.prepareCapture(request)).status());
        assertNull(residents.loadById("resident-a"));

        MutationResult slotCommitted = committed(operations.commitCaptureSlot(request, 0L));
        assertOperation(slotCommitted, MutationStatus.APPLIED, OperationState.SLOT_COMMITTED, true, 1L);
        ResidentRecord housed = residents.loadById("resident-a");
        assertResident(housed, ResidentState.HOUSED, true, 0L, SOURCE_A);
        assertEquals(MutationStatus.IDEMPOTENT,
                committed(operations.commitCaptureSlot(request, 0L)).status());

        MutationResult retireRequested = committed(
                operations.requestCaptureSourceRetirement("capture-a", 1L, 110L));
        assertOperation(retireRequested, MutationStatus.APPLIED,
                OperationState.SOURCE_RETIRE_REQUESTED, true, 2L);

        MutationResult complete = committed(operations.completeCapture("capture-a", 2L, 120L));
        assertOperation(complete, MutationStatus.APPLIED, OperationState.COMPLETE, false, 3L);
        assertEquals(MutationStatus.IDEMPOTENT,
                committed(operations.completeCapture("capture-a", 2L, 130L)).status());

        ResidentRecord stillHoused = residents.loadActiveSlot(COOP, 0);
        assertResident(stillHoused, ResidentState.HOUSED, true, 0L, SOURCE_A);
        assertEquals(1, residents.findFirstAvailableSlot(COOP, 2));
        assertNull(operations.loadActiveForProfile("profile-a"));
    }

    @Test
    void releaseTraversesCasStatesAndLeavesDeployedResidentOccupyingSlot() throws Exception {
        houseResidentA();
        ReleaseRequest request = release("release-a", TARGET_A, 0L, 200L);

        MutationResult prepared = committed(operations.prepareRelease(request));
        assertOperation(prepared, MutationStatus.APPLIED, OperationState.PREPARED, true, 0L);
        assertResident(residents.loadById("resident-a"),
                ResidentState.RELEASING, true, 1L, SOURCE_A);
        assertEquals(MutationStatus.IDEMPOTENT,
                committed(operations.prepareRelease(request)).status());

        MutationResult staleClaim = committed(operations.claimReleaseSpawn("release-a", 1L, 205L));
        assertEquals(MutationStatus.CONFLICT, staleClaim.status());
        MutationResult spawnClaimed = committed(operations.claimReleaseSpawn("release-a", 0L, 210L));
        assertOperation(spawnClaimed, MutationStatus.APPLIED, OperationState.SPAWN_CLAIMED, true, 1L);

        MutationResult projected = committed(
                operations.markProjectionCreated("release-a", 1L, TARGET_A, 220L));
        assertOperation(projected, MutationStatus.APPLIED,
                OperationState.PROJECTION_CREATED, true, 2L);
        assertEquals(MutationStatus.IDEMPOTENT,
                committed(operations.markProjectionCreated("release-a", 1L, TARGET_A, 225L)).status());

        MutationResult finalized = committed(operations.finalizeRelease("release-a", 2L, 230L));
        assertOperation(finalized, MutationStatus.APPLIED, OperationState.FINALIZED, false, 3L);
        ResidentRecord deployed = residents.loadById("resident-a");
        assertResident(deployed, ResidentState.DEPLOYED, true, 2L, TARGET_A);
        assertEquals(TARGET_A, deployed.deployedNpcUuid());
        assertEquals(MutationStatus.IDEMPOTENT,
                committed(operations.finalizeRelease("release-a", 2L, 240L)).status());
        assertEquals(-1, residents.findFirstAvailableSlot(COOP, 1));
        assertEquals(List.of("DEPLOYED", "SOURCE"), activeClaimKinds("resident-a"));
    }

    @Test
    void finalizedReleaseCanBeRecapturedWithoutHistoricalTargetFalseConflict() throws Exception {
        houseResidentA();
        releaseResidentA("release-a", TARGET_A, 200L);
        remapProfile("profile-a", SOURCE_A, TARGET_A);

        CaptureRequest recapture = capture(
                "capture-a-again",
                "resident-a",
                "profile-a",
                TARGET_A,
                0,
                2L,
                300L
        );
        assertOperation(committed(operations.prepareCapture(recapture)),
                MutationStatus.APPLIED, OperationState.PREPARED, true, 0L);
        assertOperation(committed(operations.commitCaptureSlot(recapture, 0L)),
                MutationStatus.APPLIED, OperationState.SLOT_COMMITTED, true, 1L);

        ResidentRecord recaptured = residents.loadById("resident-a");
        assertResident(recaptured, ResidentState.HOUSED, true, 3L, TARGET_A);
        assertEquals(TARGET_A, recaptured.sourceNpcUuid());
        assertNull(recaptured.deployedNpcUuid());
    }

    @Test
    void profileSlotAndCanonicalUuidConflictsFailClosedWithoutMutation() throws Exception {
        CaptureRequest wrongAlias = capture(
                "wrong-alias",
                "resident-a",
                "profile-a",
                SOURCE_B,
                0,
                0L,
                100L
        );
        MutationResult aliasConflict = committed(operations.prepareCapture(wrongAlias));
        assertEquals(MutationStatus.CONFLICT, aliasConflict.status());
        assertEquals("capture_uuid_conflict", aliasConflict.detail());
        assertNull(operations.load("wrong-alias"));

        CaptureRequest first = capture("capture-a", "resident-a", "profile-a", SOURCE_A, 0, 0L, 110L);
        assertEquals(MutationStatus.APPLIED, committed(operations.prepareCapture(first)).status());
        CaptureRequest sameProfile = capture(
                "capture-a-2", "resident-a-2", "profile-a", SOURCE_A, 1, 0L, 111L);
        assertEquals("active_profile_or_slot_operation_conflict",
                committed(operations.prepareCapture(sameProfile)).detail());
        CaptureRequest sameSlot = capture(
                "capture-b", "resident-b", "profile-b", SOURCE_B, 0, 0L, 112L);
        assertEquals("active_profile_or_slot_operation_conflict",
                committed(operations.prepareCapture(sameSlot)).detail());

        committed(operations.commitCaptureSlot(first, 0L));
        committed(operations.requestCaptureSourceRetirement("capture-a", 1L, 120L));
        committed(operations.completeCapture("capture-a", 2L, 121L));
        ReleaseRequest aliasedTarget = release("release-a", SOURCE_B, 0L, 130L);
        MutationResult releaseConflict = committed(operations.prepareRelease(aliasedTarget));
        assertEquals(MutationStatus.CONFLICT, releaseConflict.status());
        assertEquals("release_target_uuid_conflict", releaseConflict.detail());
        assertEquals(ResidentState.HOUSED, residents.loadById("resident-a").state());
    }

    @Test
    void projectionRejectsNewlyMappedActualTargetAndPreservesSpawnClaim() throws Exception {
        houseResidentA();
        ReleaseRequest request = release("release-a", TARGET_A, 0L, 200L);
        committed(operations.prepareRelease(request));
        committed(operations.claimReleaseSpawn("release-a", 0L, 210L));

        MutationResult conflict = committed(
                operations.markProjectionCreated("release-a", 1L, SOURCE_B, 220L));
        assertEquals(MutationStatus.CONFLICT, conflict.status());
        assertEquals("projection_target_uuid_conflict", conflict.detail());
        assertOperationRecord(operations.load("release-a"), OperationState.SPAWN_CLAIMED, true, 1L);
        assertResident(residents.loadById("resident-a"),
                ResidentState.RELEASING, true, 1L, SOURCE_A);

        MutationResult projected = committed(
                operations.markProjectionCreated("release-a", 1L, TARGET_A, 221L));
        assertEquals(MutationStatus.APPLIED, projected.status());
    }

    @Test
    void repositoryReadsSurfaceSqlFailureInsteadOfReturningEmptyState() throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE coop_lifecycle_operations");
        }
        assertThrows(SQLException.class, () -> operations.load("missing"));
        assertThrows(SQLException.class, () -> operations.loadActiveForProfile("profile-a"));
    }

    private void houseResidentA() throws Exception {
        CaptureRequest request = capture("capture-a", "resident-a", "profile-a", SOURCE_A, 0, 0L, 100L);
        committed(operations.prepareCapture(request));
        committed(operations.commitCaptureSlot(request, 0L));
        committed(operations.requestCaptureSourceRetirement("capture-a", 1L, 110L));
        committed(operations.completeCapture("capture-a", 2L, 120L));
    }

    private void releaseResidentA(String operationId, UUID targetUuid, long nowMs) throws Exception {
        ReleaseRequest request = release(operationId, targetUuid, 0L, nowMs);
        committed(operations.prepareRelease(request));
        committed(operations.claimReleaseSpawn(operationId, 0L, nowMs + 1L));
        committed(operations.markProjectionCreated(operationId, 1L, targetUuid, nowMs + 2L));
        committed(operations.finalizeRelease(operationId, 2L, nowMs + 3L));
    }

    private CaptureRequest capture(String operationId,
                                   String residentId,
                                   String profileId,
                                   UUID sourceUuid,
                                   int slot,
                                   long residentGeneration,
                                   long nowMs) {
        return new CaptureRequest(operationId, residentId, COOP, "Coop_Chicken", slot,
                profileId, "Mob_Chicken", sourceUuid, "{\"state\":1}",
                "snapshot-" + operationId, 1, residentGeneration, nowMs);
    }

    private ReleaseRequest release(String operationId,
                                   UUID targetUuid,
                                   long residentGeneration,
                                   long nowMs) {
        return new ReleaseRequest(operationId, "resident-a", COOP, "Coop_Chicken", 0,
                "profile-a", targetUuid, "snapshot-capture-a", residentGeneration, nowMs);
    }

    private <T> T committed(PersistenceWriteQueue.WriteSubmission<T> submission) throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<T> outcome = submission.completion().get(3, TimeUnit.SECONDS);
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status(),
                () -> "write failed: " + outcome.failureReason());
        assertNull(outcome.failure());
        T value = outcome.value();
        assertNotNull(value);
        return value;
    }

    private void assertOperation(MutationResult result,
                                 MutationStatus status,
                                 OperationState state,
                                 boolean active,
                                 long generation) {
        assertEquals(status, result.status());
        assertOperationRecord(result.operation(), state, active, generation);
    }

    private void assertOperationRecord(CoopLifecycleOperationRepository.OperationRecord operation,
                                       OperationState state,
                                       boolean active,
                                       long generation) {
        assertNotNull(operation);
        assertEquals(state, operation.state());
        assertEquals(active, operation.active());
        assertEquals(generation, operation.generation());
        if (active) {
            assertEquals(0L, operation.completedAtMs());
        } else {
            assertFalse(operation.completedAtMs() == 0L);
        }
    }

    private void assertResident(ResidentRecord resident,
                                ResidentState state,
                                boolean active,
                                long generation,
                                UUID residentUuid) {
        assertNotNull(resident);
        assertEquals(state, resident.state());
        assertEquals(active, resident.active());
        assertEquals(generation, resident.generation());
        assertEquals(residentUuid, resident.residentUuid());
    }

    private List<String> activeClaimKinds(String residentId) throws Exception {
        ArrayList<String> kinds = new ArrayList<>();
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT claim_kind FROM managed_coop_uuid_claims
                     WHERE resident_id = ? AND active = 1 ORDER BY claim_kind
                     """)) {
            statement.setString(1, residentId);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    kinds.add(resultSet.getString(1));
                }
            }
        }
        return kinds;
    }

    private void remapProfile(String profileId, UUID previous, UUID current) throws Exception {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement profile = connection.prepareStatement(
                    "UPDATE npc_profiles SET current_npc_uuid = ?, updated_at_ms = ? WHERE profile_id = ?");
                 PreparedStatement oldAlias = connection.prepareStatement(
                         "UPDATE npc_uuid_aliases SET is_current = 0 WHERE npc_uuid = ?");
                 PreparedStatement newAlias = connection.prepareStatement("""
                         INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms)
                         VALUES (?, ?, 1, ?)
                         ON CONFLICT(npc_uuid) DO UPDATE SET
                             profile_id = excluded.profile_id, is_current = 1, mapped_at_ms = excluded.mapped_at_ms
                         """)) {
                profile.setString(1, current.toString());
                profile.setLong(2, 300L);
                profile.setString(3, profileId);
                profile.executeUpdate();
                oldAlias.setString(1, previous.toString());
                oldAlias.executeUpdate();
                newAlias.setString(1, current.toString());
                newAlias.setString(2, profileId);
                newAlias.setLong(3, 300L);
                newAlias.executeUpdate();
                connection.commit();
            }
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

    private static void insertAuthority(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_authority (
                    authority_id, world_name, coop_id, x, y, z, authority_state,
                    active, import_version, created_at_ms, updated_at_ms
                ) VALUES (?, ?, 'coop_chicken', ?, ?, ?, 'TWORK_MANAGED', 1, 0, 1, 1)
                """)) {
            statement.setString(1, COOP.authorityId());
            statement.setString(2, COOP.worldName());
            statement.setInt(3, COOP.x());
            statement.setInt(4, COOP.y());
            statement.setInt(5, COOP.z());
            statement.executeUpdate();
        }
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
