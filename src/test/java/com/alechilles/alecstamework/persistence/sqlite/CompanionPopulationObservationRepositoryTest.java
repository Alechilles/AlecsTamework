package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationObservation;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationObservationPersistResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationObservationRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void existingLegacyProfileWithoutPopulationStateReceivesObservationState() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("observation.sqlite"));
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO npc_profiles (
                        profile_id, current_npc_uuid, owner_uuid, last_world_name,
                        created_at_ms, updated_at_ms, last_active_at_ms
                    ) VALUES ('profile', ?, ?, 'old-world', 1, 1, 1)
                    """)) {
                statement.setString(1, npcUuid.toString());
                statement.setString(2, ownerUuid.toString());
                statement.executeUpdate();
            }
        }
        PersistenceWriteQueue queue = new PersistenceWriteQueue(
                connections,
                new PersistenceHealthService(),
                null
        );
        try {
            CompanionPopulationObservationPersistResult result =
                    new CompanionPopulationObservationRepository(queue).persistAsync(
                            new CompanionPopulationObservation(
                                    "profile",
                                    npcUuid,
                                    ownerUuid,
                                    "default",
                                    CompanionLifecycleState.ACTIVE,
                                    "default",
                                    2,
                                    -3,
                                    0L,
                                    "test-observation"
                            )
                    ).get(2, TimeUnit.SECONDS);

            assertEquals(CompanionPopulationObservationPersistResult.Status.CREATED, result.status());
            CompanionPopulationStateRecord state =
                    new CompanionPopulationRepository(connections, queue).loadAllStates().getFirst();
            assertEquals(CompanionLifecycleState.ACTIVE.name(), state.lifecycleState());
            assertEquals("default", state.ownershipWorldName());
            assertEquals(2, state.physicalChunkX());
            assertEquals(-3, state.physicalChunkZ());
            assertEquals(0L, state.revision());
        } finally {
            queue.close();
        }
    }

    @Test
    void exhaustedSqliteBusyOutcomeMapsToRetryableObservationFailure() {
        CompanionPopulationObservation observation = new CompanionPopulationObservation(
                "profile",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "default",
                CompanionLifecycleState.ACTIVE,
                "default",
                1,
                2,
                3L,
                "test"
        );
        PersistenceWriteQueue.WriteOutcome<CompanionPopulationObservationPersistResult> outcome =
                new PersistenceWriteQueue.WriteOutcome<>(
                        PersistenceWriteQueue.WriteStatus.FAILED,
                        null,
                        "sqlite_write_failed:companion_population_live_observation:SQLiteException",
                        new IllegalStateException("[SQLITE_BUSY] database is locked")
                );

        CompanionPopulationObservationPersistResult result =
                CompanionPopulationObservationRepository.resultFromOutcome(observation, outcome);

        assertEquals(CompanionPopulationObservationPersistResult.Status.TRANSIENT_FAILURE,
                result.status());
        assertEquals(3L, result.revision());
        assertTrue(result.retryable());
    }

    @Test
    void bondedDeathAdvancesPopulationAndBindingGenerationAtomically() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("bonded-death.sqlite"));
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        String bindingId = UUID.randomUUID().toString();
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            try (PreparedStatement profile = connection.prepareStatement("""
                    INSERT INTO npc_profiles (
                        profile_id, current_npc_uuid, owner_uuid, role_id, last_world_name,
                        created_at_ms, updated_at_ms, last_active_at_ms
                    ) VALUES ('profile-dragon', ?, ?, 'Tamed_NordicDrake', 'default', 1, 1, 1)
                    """);
                 PreparedStatement population = connection.prepareStatement("""
                    INSERT INTO companion_population_state (
                        profile_id, ownership_world_name, lifecycle_state, physical_world_name,
                        physical_chunk_x, physical_chunk_z, revision, source,
                        created_at_ms, updated_at_ms
                    ) VALUES ('profile-dragon', 'default', 'ACTIVE', 'default', 1, 2, 0,
                        'test', 1, 1)
                    """)) {
                profile.setString(1, npcUuid.toString());
                profile.setString(2, ownerUuid.toString());
                profile.executeUpdate();
                population.executeUpdate();
            }
        }
        PersistenceWriteQueue queue = new PersistenceWriteQueue(
                connections, new PersistenceHealthService(), null);
        try {
            BondedVesselRepository vessels = new BondedVesselRepository(connections, queue);
            long now = 10L;
            BondedVesselBindingRecord binding = new BondedVesselBindingRecord(
                    bindingId, "profile-dragon", 1L, "hydragon:stone", 1L,
                    BondedVesselBindingRecord.LifecycleState.ACTIVE,
                    BondedVesselBindingRecord.ItemProjectionStatus.PRESENT, ownerUuid, 0L,
                    npcUuid, new BondedVesselBindingRecord.PhysicalLocation("default", 1, 2),
                    0L, "Draconic_Stone_Active", "{}", null, null, 0L, now, now, 0L);
            BondedVesselOperationRecord initial = new BondedVesselOperationRecord(
                    UUID.randomUUID().toString(), "test", "initial", null, bindingId,
                    "profile-dragon", BondedVesselOperationRecord.Action.INITIAL_BIND,
                    BondedVesselOperationRecord.State.COMMITTED, 0L, 1L, 0L,
                    "hydragon:stone", 1L, BondedVesselBindingRecord.LifecycleState.STORED,
                    BondedVesselBindingRecord.LifecycleState.STORING,
                    BondedVesselBindingRecord.LifecycleState.ACTIVE,
                    BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                    BondedVesselBindingRecord.ItemProjectionStatus.PRESENT, 0L, 0L,
                    "Draconic_Stone", "Draconic_Stone_Active", "source", "target",
                    "{}", "{}", null, npcUuid, "initial", "COMMITTED", 0L,
                    now, now, now, now);
            assertTrue(vessels.createInitialBindingAsync(binding, initial)
                    .completion().get(2, TimeUnit.SECONDS).isCommitted());

            CompanionPopulationObservationPersistResult result =
                    new CompanionPopulationObservationRepository(queue).persistAsync(
                            new CompanionPopulationObservation(
                                    "profile-dragon", npcUuid, ownerUuid, "default",
                                    CompanionLifecycleState.DEAD_REVIVABLE,
                                    null, null, null, 0L, "ecs-death-component"))
                            .get(2, TimeUnit.SECONDS);

            assertEquals(CompanionPopulationObservationPersistResult.Status.COMMITTED,
                    result.status());
            BondedVesselBindingRecord committed = vessels.findBinding(bindingId);
            assertEquals(BondedVesselBindingRecord.LifecycleState.DEAD,
                    committed.lifecycleState());
            assertEquals(BondedVesselBindingRecord.ItemProjectionStatus.REISSUE_PENDING,
                    committed.itemProjectionStatus());
            assertEquals(2L, committed.generation());
            assertEquals(1L, committed.expectedProfileRevision());
            assertEquals(null, committed.activeNpcUuid());
            CompanionPopulationStateRecord state =
                    new CompanionPopulationRepository(connections, queue).loadAllStates().getFirst();
            assertEquals(CompanionLifecycleState.DEAD_REVIVABLE.name(), state.lifecycleState());
            assertEquals(1L, state.revision());
        } finally {
            queue.close();
        }
    }
}
