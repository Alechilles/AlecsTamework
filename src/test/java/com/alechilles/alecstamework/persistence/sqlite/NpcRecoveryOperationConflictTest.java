package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.ClaimStatus;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryClaim;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.TransitionStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-table conflict regression coverage for recovery claim admission.
 */
class NpcRecoveryOperationConflictTest {
    private static final UUID SOURCE_A = uuid(1L);
    private static final UUID SOURCE_B = uuid(2L);
    private static final UUID TARGET_A = uuid(101L);
    private static final UUID TARGET_B = uuid(102L);

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceHealthService health;
    private PersistenceWriteQueue writeQueue;
    private NpcRecoveryOperationRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("recovery-conflicts.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            insertProfile(connection, "profile-a", SOURCE_A);
            insertProfile(connection, "profile-b", SOURCE_B);
        }
        health = new PersistenceHealthService();
        writeQueue = new PersistenceWriteQueue(connections, health, null);
        AtomicLong clock = new AtomicLong(100L);
        repository = new NpcRecoveryOperationRepository(connections, writeQueue, clock::get);
    }

    @AfterEach
    void tearDown() {
        if (writeQueue != null) {
            writeQueue.close();
        }
    }

    @Test
    void sameOperationReplayWinsBeforeNewProfileAndTargetConflicts() throws Exception {
        RecoveryClaim claim = new RecoveryClaim("operation-a", "profile-a", SOURCE_A, TARGET_A);
        assertEquals(ClaimStatus.CLAIMED, committed(repository.claim(claim)).status());
        try (Connection connection = connections.openConnection()) {
            putProfileState(connection, "profile-a", 1, 0, 1, 0);
            insertAlias(connection, "profile-b", TARGET_A);
        }

        var replayed = committed(repository.claim(claim));

        assertEquals(ClaimStatus.REPLAYED, replayed.status());
        assertEquals("operation-a", replayed.operation().operationId());
        assertEquals(1, recoveryRowCount());
    }

    @Test
    void sourceMappedToDifferentProfileIsRejected() throws Exception {
        var result = committed(repository.claim(
                new RecoveryClaim("source-conflict", "profile-a", SOURCE_B, TARGET_A)));

        assertEquals(ClaimStatus.SOURCE_CONFLICT, result.status());
        assertNull(result.operation());
        assertEquals(0, recoveryRowCount());
    }

    @Test
    void captureDeathAndCoopBlockClaimsButLostOnlyIsAllowed() throws Exception {
        try (Connection connection = connections.openConnection()) {
            putProfileState(connection, "profile-a", 1, 0, 1, 0);
        }
        assertProfileStateConflict("capture-conflict");

        try (Connection connection = connections.openConnection()) {
            putProfileState(connection, "profile-a", 0, 1, 1, 0);
        }
        assertProfileStateConflict("death-conflict");

        try (Connection connection = connections.openConnection()) {
            putProfileState(connection, "profile-a", 0, 0, 1, 1);
        }
        assertProfileStateConflict("coop-conflict");

        try (Connection connection = connections.openConnection()) {
            putProfileState(connection, "profile-a", 0, 0, 1, 0);
        }
        var lostOnly = committed(repository.claim(
                new RecoveryClaim("lost-only", "profile-a", SOURCE_A, TARGET_A)));

        assertEquals(ClaimStatus.CLAIMED, lostOnly.status());
        assertEquals(1, recoveryRowCount());
    }

    @Test
    void activeManagedResidentAndCoopLifecycleOperationBlockClaims() throws Exception {
        try (Connection connection = connections.openConnection()) {
            insertAuthority(connection, "authority-a", 1);
            insertManagedResident(
                    connection, "resident-a", "authority-a", "profile-a", 0,
                    uuid(201L), SOURCE_A, null);
        }
        assertProfileStateConflict("managed-conflict");

        try (Connection connection = connections.openConnection()) {
            execute(connection, "DELETE FROM managed_coop_residents");
            insertCoopLifecycle(
                    connection, "coop-operation-a", "authority-a", "profile-a", 0,
                    null, null, true);
        }
        assertProfileStateConflict("lifecycle-conflict");
        assertEquals(0, recoveryRowCount());
    }

    @Test
    void targetEvidenceAcrossProfilesManagedStateClaimsAndCoopOperationsIsRejected() throws Exception {
        UUID aliasTarget = uuid(301L);
        UUID residentTarget = uuid(302L);
        UUID sourceTarget = uuid(303L);
        UUID deployedTarget = uuid(304L);
        UUID claimTarget = uuid(305L);
        UUID coopPlannedTarget = uuid(306L);
        UUID coopActualTarget = uuid(307L);
        try (Connection connection = connections.openConnection()) {
            insertAlias(connection, "profile-b", aliasTarget);
            insertAuthority(connection, "authority-b", 2);
            insertManagedResident(
                    connection, "resident-b", "authority-b", "profile-b", 0,
                    residentTarget, sourceTarget, deployedTarget);
            insertUuidClaim(connection, "resident-b", claimTarget);
            insertCoopLifecycle(
                    connection, "coop-operation-b", "authority-b", "profile-b", 0,
                    coopPlannedTarget, coopActualTarget, false);
        }

        List<UUID> conflicts = List.of(
                SOURCE_B, aliasTarget, residentTarget, sourceTarget, deployedTarget,
                claimTarget, coopPlannedTarget, coopActualTarget
        );
        int operationNumber = 0;
        for (UUID target : conflicts) {
            var result = committed(repository.claim(new RecoveryClaim(
                    "target-conflict-" + operationNumber++, "profile-a", SOURCE_A, target)));
            assertEquals(ClaimStatus.TARGET_CONFLICT, result.status(), "target=" + target);
            assertNull(result.operation());
        }
        assertEquals(0, recoveryRowCount());
    }

    @Test
    void projectionTargetIsRecheckedAgainstCrossDomainEvidence() throws Exception {
        assertEquals(ClaimStatus.CLAIMED, committed(repository.claim(
                new RecoveryClaim("operation-a", "profile-a", SOURCE_A, TARGET_A))).status());
        try (Connection connection = connections.openConnection()) {
            insertAlias(connection, "profile-b", TARGET_B);
        }

        var result = committed(repository.recordProjectionCreated(
                "operation-a", "profile-a", TARGET_B, 0L));

        assertEquals(TransitionStatus.TARGET_CONFLICT, result.status());
        assertEquals("operation-a", result.operation().operationId());
        assertNull(repository.loadByOperationId("operation-a").operation().actualTargetUuid());
    }

    @Test
    void corruptProfileStateFailsClosedWithoutCreatingAnOperation() throws Exception {
        try (Connection connection = connections.openConnection()) {
            putProfileState(connection, "profile-a", 2, 0, 1, 0);
        }

        PersistenceWriteQueue.WriteOutcome<NpcRecoveryOperationRepository.ClaimResult> outcome =
                repository.claim(new RecoveryClaim(
                                "corrupt-state", "profile-a", SOURCE_A, TARGET_A))
                        .completion().get(3, TimeUnit.SECONDS);

        assertEquals(PersistenceWriteQueue.WriteStatus.FAILED, outcome.status());
        assertInstanceOf(NpcRecoveryOperationTransactions.RepositoryIntegrityException.class,
                outcome.failure());
        assertFalse(health.isHealthy());
        assertEquals(0, recoveryRowCount());
    }

    @Test
    void multipleActiveManagedRowsFailClosedWithoutCreatingAnOperation() throws Exception {
        try (Connection connection = connections.openConnection()) {
            execute(connection, "DROP INDEX uq_managed_resident_active_profile");
            insertAuthority(connection, "authority-a", 1);
            insertAuthority(connection, "authority-b", 2);
            insertManagedResident(
                    connection, "resident-a", "authority-a", "profile-a", 0,
                    uuid(401L), SOURCE_A, null);
            insertManagedResident(
                    connection, "resident-b", "authority-b", "profile-a", 0,
                    uuid(402L), uuid(403L), null);
        }

        PersistenceWriteQueue.WriteOutcome<NpcRecoveryOperationRepository.ClaimResult> outcome =
                repository.claim(new RecoveryClaim(
                                "multiple-managed", "profile-a", SOURCE_A, TARGET_A))
                        .completion().get(3, TimeUnit.SECONDS);

        assertEquals(PersistenceWriteQueue.WriteStatus.FAILED, outcome.status());
        assertInstanceOf(NpcRecoveryOperationTransactions.RepositoryIntegrityException.class,
                outcome.failure());
        assertEquals(0, recoveryRowCount());
    }

    private void assertProfileStateConflict(String operationId) throws Exception {
        var result = committed(repository.claim(
                new RecoveryClaim(operationId, "profile-a", SOURCE_A, TARGET_A)));
        assertEquals(ClaimStatus.PROFILE_STATE_CONFLICT, result.status());
        assertNull(result.operation());
    }

    private <T> T committed(PersistenceWriteQueue.WriteSubmission<T> submission) throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<T> outcome =
                submission.completion().get(3, TimeUnit.SECONDS);
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status());
        assertNull(outcome.failure());
        assertNotNull(outcome.value());
        return outcome.value();
    }

    private int recoveryRowCount() throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT COUNT(*) FROM npc_recovery_operations")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void insertProfile(Connection connection, String profileId, UUID currentUuid)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, role_id,
                    created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, 'Mob_Test', 1, 1, 1)
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, currentUuid.toString());
            statement.executeUpdate();
        }
    }

    private void insertAlias(Connection connection, String profileId, UUID npcUuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO npc_uuid_aliases VALUES (?, ?, 0, 1)")) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, profileId);
            statement.executeUpdate();
        }
    }

    private void putProfileState(Connection connection,
                                 String profileId,
                                 int captured,
                                 int dead,
                                 int lost,
                                 int inCoop) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO profile_states (
                    profile_id, capture_active, death_active, lost_active,
                    in_coop, coop_key, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, NULL, 1)
                ON CONFLICT(profile_id) DO UPDATE SET
                    capture_active = excluded.capture_active,
                    death_active = excluded.death_active,
                    lost_active = excluded.lost_active,
                    in_coop = excluded.in_coop
                """)) {
            statement.setString(1, profileId);
            statement.setInt(2, captured);
            statement.setInt(3, dead);
            statement.setInt(4, lost);
            statement.setInt(5, inCoop);
            statement.executeUpdate();
        }
    }

    private void insertAuthority(Connection connection, String authorityId, int x) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_authority (
                    authority_id, world_name, coop_id, x, y, z, authority_state,
                    active, import_version, created_at_ms, updated_at_ms
                ) VALUES (?, 'world', 'coop', ?, 2, 3, 'TWORK_MANAGED', 1, 1, 1, 1)
                """)) {
            statement.setString(1, authorityId);
            statement.setInt(2, x);
            statement.executeUpdate();
        }
    }

    private void insertManagedResident(Connection connection,
                                       String residentId,
                                       String authorityId,
                                       String profileId,
                                       int slot,
                                       UUID residentUuid,
                                       UUID sourceUuid,
                                       UUID deployedUuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_residents (
                    resident_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                    profile_id, resident_uuid, source_npc_uuid, deployed_npc_uuid,
                    state, generation, active, created_at_ms, updated_at_ms
                ) SELECT ?, authority_id, world_name, coop_id, x, y, z, ?,
                         ?, ?, ?, ?, 'DEPLOYED', 0, 1, 1, 1
                  FROM managed_coop_authority WHERE authority_id = ?
                """)) {
            statement.setString(1, residentId);
            statement.setInt(2, slot);
            statement.setString(3, profileId);
            statement.setString(4, residentUuid.toString());
            statement.setString(5, sourceUuid != null ? sourceUuid.toString() : null);
            statement.setString(6, deployedUuid != null ? deployedUuid.toString() : null);
            statement.setString(7, authorityId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertUuidClaim(Connection connection, String residentId, UUID npcUuid)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_uuid_claims (
                    npc_uuid, resident_id, claim_kind, active, created_at_ms, updated_at_ms
                ) VALUES (?, ?, 'PLANNED', 0, 1, 1)
                """)) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, residentId);
            statement.executeUpdate();
        }
    }

    private void insertCoopLifecycle(Connection connection,
                                     String operationId,
                                     String authorityId,
                                     String profileId,
                                     int slot,
                                     UUID plannedTarget,
                                     UUID actualTarget,
                                     boolean active) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_lifecycle_operations (
                    operation_id, operation_kind, profile_id, authority_id,
                    world_name, coop_id, x, y, z, resident_slot,
                    planned_target_uuid, actual_target_uuid, state, active,
                    created_at_ms, updated_at_ms
                ) SELECT ?, 'RELEASE', ?, authority_id, world_name, coop_id, x, y, z, ?,
                         ?, ?, ?, ?, 1, 1
                  FROM managed_coop_authority WHERE authority_id = ?
                """)) {
            statement.setString(1, operationId);
            statement.setString(2, profileId);
            statement.setInt(3, slot);
            statement.setString(4, plannedTarget != null ? plannedTarget.toString() : null);
            statement.setString(5, actualTarget != null ? actualTarget.toString() : null);
            statement.setString(6, active ? "PREPARED" : "FINALIZED");
            statement.setInt(7, active ? 1 : 0);
            statement.setString(8, authorityId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
