package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.CompanionSpawnSourceFinalizationContext;
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
    void sourceBearingCommitStaysAppliedUntilIdempotentSourceFinalization() throws Exception {
        try (Harness harness = harness("source-finalization.sqlite")) {
            UUID sourceNpc = UUID.randomUUID();
            UUID replacementNpc = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            String context = CompanionSpawnSourceFinalizationContext.extensionJson(
                    CompanionSpawnSourceFinalizationContext.Kind.DEATH_RECORD,
                    "death-source:" + sourceNpc,
                    sourceNpc,
                    ownerUuid,
                    null,
                    "expected",
                    "replacement"
            );
            assertTrue(await(harness.repository.prepareAsync(prepare(
                    "op-source", baseline(profileId, sourceNpc, ownerUuid,
                            0L, "DEAD_REVIVABLE", null, null, null), context
            ))).isSuccess());
            assertTrue(await(harness.repository.advanceOperationAsync(
                    "op-source",
                    CompanionPopulationOperationRecord.State.PREPARED,
                    CompanionPopulationOperationRecord.State.APPLYING,
                    null
            )));
            PopulationPersistenceTransition.Commit commit = new PopulationPersistenceTransition.Commit(
                    "op-source", profileId, 0L, ProfileOwnerMutation.unchanged(), replacementNpc,
                    "default", "ACTIVE", "default", 2, 3, "dead_restore"
            );

            PopulationPersistenceTransition.Result first = await(
                    harness.repository.commitAsync(commit)
            );
            PopulationPersistenceTransition.Result retry = await(
                    harness.repository.commitAsync(commit)
            );

            assertEquals(
                    PopulationPersistenceTransition.ResultStatus.SOURCE_FINALIZATION_PENDING,
                    first.status()
            );
            assertEquals(
                    PopulationPersistenceTransition.ResultStatus.SOURCE_FINALIZATION_PENDING,
                    retry.status()
            );
            assertEquals(1L, harness.repository.loadAllStates().getFirst().revision());
            assertEquals("APPLIED", operationState(harness.connections, "op-source"));
            assertTrue(await(harness.repository.completeSourceFinalizationAsync("op-source")));
            assertTrue(await(harness.repository.completeSourceFinalizationAsync("op-source")));
            assertEquals("COMMITTED", operationState(harness.connections, "op-source"));
            assertTrue(harness.repository.loadNonterminalOperations().isEmpty());
        }
    }

    @Test
    void breedingReplayLoadRetainsCommittedAndFailedRowsOnlyForBreeding() throws Exception {
        try (Harness harness = harness("breeding-replay.sqlite")) {
            UUID committedNpc = UUID.randomUUID();
            String committedProfile = UUID.randomUUID().toString();
            assertTrue(await(harness.repository.prepareAsync(prepareOfType(
                    "op-breeding-committed",
                    baseline(committedProfile, committedNpc, null, 0L, "ACTIVE", "default", 0, 0),
                    "BREEDING"
            ))).isSuccess());
            assertTrue(await(harness.repository.advanceOperationAsync(
                    "op-breeding-committed",
                    CompanionPopulationOperationRecord.State.PREPARED,
                    CompanionPopulationOperationRecord.State.APPLYING,
                    null
            )));
            assertTrue(await(harness.repository.commitAsync(new PopulationPersistenceTransition.Commit(
                    "op-breeding-committed", committedProfile, 0L,
                    ProfileOwnerMutation.unchanged(), committedNpc,
                    "default", "ACTIVE", "default", 0, 0, "breeding"
            ))).isSuccess());

            UUID failedNpc = UUID.randomUUID();
            String failedProfile = UUID.randomUUID().toString();
            assertTrue(await(harness.repository.prepareAsync(prepareOfType(
                    "op-breeding-failed",
                    baseline(failedProfile, failedNpc, null, 0L, "ACTIVE", "default", 1, 0),
                    "BREEDING"
            ))).isSuccess());
            assertTrue(await(harness.repository.advanceOperationAsync(
                    "op-breeding-failed",
                    CompanionPopulationOperationRecord.State.PREPARED,
                    CompanionPopulationOperationRecord.State.FAILED,
                    "simulated"
            )));

            assertTrue(await(harness.repository.prepareAsync(prepare(
                    "op-not-breeding",
                    baseline(UUID.randomUUID().toString(), UUID.randomUUID(), null,
                            0L, "ACTIVE", "default", 2, 0)
            ))).isSuccess());

            List<CompanionPopulationOperationRecord> rows =
                    harness.repository.loadBreedingOperations();

            assertEquals(2, rows.size());
            assertEquals(List.of(
                    CompanionPopulationOperationRecord.State.COMMITTED,
                    CompanionPopulationOperationRecord.State.FAILED
            ), rows.stream().map(CompanionPopulationOperationRecord::state).toList());
            assertTrue(rows.stream().allMatch(row -> "BREEDING".equals(row.operationType())));
        }
    }

    @Test
    void commitAtomicallyPersistsCoopLedgerSourceWithPopulationState() throws Exception {
        try (Harness harness = harness("coop-atomic.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            String target = ("""
                    {"npcUuid":"%s","coopLedgerMutation":{
                      "mode":"CAPTURE","worldName":"default","coopId":"coop-a",
                      "x":4,"y":5,"z":6,"residentSlot":2,
                      "housedNpcUuid":"%s","lastReleasedNpcUuid":null,
                      "ownerId":"%s","toolIds":["tool-a"],"roleId":"tamed_chicken",
                      "displayName":"Clucky","housedAtMs":100,"releasedAtMs":0,
                      "stateSnapshotJson":"{\\"version\\":1}",
                      "previousNpcUuid":null,"currentNpcUuid":"%s"
                    }}
                    """).formatted(npcUuid, npcUuid, ownerUuid, npcUuid);
            assertTrue(await(harness.repository.prepareAsync(prepare(
                    "op-coop-capture",
                    baseline(profileId, npcUuid, ownerUuid, 0L, "ACTIVE", "default", 0, 0),
                    target
            ))).isSuccess());
            assertTrue(await(harness.repository.advanceOperationAsync(
                    "op-coop-capture",
                    CompanionPopulationOperationRecord.State.PREPARED,
                    CompanionPopulationOperationRecord.State.APPLYING,
                    null
            )));

            PopulationPersistenceTransition.Result result = await(harness.repository.commitAsync(
                    new PopulationPersistenceTransition.Commit(
                            "op-coop-capture", profileId, 0L,
                            ProfileOwnerMutation.unchanged(), npcUuid, "default", "COOP",
                            null, null, null, "coop_capture"
                    )
            ));

            assertEquals(PopulationPersistenceTransition.ResultStatus.COMMITTED, result.status());
            try (Connection connection = harness.connections.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT housed_npc_uuid, profile_id FROM coop_slots WHERE coop_id = 'coop-a'"
                 ); ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(npcUuid.toString(), rows.getString(1));
                assertEquals(profileId, rows.getString(2));
            }
            assertEquals("COOP", harness.repository.loadAllStates().getFirst().lifecycleState());
            assertEquals("COMMITTED", operationState(harness.connections, "op-coop-capture"));
        }
    }

    @Test
    void releaseAtomicallyRemapsCoopLedgerIdentityAndPopulationState() throws Exception {
        try (Harness harness = harness("coop-atomic-release.sqlite")) {
            UUID previousNpcUuid = UUID.randomUUID();
            UUID currentNpcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            String captureTarget = ("""
                    {"npcUuid":"%s","coopLedgerMutation":{
                      "mode":"CAPTURE","worldName":"default","coopId":"coop-a",
                      "x":4,"y":5,"z":6,"residentSlot":2,
                      "housedNpcUuid":"%s","lastReleasedNpcUuid":null,
                      "ownerId":"%s","toolIds":["tool-a"],"roleId":"tamed_chicken",
                      "displayName":"Clucky","housedAtMs":100,"releasedAtMs":0,
                      "stateSnapshotJson":"{\\"version\\":1}",
                      "previousNpcUuid":null,"currentNpcUuid":"%s"
                    }}
                    """).formatted(previousNpcUuid, previousNpcUuid, ownerUuid, previousNpcUuid);
            assertTrue(await(harness.repository.prepareAsync(prepare(
                    "op-coop-seed",
                    baseline(profileId, previousNpcUuid, ownerUuid, 0L,
                            "ACTIVE", "default", 0, 0),
                    captureTarget
            ))).isSuccess());
            assertTrue(await(harness.repository.advanceOperationAsync(
                    "op-coop-seed",
                    CompanionPopulationOperationRecord.State.PREPARED,
                    CompanionPopulationOperationRecord.State.APPLYING,
                    null
            )));
            assertTrue(await(harness.repository.commitAsync(
                    new PopulationPersistenceTransition.Commit(
                            "op-coop-seed", profileId, 0L,
                            ProfileOwnerMutation.unchanged(), previousNpcUuid,
                            "default", "COOP", null, null, null, "coop_capture"
                    )
            )).isSuccess());

            CompanionPopulationStateRecord cooped = harness.repository.loadAllStates().getFirst();
            String releaseTarget = ("""
                    {"operation":"coop_release","idempotencyKey":"coop-release-test",
                     "previousNpcUuid":"%s","plannedNpcUuid":"%s",
                     "world":"default","chunkX":4,"chunkZ":5,
                     "coopLedgerMutation":{
                      "mode":"RELEASE","worldName":"default","coopId":"coop-a",
                      "x":4,"y":5,"z":6,"residentSlot":2,
                      "housedNpcUuid":null,"lastReleasedNpcUuid":"%s",
                      "ownerId":"%s","toolIds":["tool-a"],"roleId":"tamed_chicken",
                      "displayName":"Clucky","housedAtMs":100,"releasedAtMs":200,
                       "stateSnapshotJson":"{\\"version\\":1}",
                      "previousNpcUuid":"%s","currentNpcUuid":"%s"
                    }}
                    """).formatted(
                    previousNpcUuid, currentNpcUuid, currentNpcUuid, ownerUuid,
                    previousNpcUuid, currentNpcUuid
            );
            assertTrue(await(harness.repository.prepareAsync(prepare(
                    "op-coop-release", cooped, releaseTarget
            ))).isSuccess());
            assertTrue(await(harness.repository.advanceOperationAsync(
                    "op-coop-release",
                    CompanionPopulationOperationRecord.State.PREPARED,
                    CompanionPopulationOperationRecord.State.APPLYING,
                    null
            )));

            PopulationPersistenceTransition.Result result = await(
                    harness.repository.commitAsync(new PopulationPersistenceTransition.Commit(
                            "op-coop-release", profileId, 1L,
                            ProfileOwnerMutation.unchanged(), currentNpcUuid,
                            "default", "ACTIVE", "default", 4, 5, "coop_release"
                    ))
            );

            assertEquals(PopulationPersistenceTransition.ResultStatus.COMMITTED, result.status());
            CompanionPopulationStateRecord active = harness.repository.loadAllStates().getFirst();
            assertEquals(currentNpcUuid, active.currentNpcUuid());
            assertEquals("ACTIVE", active.lifecycleState());
            assertEquals(2L, active.revision());
            try (Connection connection = harness.connections.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT housed_npc_uuid, last_released_npc_uuid, profile_id "
                                 + "FROM coop_slots WHERE coop_id = 'coop-a'"
                 ); ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertNull(rows.getString(1));
                assertEquals(currentNpcUuid.toString(), rows.getString(2));
                assertEquals(profileId, rows.getString(3));
            }
            assertEquals(profileId, profileIdForAlias(harness.connections, previousNpcUuid));
            assertEquals(profileId, profileIdForAlias(harness.connections, currentNpcUuid));
            assertEquals("COMMITTED", operationState(harness.connections, "op-coop-release"));
        }
    }

    @Test
    void invalidCoopSideEffectRollsBackPopulationAndJournalCommit() throws Exception {
        try (Harness harness = harness("coop-atomic-rollback.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            String invalidTarget = ("""
                    {"npcUuid":"%s","coopLedgerMutation":{
                      "mode":"CAPTURE","worldName":"default","coopId":"coop-a",
                      "x":0,"y":0,"z":0,"residentSlot":0,
                      "housedNpcUuid":null,"lastReleasedNpcUuid":null,"ownerId":null,
                      "toolIds":[],"roleId":"role","displayName":null,
                      "housedAtMs":1,"releasedAtMs":0,"stateSnapshotJson":null,
                      "previousNpcUuid":null,"currentNpcUuid":"%s"
                    }}
                    """).formatted(npcUuid, npcUuid);
            assertTrue(await(harness.repository.prepareAsync(prepare(
                    "op-coop-invalid",
                    baseline(profileId, npcUuid, null, 0L, "ACTIVE", "default", 0, 0),
                    invalidTarget
            ))).isSuccess());
            assertTrue(await(harness.repository.advanceOperationAsync(
                    "op-coop-invalid",
                    CompanionPopulationOperationRecord.State.PREPARED,
                    CompanionPopulationOperationRecord.State.APPLYING,
                    null
            )));

            PersistenceWriteQueue.WriteOutcome<PopulationPersistenceTransition.Result> outcome =
                    harness.repository.commitAsync(new PopulationPersistenceTransition.Commit(
                            "op-coop-invalid", profileId, 0L, ProfileOwnerMutation.unchanged(),
                            npcUuid, "default", "COOP", null, null, null, "coop_capture"
                    )).completion().get(2, TimeUnit.SECONDS);

            assertFalse(outcome.isCommitted());
            assertEquals(0L, harness.repository.loadAllStates().getFirst().revision());
            assertEquals("ACTIVE", harness.repository.loadAllStates().getFirst().lifecycleState());
            assertEquals("APPLYING", operationState(harness.connections, "op-coop-invalid"));
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
                    npcUuid, ownerUuid, "Original owner", "tamed_chicken", "Clucky", null,
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
            assertNull(profiles.loadProfileById(profileId).ownerName());
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
        return prepare(operationId, baseline, "{\"test\":true}");
    }

    private static PopulationPersistenceTransition.Prepare prepare(
            String operationId,
            CompanionPopulationStateRecord baseline,
            String targetContextJson
    ) {
        return prepareOfType(operationId, baseline, targetContextJson, "TEST");
    }

    private static PopulationPersistenceTransition.Prepare prepareOfType(
            String operationId,
            CompanionPopulationStateRecord baseline,
            String operationType
    ) {
        return prepareOfType(operationId, baseline, "{\"test\":true}", operationType);
    }

    private static PopulationPersistenceTransition.Prepare prepareOfType(
            String operationId,
            CompanionPopulationStateRecord baseline,
            String targetContextJson,
            String operationType
    ) {
        long now = System.currentTimeMillis();
        return new PopulationPersistenceTransition.Prepare(
                new CompanionPopulationOperationRecord(
                        operationId,
                        baseline.profileId(),
                        operationType,
                        CompanionPopulationOperationRecord.State.PREPARED,
                        baseline.revision(),
                        "{\"state\":\"old\"}",
                        "{\"state\":\"new\"}",
                        targetContextJson,
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
