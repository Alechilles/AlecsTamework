package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void prepareAllocatesExactProfileAndRestartCanSeeNonterminalJournal() throws Exception {
        try (Harness harness = harness("prepare.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            PopulationPersistenceTransition.Prepare prepare = prepare(
                    "op-prepare",
                    baseline(profileId, npcUuid, null, 0L, "ACTIVE", "default", 4, -2)
            );

            PopulationPersistenceTransition.Result result = await(harness.repository.prepareAsync(prepare));

            assertEquals(PopulationPersistenceTransition.ResultStatus.PREPARED, result.status());
            List<CompanionPopulationStateRecord> states = harness.repository.loadAllStates();
            assertEquals(1, states.size());
            assertEquals(profileId, states.getFirst().profileId());
            assertEquals(npcUuid, states.getFirst().currentNpcUuid());
            List<CompanionPopulationOperationRecord> operations = harness.repository.loadNonterminalOperations();
            assertEquals(1, operations.size());
            assertEquals("op-prepare", operations.getFirst().operationId());
            assertEquals(CompanionPopulationOperationRecord.State.PREPARED, operations.getFirst().state());
            assertEquals(profileId, profileIdForAlias(harness.connections, npcUuid));
        }
    }

    @Test
    void commitAtomicallySetsOwnerStateRevisionAndJournal() throws Exception {
        try (Harness harness = harness("commit.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            assertTrue(await(harness.repository.prepareAsync(prepare(
                    "op-set",
                    baseline(profileId, npcUuid, null, 0L, "ACTIVE", "default", 1, 2)
            ))).isSuccess());
            assertTrue(await(harness.repository.advanceOperationAsync(
                    "op-set",
                    CompanionPopulationOperationRecord.State.PREPARED,
                    CompanionPopulationOperationRecord.State.APPLYING,
                    null
            )));

            PopulationPersistenceTransition.Result result = await(harness.repository.commitAsync(
                    new PopulationPersistenceTransition.Commit(
                            "op-set",
                            profileId,
                            0L,
                            ProfileOwnerMutation.set(ownerUuid),
                            npcUuid,
                            "default",
                            "ACTIVE",
                            "default",
                            1,
                            2,
                            "tame"
                    )
            ));

            assertEquals(PopulationPersistenceTransition.ResultStatus.COMMITTED, result.status());
            assertEquals(1L, result.revision());
            CompanionPopulationStateRecord state = harness.repository.loadAllStates().getFirst();
            assertEquals(ownerUuid, state.ownerUuid());
            assertEquals(1L, state.revision());
            assertEquals("tame", state.source());
            assertTrue(harness.repository.loadNonterminalOperations().isEmpty());
            assertEquals("COMMITTED", operationState(harness.connections, "op-set"));
        }
    }

    @Test
    void explicitClearWritesSqlNullWhileLegacyNullProfileUpdateStillPreservesOwner() throws Exception {
        try (Harness harness = harness("owner-clear.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            commitOwner(harness, profileId, npcUuid, ownerUuid);

            NpcProfileRepository profiles = new NpcProfileRepository(harness.connections, harness.queue);
            assertTrue(profiles.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid, null, null, "tamed_chicken", "Clucky", null,
                    null, null, null, null, null
            )));
            assertTrue(harness.queue.awaitIdle(2_000L));
            assertEquals(ownerUuid, profiles.loadProfileById(profileId).ownerUuid());

            CompanionPopulationStateRecord current = harness.repository.loadAllStates().getFirst();
            assertTrue(await(harness.repository.prepareAsync(prepare(
                    "op-clear",
                    new CompanionPopulationStateRecord(
                            profileId,
                            npcUuid,
                            ownerUuid,
                            "default",
                            current.ownershipWorldName(),
                            current.lifecycleState(),
                            current.physicalWorldName(),
                            current.physicalChunkX(),
                            current.physicalChunkZ(),
                            current.revision(),
                            current.source(),
                            current.createdAtMs(),
                            System.currentTimeMillis()
                    )
            ))).isSuccess());
            assertTrue(await(harness.repository.advanceOperationAsync(
                    "op-clear",
                    CompanionPopulationOperationRecord.State.PREPARED,
                    CompanionPopulationOperationRecord.State.APPLYING,
                    null
            )));
            PopulationPersistenceTransition.Result cleared = await(harness.repository.commitAsync(
                    new PopulationPersistenceTransition.Commit(
                            "op-clear",
                            profileId,
                            1L,
                            ProfileOwnerMutation.clear(),
                            null,
                            "default",
                            "RELEASED",
                            null,
                            null,
                            null,
                            "owner_clear"
                    )
            ));

            assertEquals(PopulationPersistenceTransition.ResultStatus.COMMITTED, cleared.status());
            assertNull(profiles.loadProfileById(profileId).ownerUuid());
            assertNull(harness.repository.loadAllStates().getFirst().ownerUuid());
            assertEquals(2L, harness.repository.loadAllStates().getFirst().revision());
        }
    }

    @Test
    void staleRevisionAndIdentityCollisionDoNotCreateJournalRows() throws Exception {
        try (Harness harness = harness("conflicts.sqlite")) {
            UUID npcA = UUID.randomUUID();
            UUID npcB = UUID.randomUUID();
            String profileA = UUID.randomUUID().toString();
            String profileB = UUID.randomUUID().toString();
            assertTrue(await(harness.repository.prepareAsync(prepare(
                    "op-a",
                    baseline(profileA, npcA, null, 0L, "ACTIVE", "default", 0, 0)
            ))).isSuccess());
            assertTrue(await(harness.repository.advanceOperationAsync(
                    "op-a",
                    CompanionPopulationOperationRecord.State.PREPARED,
                    CompanionPopulationOperationRecord.State.APPLYING,
                    null
            )));
            assertTrue(await(harness.repository.commitAsync(new PopulationPersistenceTransition.Commit(
                    "op-a", profileA, 0L, ProfileOwnerMutation.unchanged(), npcA,
                    "default", "ACTIVE", "default", 0, 0, "seed"
            ))).isSuccess());

            PopulationPersistenceTransition.Result stale = await(harness.repository.prepareAsync(prepare(
                    "op-stale",
                    baseline(profileA, npcA, null, 0L, "ACTIVE", "default", 0, 0)
            )));
            assertEquals(PopulationPersistenceTransition.ResultStatus.REVISION_CONFLICT, stale.status());

            PopulationPersistenceTransition.Result collision = await(harness.repository.prepareAsync(prepare(
                    "op-collision",
                    baseline(profileB, npcA, null, 0L, "ACTIVE", "default", 5, 5)
            )));
            assertEquals(PopulationPersistenceTransition.ResultStatus.IDENTITY_CONFLICT, collision.status());
            assertEquals(1, harness.repository.loadAllStates().size());
            assertFalse(profileExists(harness.connections, profileB));
            assertTrue(harness.repository.loadNonterminalOperations().isEmpty());
            assertNull(profileIdForAlias(harness.connections, npcB));
        }
    }

    @Test
    void coverageCursorUpsertIsDurableAndReplacesProgress() throws Exception {
        try (Harness harness = harness("coverage.sqlite")) {
            CompanionPopulationCoverageRepository coverage =
                    new CompanionPopulationCoverageRepository(harness.connections, harness.queue);
            CompanionPopulationCoverageRecord first = new CompanionPopulationCoverageRecord(
                    "world:default",
                    CompanionPopulationCoverageRecord.Dimension.WORLD_ENTITIES,
                    "default",
                    "generation-a",
                    CompanionPopulationCoverageRecord.State.RECONCILING,
                    "{\"chunk\":12}",
                    12L,
                    50L,
                    100L,
                    110L,
                    0L,
                    null
            );
            await(coverage.upsertAsync(first));
            CompanionPopulationCoverageRecord ready = new CompanionPopulationCoverageRecord(
                    first.coverageKey(),
                    first.dimension(),
                    first.worldOrSaveId(),
                    first.scanGeneration(),
                    CompanionPopulationCoverageRecord.State.READY,
                    null,
                    50L,
                    50L,
                    first.startedAtMs(),
                    200L,
                    200L,
                    null
            );
            await(coverage.upsertAsync(ready));

            List<CompanionPopulationCoverageRecord> rows = coverage.loadAll();
            assertEquals(1, rows.size());
            assertEquals(CompanionPopulationCoverageRecord.State.READY, rows.getFirst().state());
            assertEquals(50L, rows.getFirst().scannedCount());
            assertNull(rows.getFirst().cursorJson());
        }
    }

    private Harness harness(String filename) throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve(filename));
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            new SqliteSchemaMigrator().migrate(connection);
            connection.commit();
        }
        PersistenceHealthService health = new PersistenceHealthService();
        PersistenceWriteQueue queue = new PersistenceWriteQueue(connections, health, null);
        return new Harness(connections, queue, new CompanionPopulationRepository(connections, queue));
    }

    private static void commitOwner(Harness harness,
                                    String profileId,
                                    UUID npcUuid,
                                    UUID ownerUuid) throws Exception {
        assertTrue(await(harness.repository.prepareAsync(prepare(
                "op-owner",
                baseline(profileId, npcUuid, null, 0L, "ACTIVE", "default", 3, 4)
        ))).isSuccess());
        assertTrue(await(harness.repository.advanceOperationAsync(
                "op-owner",
                CompanionPopulationOperationRecord.State.PREPARED,
                CompanionPopulationOperationRecord.State.APPLYING,
                null
        )));
        assertTrue(await(harness.repository.commitAsync(new PopulationPersistenceTransition.Commit(
                "op-owner", profileId, 0L, ProfileOwnerMutation.set(ownerUuid), npcUuid,
                "default", "ACTIVE", "default", 3, 4, "seed"
        ))).isSuccess());
    }

    private static CompanionPopulationStateRecord baseline(String profileId,
                                                           UUID npcUuid,
                                                           UUID ownerUuid,
                                                           long revision,
                                                           String lifecycle,
                                                           String physicalWorld,
                                                           Integer chunkX,
                                                           Integer chunkZ) {
        long now = System.currentTimeMillis();
        return new CompanionPopulationStateRecord(
                profileId,
                npcUuid,
                ownerUuid,
                physicalWorld,
                physicalWorld,
                lifecycle,
                physicalWorld,
                chunkX,
                chunkZ,
                revision,
                "test",
                now,
                now
        );
    }

    private static PopulationPersistenceTransition.Prepare prepare(
            String operationId,
            CompanionPopulationStateRecord baseline
    ) {
        long now = System.currentTimeMillis();
        return new PopulationPersistenceTransition.Prepare(
                new CompanionPopulationOperationRecord(
                        operationId,
                        baseline.profileId(),
                        "TEST",
                        CompanionPopulationOperationRecord.State.PREPARED,
                        baseline.revision(),
                        "{\"state\":\"old\"}",
                        "{\"state\":\"new\"}",
                        "{\"test\":true}",
                        now,
                        now,
                        0L,
                        null
                ),
                baseline
        );
    }

    private static <T> T await(PersistenceWriteQueue.WriteSubmission<T> submission) throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<T> outcome = submission.completion().get(2, TimeUnit.SECONDS);
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status(), outcome.failureReason());
        return outcome.value();
    }

    private static String operationState(SqliteConnectionManager connections, String operationId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state FROM companion_population_operations WHERE operation_id = ?"
             )) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String profileIdForAlias(SqliteConnectionManager connections, UUID npcUuid) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ?"
             )) {
            statement.setString(1, npcUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static boolean profileExists(SqliteConnectionManager connections, String profileId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM npc_profiles WHERE profile_id = ?"
             )) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private record Harness(SqliteConnectionManager connections,
                           PersistenceWriteQueue queue,
                           CompanionPopulationRepository repository) implements AutoCloseable {
        @Override
        public void close() {
            queue.close();
        }
    }
}
