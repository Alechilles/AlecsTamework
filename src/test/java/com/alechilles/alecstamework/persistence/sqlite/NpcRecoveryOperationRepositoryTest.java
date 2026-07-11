package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.ClaimStatus;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.FailureDisposition;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.LoadStatus;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.ReadFailureKind;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryClaim;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryOperation;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryState;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.TransitionStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for duplicate-spawn recovery claims and crash-boundary replays. */
class NpcRecoveryOperationRepositoryTest {
    private static final UUID SOURCE_A = uuid("00000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE_B = uuid("00000000-0000-0000-0000-000000000002");
    private static final UUID TARGET_A = uuid("00000000-0000-0000-0000-000000000101");
    private static final UUID TARGET_B = uuid("00000000-0000-0000-0000-000000000102");
    private static final UUID TARGET_C = uuid("00000000-0000-0000-0000-000000000103");

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceWriteQueue writeQueue;
    private NpcRecoveryOperationRepository repository;
    private AtomicLong clock;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("recovery.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            insertProfile(connection, "profile-a", SOURCE_A);
            insertProfile(connection, "profile-b", SOURCE_B);
        }
        clock = new AtomicLong(100L);
        writeQueue = new PersistenceWriteQueue(connections, new PersistenceHealthService(), null);
        repository = new NpcRecoveryOperationRepository(connections, writeQueue, clock::get);
    }

    @AfterEach
    void tearDown() {
        if (writeQueue != null) {
            writeQueue.close();
        }
    }

    @Test
    void claimIsIdempotentAndAllowsOnlyOneActiveOperationPerProfile() throws Exception {
        RecoveryClaim claim = new RecoveryClaim("operation-a", "profile-a", SOURCE_A, TARGET_A);

        var claimed = committed(repository.claim(claim));
        assertEquals(ClaimStatus.CLAIMED, claimed.status());
        assertRecovery(claimed.operation(), RecoveryState.SPAWN_CLAIMED, true, 0L);
        assertEquals(1, claimed.operation().attemptCount());
        assertEquals(100L, claimed.operation().createdAtMs());

        clock.set(150L);
        var replayed = committed(repository.claim(claim));
        assertEquals(ClaimStatus.REPLAYED, replayed.status());
        assertEquals(100L, replayed.operation().updatedAtMs());

        var changedReplay = committed(repository.claim(
                new RecoveryClaim("operation-a", "profile-a", SOURCE_A, TARGET_B)
        ));
        assertEquals(ClaimStatus.OPERATION_CONFLICT, changedReplay.status());

        var secondOperation = committed(repository.claim(
                new RecoveryClaim("operation-a-2", "profile-a", SOURCE_A, TARGET_B)
        ));
        assertEquals(ClaimStatus.PROFILE_CONFLICT, secondOperation.status());
        assertEquals("operation-a", secondOperation.operation().operationId());
        assertEquals(1, rowCount());
    }

    @Test
    void rejectsCrossProfilePlannedAndActualTargetConflicts() throws Exception {
        assertEquals(ClaimStatus.CLAIMED, committed(repository.claim(
                new RecoveryClaim("operation-a", "profile-a", SOURCE_A, TARGET_A)
        )).status());

        var plannedConflict = committed(repository.claim(
                new RecoveryClaim("operation-b-conflict", "profile-b", SOURCE_B, TARGET_A)
        ));
        assertEquals(ClaimStatus.TARGET_CONFLICT, plannedConflict.status());
        assertEquals("profile-a", plannedConflict.operation().profileId());

        assertEquals(ClaimStatus.CLAIMED, committed(repository.claim(
                new RecoveryClaim("operation-b", "profile-b", SOURCE_B, TARGET_B)
        )).status());
        var actualConflict = committed(repository.recordProjectionCreated(
                "operation-b",
                "profile-b",
                TARGET_A,
                0L
        ));
        assertEquals(TransitionStatus.TARGET_CONFLICT, actualConflict.status());
        assertEquals("operation-a", actualConflict.operation().operationId());

        RecoveryOperation unchanged = repository.loadByOperationId("operation-b").operation();
        assertRecovery(unchanged, RecoveryState.SPAWN_CLAIMED, true, 0L);
        assertNull(unchanged.actualTargetUuid());
    }

    @Test
    void projectionAndFinalizationUseOptimisticGenerationsAndReplaySafely() throws Exception {
        committed(repository.claim(new RecoveryClaim("operation-a", "profile-a", SOURCE_A, TARGET_A)));

        var prematureFinalize = committed(repository.finalizeOperation("operation-a", "profile-a", 0L));
        assertEquals(TransitionStatus.STATE_CONFLICT, prematureFinalize.status());

        var staleProjection = committed(repository.recordProjectionCreated(
                "operation-a",
                "profile-a",
                TARGET_A,
                1L
        ));
        assertEquals(TransitionStatus.GENERATION_CONFLICT, staleProjection.status());

        clock.set(200L);
        var projected = committed(repository.recordProjectionCreated(
                "operation-a",
                "profile-a",
                TARGET_A,
                0L
        ));
        assertEquals(TransitionStatus.APPLIED, projected.status());
        assertRecovery(projected.operation(), RecoveryState.PROJECTION_CREATED, true, 1L);
        assertEquals(TARGET_A, projected.operation().actualTargetUuid());
        assertEquals(200L, projected.operation().updatedAtMs());

        var replayedProjection = committed(repository.recordProjectionCreated(
                "operation-a",
                "profile-a",
                TARGET_A,
                0L
        ));
        assertEquals(TransitionStatus.REPLAYED, replayedProjection.status());
        assertEquals(1L, replayedProjection.operation().generation());

        var staleFinalize = committed(repository.finalizeOperation("operation-a", "profile-a", 0L));
        assertEquals(TransitionStatus.GENERATION_CONFLICT, staleFinalize.status());

        clock.set(300L);
        var finalized = committed(repository.finalizeOperation("operation-a", "profile-a", 1L));
        assertEquals(TransitionStatus.APPLIED, finalized.status());
        assertRecovery(finalized.operation(), RecoveryState.FINALIZED, false, 2L);
        assertEquals(300L, finalized.operation().completedAtMs());

        var replayedFinalize = committed(repository.finalizeOperation("operation-a", "profile-a", 1L));
        assertEquals(TransitionStatus.REPLAYED, replayedFinalize.status());

        clock.set(400L);
        var nextRecovery = committed(repository.claim(
                new RecoveryClaim("operation-a-next", "profile-a", TARGET_A, TARGET_C)
        ));
        assertEquals(ClaimStatus.CLAIMED, nextRecovery.status());
    }

    @Test
    void failOrQuarantineTerminatesExactlyOneGeneration() throws Exception {
        committed(repository.claim(new RecoveryClaim("operation-a", "profile-a", SOURCE_A, TARGET_A)));

        var stale = committed(repository.failOrQuarantine(
                "operation-a",
                "profile-a",
                1L,
                FailureDisposition.QUARANTINED,
                "target_owned_by_other_profile"
        ));
        assertEquals(TransitionStatus.GENERATION_CONFLICT, stale.status());

        clock.set(250L);
        var quarantined = committed(repository.failOrQuarantine(
                "operation-a",
                "profile-a",
                0L,
                FailureDisposition.QUARANTINED,
                "target_owned_by_other_profile"
        ));
        assertEquals(TransitionStatus.APPLIED, quarantined.status());
        assertRecovery(quarantined.operation(), RecoveryState.QUARANTINED, false, 1L);
        assertEquals("target_owned_by_other_profile", quarantined.operation().lastError());
        assertEquals(250L, quarantined.operation().completedAtMs());

        var replayed = committed(repository.failOrQuarantine(
                "operation-a",
                "profile-a",
                0L,
                FailureDisposition.QUARANTINED,
                "target_owned_by_other_profile"
        ));
        assertEquals(TransitionStatus.REPLAYED, replayed.status());

        var wrongTerminal = committed(repository.failOrQuarantine(
                "operation-a",
                "profile-a",
                1L,
                FailureDisposition.FAILED,
                "should_not_replace_quarantine"
        ));
        assertEquals(TransitionStatus.STATE_CONFLICT, wrongTerminal.status());

        committed(repository.claim(new RecoveryClaim("operation-b", "profile-b", SOURCE_B, TARGET_B)));
        var failed = committed(repository.failOrQuarantine(
                "operation-b",
                "profile-b",
                0L,
                FailureDisposition.FAILED,
                "spawn_api_failed"
        ));
        assertRecovery(failed.operation(), RecoveryState.FAILED, false, 1L);
    }

    @Test
    void readsDistinguishMissingInvalidAndSqlFailure() throws Exception {
        assertEquals(LoadStatus.NOT_FOUND, repository.loadByOperationId("missing").status());

        var invalid = repository.loadActiveByProfile(" ");
        assertEquals(LoadStatus.FAILED, invalid.status());
        assertEquals(ReadFailureKind.INVALID_INPUT, invalid.failure().kind());

        committed(repository.claim(new RecoveryClaim("operation-a", "profile-a", SOURCE_A, TARGET_A)));
        var loaded = repository.loadActiveByProfile("profile-a");
        assertEquals(LoadStatus.FOUND, loaded.status());
        assertEquals("operation-a", loaded.operation().operationId());

        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE npc_recovery_operations");
        }
        var failed = repository.loadByOperationId("operation-a");
        assertEquals(LoadStatus.FAILED, failed.status());
        assertEquals(ReadFailureKind.SQL_ERROR, failed.failure().kind());
        assertInstanceOf(SQLException.class, failed.failure().cause());
    }

    private <T> T committed(PersistenceWriteQueue.WriteSubmission<T> submission) throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<T> outcome = submission.completion().get(3, TimeUnit.SECONDS);
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status());
        assertNull(outcome.failureReason());
        assertNull(outcome.failure());
        T value = outcome.value();
        assertNotNull(value);
        return value;
    }

    private void assertRecovery(RecoveryOperation operation,
                                RecoveryState state,
                                boolean active,
                                long generation) {
        assertNotNull(operation);
        assertEquals(state, operation.state());
        assertEquals(active, operation.active());
        assertEquals(generation, operation.generation());
        assertTrue(operation.attemptCount() >= 1);
        assertTrue(operation.createdAtMs() > 0L);
        if (active) {
            assertEquals(0L, operation.completedAtMs());
        } else {
            assertFalse(operation.completedAtMs() == 0L);
        }
    }

    private int rowCount() throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT COUNT(*) FROM npc_recovery_operations")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static void insertProfile(Connection connection,
                                      String profileId,
                                      UUID currentUuid) throws Exception {
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

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
