package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.ActiveOperationsStatus;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.ClaimStatus;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryClaim;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryFinalization;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryState;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.TransitionStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for recovery finalization transaction boundaries and exact replays. */
class NpcRecoveryAtomicFinalizationTest {
    private static final UUID SOURCE_A = uuid(1L);
    private static final UUID SOURCE_B = uuid(2L);
    private static final UUID HISTORICAL_A = uuid(3L);
    private static final UUID TARGET_A = uuid(101L);
    private static final UUID TARGET_B = uuid(102L);

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceHealthService health;
    private PersistenceWriteQueue writeQueue;
    private NpcRecoveryOperationRepository repository;
    private AtomicLong clock;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("recovery-finalize.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            insertProfile(connection, "profile-a", SOURCE_A);
            insertProfile(connection, "profile-b", SOURCE_B);
            insertAwaitingLostState(connection, "profile-a", SOURCE_A);
            insertAwaitingLostState(connection, "profile-b", SOURCE_B);
            insertAlias(connection, "profile-a", SOURCE_A, true);
            insertAlias(connection, "profile-a", HISTORICAL_A, false);
        }
        clock = new AtomicLong(100L);
        health = new PersistenceHealthService();
        writeQueue = new PersistenceWriteQueue(connections, health, null);
        repository = new NpcRecoveryOperationRepository(connections, writeQueue, clock::get);
    }

    @AfterEach
    void tearDown() {
        if (writeQueue != null) {
            writeQueue.close();
        }
    }

    @Test
    void finalizationAtomicallyRemapsIdentityPreservesLostPayloadAndMergesTools() throws Exception {
        insertToolLink("profile-a", "tool-existing", "profile");
        insertToolLink("profile-a", "tool-other-type", "diagnostic");
        claimAndProject("operation-a", "profile-a", SOURCE_A, TARGET_A);
        clock.set(300L);

        var result = committed(repository.finalizeRecovery(finalization(
                "operation-a", "profile-a", SOURCE_A, TARGET_A, 1L,
                List.of("tool-new", " tool-existing ", "tool-new"))));

        assertEquals(TransitionStatus.APPLIED, result.status());
        assertEquals(RecoveryState.FINALIZED, result.operation().state());
        assertFalse(result.operation().active());
        assertEquals(2L, result.operation().generation());
        assertEquals(TARGET_A.toString(), scalarString(
                "SELECT current_npc_uuid FROM npc_profiles WHERE profile_id = 'profile-a'"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM npc_uuid_aliases "
                + "WHERE profile_id = 'profile-a' AND is_current = 1"));
        assertEquals(1, aliasCurrent(TARGET_A));
        assertEquals(0, aliasCurrent(SOURCE_A));
        assertEquals(0, aliasCurrent(HISTORICAL_A));
        assertEquals("keep-me", scalarString("SELECT json_extract(payload_json, '$.marker') "
                + "FROM npc_snapshots WHERE profile_id = 'profile-a' AND is_active = 1"));
        assertEquals(TARGET_A.toString(), scalarString("SELECT json_extract(payload_json, '$.replacementNpcUuid') "
                + "FROM npc_snapshots WHERE profile_id = 'profile-a' AND is_active = 1"));
        assertEquals(300L, scalarLong("SELECT json_extract(payload_json, '$.recoveredAtMs') "
                + "FROM npc_snapshots WHERE profile_id = 'profile-a' AND is_active = 1"));
        assertEquals(1, scalarInt("SELECT lost_active FROM profile_states WHERE profile_id = 'profile-a'"));
        assertEquals(1, toolLinkCount("tool-existing", "profile"));
        assertEquals(1, toolLinkCount("tool-new", "profile"));
        assertEquals(1, toolLinkCount("tool-other-type", "diagnostic"));

        var replay = committed(repository.finalizeRecovery(finalization(
                "operation-a", "profile-a", SOURCE_A, TARGET_A, 1L,
                List.of("tool-existing", "tool-new"))));
        assertEquals(TransitionStatus.REPLAYED, replay.status());
        assertEquals(ActiveOperationsStatus.LOADED, repository.loadAllActive().status());
        assertTrue(repository.loadAllActive().operations().isEmpty());
    }

    @Test
    void projectionRejectsUuidOtherThanTheDurablyClaimedTargetWithoutMutation() throws Exception {
        assertEquals(ClaimStatus.CLAIMED, committed(repository.claim(
                new RecoveryClaim("operation-a", "profile-a", SOURCE_A, TARGET_A))).status());

        var result = committed(repository.recordProjectionCreated(
                "operation-a", "profile-a", TARGET_B, 0L));

        assertEquals(TransitionStatus.TARGET_CONFLICT, result.status());
        var stored = repository.loadByOperationId("operation-a").operation();
        assertEquals(RecoveryState.SPAWN_CLAIMED, stored.state());
        assertEquals(0L, stored.generation());
        assertNull(stored.actualTargetUuid());
    }

    @Test
    void finalizationRevalidatesProfileStateAndTargetOwnership() throws Exception {
        claimAndProject("operation-a", "profile-a", SOURCE_A, TARGET_A);
        execute("UPDATE profile_states SET capture_active = 1 WHERE profile_id = 'profile-a'");

        var captured = committed(repository.finalizeRecovery(finalization(
                "operation-a", "profile-a", SOURCE_A, TARGET_A, 1L, List.of())));
        assertEquals(TransitionStatus.STATE_CONFLICT, captured.status());
        assertProjectionStillPending("operation-a");

        execute("UPDATE profile_states SET capture_active = 0 WHERE profile_id = 'profile-a'");
        insertAliasDirect("profile-b", TARGET_A, false);
        var stolenTarget = committed(repository.finalizeRecovery(finalization(
                "operation-a", "profile-a", SOURCE_A, TARGET_A, 1L, List.of())));
        assertEquals(TransitionStatus.TARGET_CONFLICT, stolenTarget.status());
        assertProjectionStillPending("operation-a");
        assertEquals(SOURCE_A.toString(), scalarString(
                "SELECT current_npc_uuid FROM npc_profiles WHERE profile_id = 'profile-a'"));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM npc_snapshots "
                + "WHERE profile_id = 'profile-a' AND json_extract(payload_json, '$.replacementNpcUuid') IS NOT NULL"));
    }

    @Test
    void sqlFailureAfterOperationUpdateRollsBackEveryFinalizationSideEffect() throws Exception {
        claimAndProject("operation-a", "profile-a", SOURCE_A, TARGET_A);
        execute("DROP TABLE npc_tool_links");

        PersistenceWriteQueue.WriteOutcome<NpcRecoveryOperationRepository.TransitionResult> outcome =
                repository.finalizeRecovery(finalization(
                                "operation-a", "profile-a", SOURCE_A, TARGET_A, 1L, List.of("tool-new")))
                        .completion().get(3, TimeUnit.SECONDS);

        assertEquals(PersistenceWriteQueue.WriteStatus.FAILED, outcome.status());
        assertInstanceOf(java.sql.SQLException.class, outcome.failure());
        assertFalse(health.isHealthy());
        assertProjectionStillPending("operation-a");
        assertEquals(SOURCE_A.toString(), scalarString(
                "SELECT current_npc_uuid FROM npc_profiles WHERE profile_id = 'profile-a'"));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM npc_uuid_aliases WHERE npc_uuid = '" + TARGET_A + "'"));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM npc_snapshots "
                + "WHERE profile_id = 'profile-a' AND json_extract(payload_json, '$.replacementNpcUuid') IS NOT NULL"));
    }

    @Test
    void finalizedReplayRequiresAllDurableStateToStillMatch() throws Exception {
        claimAndProject("operation-a", "profile-a", SOURCE_A, TARGET_A);
        clock.set(300L);
        committed(repository.finalizeRecovery(finalization(
                "operation-a", "profile-a", SOURCE_A, TARGET_A, 1L, List.of("tool-a"))));
        execute("DELETE FROM npc_uuid_aliases WHERE npc_uuid = '" + TARGET_A + "'");

        var replay = committed(repository.finalizeRecovery(finalization(
                "operation-a", "profile-a", SOURCE_A, TARGET_A, 1L, List.of("tool-a"))));

        assertEquals(TransitionStatus.STATE_CONFLICT, replay.status());
        assertEquals(RecoveryState.FINALIZED, replay.operation().state());
    }

    @Test
    void loadAllActiveReturnsTypedRowsAndFailsClosedOnDuplicateProfiles() throws Exception {
        committed(repository.claim(new RecoveryClaim("operation-a", "profile-a", SOURCE_A, TARGET_A)));
        committed(repository.claim(new RecoveryClaim("operation-b", "profile-b", SOURCE_B, TARGET_B)));

        var loaded = repository.loadAllActive();
        assertEquals(ActiveOperationsStatus.LOADED, loaded.status());
        assertEquals(List.of("operation-a", "operation-b"), loaded.operations().stream()
                .map(NpcRecoveryOperationRepository.RecoveryOperation::operationId).toList());

        execute("DROP INDEX uq_recovery_active_profile");
        execute("INSERT INTO npc_recovery_operations (operation_id, profile_id, source_npc_uuid, "
                + "planned_target_uuid, state, active, generation, attempt_count, created_at_ms, updated_at_ms) "
                + "VALUES ('operation-a-duplicate', 'profile-a', '" + SOURCE_A + "', '" + uuid(999L)
                + "', 'SPAWN_CLAIMED', 1, 0, 1, 2, 2)");

        var failed = repository.loadAllActive();
        assertEquals(ActiveOperationsStatus.FAILED, failed.status());
        assertTrue(failed.operations().isEmpty());
        assertEquals(NpcRecoveryOperationRepository.ReadFailureKind.INTEGRITY_VIOLATION,
                failed.failure().kind());
    }

    private void claimAndProject(String operationId,
                                 String profileId,
                                 UUID sourceUuid,
                                 UUID targetUuid) throws Exception {
        assertEquals(ClaimStatus.CLAIMED, committed(repository.claim(
                new RecoveryClaim(operationId, profileId, sourceUuid, targetUuid))).status());
        assertEquals(TransitionStatus.APPLIED, committed(repository.recordProjectionCreated(
                operationId, profileId, targetUuid, 0L)).status());
    }

    private RecoveryFinalization finalization(String operationId,
                                              String profileId,
                                              UUID sourceUuid,
                                              UUID targetUuid,
                                              long generation,
                                              List<String> toolIds) {
        return new RecoveryFinalization(
                operationId, profileId, sourceUuid, targetUuid, targetUuid, generation, toolIds);
    }

    private void assertProjectionStillPending(String operationId) {
        var operation = repository.loadByOperationId(operationId).operation();
        assertEquals(RecoveryState.PROJECTION_CREATED, operation.state());
        assertTrue(operation.active());
        assertEquals(1L, operation.generation());
    }

    private <T> T committed(PersistenceWriteQueue.WriteSubmission<T> submission) throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<T> outcome = submission.completion().get(3, TimeUnit.SECONDS);
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status());
        assertNull(outcome.failure());
        return outcome.value();
    }

    private void insertProfile(Connection connection, String profileId, UUID currentUuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, role_id, created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, 'Mob_Test', 1, 1, 1)
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, currentUuid.toString());
            statement.executeUpdate();
        }
    }

    private void insertAwaitingLostState(Connection connection,
                                         String profileId,
                                         UUID sourceUuid) throws Exception {
        try (PreparedStatement state = connection.prepareStatement("""
                INSERT INTO profile_states (
                    profile_id, capture_active, death_active, lost_active, in_coop, updated_at_ms
                ) VALUES (?, 0, 0, 1, 0, 1)
                """)) {
            state.setString(1, profileId);
            state.executeUpdate();
        }
        try (PreparedStatement snapshot = connection.prepareStatement("""
                INSERT INTO npc_snapshots (
                    profile_id, snapshot_type, snapshot_version, payload_json, is_active, created_at_ms
                ) VALUES (?, 'lost', 1, ?, 1, 1)
                """)) {
            snapshot.setString(1, profileId);
            JsonObject payload = JsonParser.parseString(
                    RecoveryTestEnvelopeFixtures.validEnvelope(sourceUuid)).getAsJsonObject();
            payload.addProperty("marker", "keep-me");
            snapshot.setString(2, payload.toString());
            snapshot.executeUpdate();
        }
    }

    private void insertAlias(Connection connection,
                             String profileId,
                             UUID npcUuid,
                             boolean current) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO npc_uuid_aliases VALUES (?, ?, ?, 1)")) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, profileId);
            statement.setInt(3, current ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void insertAliasDirect(String profileId, UUID npcUuid, boolean current) throws Exception {
        try (Connection connection = connections.openConnection()) {
            insertAlias(connection, profileId, npcUuid, current);
        }
    }

    private void insertToolLink(String profileId, String toolId, String linkType) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO npc_tool_links VALUES (?, ?, ?, 1, 1)")) {
            statement.setString(1, profileId);
            statement.setString(2, toolId);
            statement.setString(3, linkType);
            statement.executeUpdate();
        }
    }

    private int aliasCurrent(UUID npcUuid) throws Exception {
        return scalarInt("SELECT is_current FROM npc_uuid_aliases WHERE npc_uuid = '" + npcUuid + "'");
    }

    private int toolLinkCount(String toolId, String linkType) throws Exception {
        return scalarInt("SELECT COUNT(*) FROM npc_tool_links WHERE profile_id = 'profile-a' "
                + "AND tool_uuid = '" + toolId + "' AND link_type = '" + linkType + "'");
    }

    private String scalarString(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private long scalarLong(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
