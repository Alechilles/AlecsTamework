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
}
