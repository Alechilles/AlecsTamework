package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidence;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidenceSet;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationRepairRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void copiedCapturedItemsAndSavedEntityMergeIntoOnePhysicalCanonicalProfile() throws Exception {
        try (Harness harness = harness("dedupe.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            CompanionPopulationEvidenceSet evidence = new CompanionPopulationEvidenceSet(List.of(
                    physical("entity", npcUuid, ownerUuid, "default", 2, 3),
                    captured("copy-a", npcUuid, ownerUuid),
                    captured("copy-b", npcUuid, ownerUuid)
            ));

            CompanionPopulationRepairRepository.RepairResult result = harness.repair.mergeAsync(evidence)
                    .completion().get(2, TimeUnit.SECONDS).value();

            assertTrue(result.merged());
            assertEquals(1, result.insertedProfiles());
            assertEquals(2, result.duplicateObservations());
            List<CompanionPopulationStateRecord> states = harness.population.loadAllStates();
            assertEquals(1, states.size());
            assertEquals(ownerUuid, states.getFirst().ownerUuid());
            assertEquals(CompanionLifecycleState.UNLOADED.name(), states.getFirst().lifecycleState());
            assertEquals("default", states.getFirst().physicalWorldName());
            assertEquals(2, states.getFirst().physicalChunkX());
            assertEquals(3, states.getFirst().physicalChunkZ());
        }
    }

    @Test
    void savedCorpseRepairsToDeadRevivableWithoutClaimOccupancy() throws Exception {
        try (Harness harness = harness("dead-physical.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            CompanionPopulationRepairRepository.RepairResult result = harness.repair.mergeAsync(
                    new CompanionPopulationEvidenceSet(List.of(
                            deadPhysical("corpse", npcUuid, ownerUuid, "default", 2, 3)
                    ))
            ).completion().get(2, TimeUnit.SECONDS).value();

            assertTrue(result.merged());
            CompanionPopulationStateRecord state = harness.population.loadAllStates().getFirst();
            assertEquals(ownerUuid, state.ownerUuid());
            assertEquals(CompanionLifecycleState.DEAD_REVIVABLE.name(), state.lifecycleState());
            assertEquals("default", state.physicalWorldName());
            ClaimChunkCoordinate chunk = new ClaimChunkCoordinate("default", 2, 3);
            ClaimOccupancyIndex claimIndex = new ClaimOccupancyIndex();
            claimIndex.replaceCommittedEntries(List.of(new ClaimOccupancyEntry(
                    state.profileId(), state.ownerUuid(), CompanionLifecycleState.DEAD_REVIVABLE,
                    chunk, state.revision()
            )), ClaimOccupancyReadiness.READY);
            assertEquals(0, claimIndex.snapshot().occupiedProfileCount());
            assertFalse(claimIndex.snapshot().profilesByChunk().containsKey(chunk));
        }
    }

    @Test
    void ownerlessCorpseCannotReopenAnExplicitlyReleasedProfile() throws Exception {
        try (Harness harness = harness("released-corpse.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            insertReleasedProfile(harness.connections, npcUuid);

            CompanionPopulationRepairRepository.RepairResult result = harness.repair.mergeAsync(
                    new CompanionPopulationEvidenceSet(List.of(
                            deadPhysical("corpse", npcUuid, null, "default", 2, 3)
                    ))
            ).completion().get(2, TimeUnit.SECONDS).value();

            assertTrue(result.merged());
            CompanionPopulationStateRecord state = harness.population.loadAllStates().getFirst();
            assertNull(state.ownerUuid());
            assertEquals(CompanionLifecycleState.RELEASED.name(), state.lifecycleState());
            assertNull(state.physicalWorldName());
        }
    }

    @Test
    void directUnownedPhysicalObservationNeverReleasesAnExistingDurableSlot() throws Exception {
        try (Harness harness = harness("preserve-owner.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            CompanionPopulationRepairRepository.RepairResult first = harness.repair.mergeAsync(
                    new CompanionPopulationEvidenceSet(List.of(captured("captured", npcUuid, ownerUuid)))
            ).completion().get(2, TimeUnit.SECONDS).value();
            assertTrue(first.merged());

            CompanionPopulationRepairRepository.RepairResult second = harness.repair.mergeAsync(
                    new CompanionPopulationEvidenceSet(List.of(
                            physical("entity", npcUuid, null, "default", 0, 0)
                    ))
            ).completion().get(2, TimeUnit.SECONDS).value();

            assertTrue(second.merged());
            CompanionPopulationStateRecord durable = harness.population.loadAllStates().getFirst();
            assertEquals(ownerUuid, durable.ownerUuid());
            assertEquals("default", durable.ownershipWorldName());
        }
    }

    @Test
    void capturedOwnerWithoutAuthoritativeWorldRemainsVisibleAsPerWorldUncertainty() throws Exception {
        try (Harness harness = harness("unknown-world.sqlite")) {
            CompanionPopulationRepairRepository.RepairResult result = harness.repair.mergeAsync(
                    new CompanionPopulationEvidenceSet(List.of(
                            captured("captured", UUID.randomUUID(), UUID.randomUUID())
                    ))
            ).completion().get(2, TimeUnit.SECONDS).value();

            assertTrue(result.merged());
            assertEquals("owned-profiles-have-unknown-world", result.reason());
            assertNull(harness.population.loadAllStates().getFirst().ownershipWorldName());
        }
    }

    @Test
    void conflictingOwnersAbortTheWholeRepairTransaction() throws Exception {
        try (Harness harness = harness("conflict.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            CompanionPopulationEvidenceSet evidence = new CompanionPopulationEvidenceSet(List.of(
                    captured("a", npcUuid, UUID.randomUUID()),
                    captured("b", npcUuid, UUID.randomUUID())
            ));

            CompanionPopulationRepairRepository.RepairResult result = harness.repair.mergeAsync(evidence)
                    .completion().get(2, TimeUnit.SECONDS).value();

            assertFalse(result.merged());
            assertEquals("reconciliation-evidence-conflict", result.reason());
            assertTrue(harness.population.loadAllStates().isEmpty());
        }
    }

    @Test
    void explicitLegacySnapshotsPreserveTheirDistinctDormantLifecycle() throws Exception {
        try (Harness harness = harness("legacy-lifecycles.sqlite")) {
            UUID ownerUuid = UUID.randomUUID();
            UUID capturedUuid = UUID.randomUUID();
            UUID deathUuid = UUID.randomUUID();
            UUID lostUuid = UUID.randomUUID();
            UUID coopUuid = UUID.randomUUID();
            CompanionPopulationEvidenceSet evidence = new CompanionPopulationEvidenceSet(List.of(
                    dormant("capture", capturedUuid, ownerUuid,
                            CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT),
                    dormant("death", deathUuid, ownerUuid,
                            CompanionPopulationEvidence.Kind.DEATH_SNAPSHOT),
                    dormant("lost", lostUuid, ownerUuid,
                            CompanionPopulationEvidence.Kind.LOST_SNAPSHOT),
                    dormant("coop", coopUuid, ownerUuid,
                            CompanionPopulationEvidence.Kind.COOP_SNAPSHOT)
            ));

            CompanionPopulationRepairRepository.RepairResult result = harness.repair.mergeAsync(evidence)
                    .completion().get(2, TimeUnit.SECONDS).value();

            assertTrue(result.merged());
            Map<UUID, CompanionPopulationStateRecord> states = harness.population.loadAllStates().stream()
                    .collect(Collectors.toMap(
                            CompanionPopulationStateRecord::currentNpcUuid,
                            Function.identity()
                    ));
            assertEquals(CompanionLifecycleState.CAPTURED.name(), states.get(capturedUuid).lifecycleState());
            assertEquals(CompanionLifecycleState.DEAD_REVIVABLE.name(), states.get(deathUuid).lifecycleState());
            assertEquals(CompanionLifecycleState.LOST.name(), states.get(lostUuid).lifecycleState());
            assertEquals(CompanionLifecycleState.COOP.name(), states.get(coopUuid).lifecycleState());
        }
    }

    @Test
    void dormantProfileWithoutPopulationStateReceivesUnknownDormantObservationState() throws Exception {
        try (Harness harness = harness("profile-only.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            CompanionPopulationRepairRepository.RepairResult result = harness.repair.mergeAsync(
                    new CompanionPopulationEvidenceSet(List.of(
                            dormant("profile", npcUuid, ownerUuid,
                                    CompanionPopulationEvidence.Kind.PROFILE_RECORD)
                    ))
            ).completion().get(2, TimeUnit.SECONDS).value();

            assertTrue(result.merged());
            CompanionPopulationStateRecord state = harness.population.loadAllStates().getFirst();
            assertEquals(CompanionLifecycleState.UNKNOWN_DORMANT.name(), state.lifecycleState());
            assertNull(state.physicalWorldName());
        }
    }

    @Test
    void restoredPhysicalUuidSupersedesStaleDormantAliasEvidenceForTheSameProfile() throws Exception {
        try (Harness harness = harness("restore-stale-source.sqlite")) {
            UUID oldUuid = UUID.randomUUID();
            UUID restoredUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            try (Connection connection = harness.connections.openConnection()) {
                try (PreparedStatement profile = connection.prepareStatement("""
                        INSERT INTO npc_profiles (
                            profile_id, current_npc_uuid, owner_uuid, last_world_name,
                            created_at_ms, updated_at_ms, last_active_at_ms
                        ) VALUES ('profile', ?, ?, 'default', 1, 1, 1)
                        """)) {
                    profile.setString(1, restoredUuid.toString());
                    profile.setString(2, ownerUuid.toString());
                    profile.executeUpdate();
                }
                try (PreparedStatement aliases = connection.prepareStatement("""
                        INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms)
                        VALUES (?, 'profile', ?, 1)
                        """)) {
                    aliases.setString(1, oldUuid.toString());
                    aliases.setInt(2, 0);
                    aliases.executeUpdate();
                    aliases.setString(1, restoredUuid.toString());
                    aliases.setInt(2, 1);
                    aliases.executeUpdate();
                }
                try (PreparedStatement state = connection.prepareStatement("""
                        INSERT INTO companion_population_state (
                            profile_id, ownership_world_name, lifecycle_state, physical_world_name,
                            physical_chunk_x, physical_chunk_z, revision, source, created_at_ms, updated_at_ms
                        ) VALUES ('profile', 'default', 'UNLOADED', 'default', 4, 5, 1,
                                  'startup-operation-recovery', 1, 1)
                        """)) {
                    state.executeUpdate();
                }
            }
            CompanionPopulationEvidenceSet evidence = new CompanionPopulationEvidenceSet(List.of(
                    physical("restored", restoredUuid, ownerUuid, "default", 4, 5),
                    dormant("stale-capture", oldUuid, ownerUuid,
                            CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT)
            ));

            CompanionPopulationRepairRepository.RepairResult result = harness.repair.mergeAsync(evidence)
                    .completion().get(2, TimeUnit.SECONDS).value();

            assertTrue(result.merged());
            CompanionPopulationStateRecord state = harness.population.loadAllStates().getFirst();
            assertEquals(restoredUuid, state.currentNpcUuid());
            assertEquals(CompanionLifecycleState.UNLOADED.name(), state.lifecycleState());
            assertEquals("default", state.physicalWorldName());
        }
    }

    @Test
    void finalizedLostRecoverySourceDoesNotDegradeCurrentPhysicalProjection() throws Exception {
        try (Harness harness = harness("finalized-recovery-source.sqlite")) {
            UUID staleSourceUuid = UUID.randomUUID();
            UUID currentUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            insertFinalizedRecoveryProfile(
                    harness.connections, staleSourceUuid, currentUuid, ownerUuid);
            CompanionPopulationEvidenceSet evidence = new CompanionPopulationEvidenceSet(List.of(
                    physical("stale-source", staleSourceUuid, ownerUuid, "flat_world", 1, 2),
                    physical("current", currentUuid, ownerUuid, "default", 4, 5)
            ));

            CompanionPopulationRepairRepository.RepairResult result = harness.repair.mergeAsync(evidence)
                    .completion().get(2, TimeUnit.SECONDS).value();

            assertTrue(result.merged());
            assertNull(result.reason());
            CompanionPopulationStateRecord state = harness.population.loadAllStates().getFirst();
            assertEquals(currentUuid, state.currentNpcUuid());
            assertEquals(CompanionLifecycleState.UNLOADED.name(), state.lifecycleState());
            assertEquals("default", state.physicalWorldName());
            assertEquals(4, state.physicalChunkX());
            assertEquals(5, state.physicalChunkZ());
        }
    }

    private Harness harness(String file) throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve(file));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        PersistenceHealthService health = new PersistenceHealthService();
        PersistenceWriteQueue queue = new PersistenceWriteQueue(connections, health, null);
        return new Harness(
                connections,
                queue,
                new CompanionPopulationRepairRepository(queue),
                new CompanionPopulationRepository(connections, queue)
        );
    }

    private static void insertReleasedProfile(SqliteConnectionManager connections, UUID npcUuid)
            throws Exception {
        try (Connection connection = connections.openConnection()) {
            try (PreparedStatement profile = connection.prepareStatement("""
                    INSERT INTO npc_profiles (
                        profile_id, current_npc_uuid, owner_uuid, last_world_name,
                        created_at_ms, updated_at_ms, last_active_at_ms
                    ) VALUES ('profile', ?, NULL, 'default', 1, 1, 1)
                    """)) {
                profile.setString(1, npcUuid.toString());
                profile.executeUpdate();
            }
            try (PreparedStatement alias = connection.prepareStatement("""
                    INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms)
                    VALUES (?, 'profile', 1, 1)
                    """)) {
                alias.setString(1, npcUuid.toString());
                alias.executeUpdate();
            }
            try (PreparedStatement state = connection.prepareStatement("""
                    INSERT INTO companion_population_state (
                        profile_id, ownership_world_name, lifecycle_state, physical_world_name,
                        physical_chunk_x, physical_chunk_z, revision, source, created_at_ms, updated_at_ms
                    ) VALUES ('profile', 'default', 'RELEASED', NULL, NULL, NULL, 1, 'test', 1, 1)
                    """)) {
                state.executeUpdate();
            }
        }
    }

    private static void insertFinalizedRecoveryProfile(
            SqliteConnectionManager connections,
            UUID staleSourceUuid,
            UUID currentUuid,
            UUID ownerUuid
    ) throws Exception {
        try (Connection connection = connections.openConnection()) {
            try (PreparedStatement profile = connection.prepareStatement("""
                    INSERT INTO npc_profiles (
                        profile_id, current_npc_uuid, owner_uuid, last_world_name,
                        created_at_ms, updated_at_ms, last_active_at_ms
                    ) VALUES ('profile', ?, ?, 'default', 1, 1, 1)
                    """)) {
                profile.setString(1, currentUuid.toString());
                profile.setString(2, ownerUuid.toString());
                profile.executeUpdate();
            }
            try (PreparedStatement aliases = connection.prepareStatement("""
                    INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms)
                    VALUES (?, 'profile', ?, 1)
                    """)) {
                aliases.setString(1, staleSourceUuid.toString());
                aliases.setInt(2, 0);
                aliases.executeUpdate();
                aliases.setString(1, currentUuid.toString());
                aliases.setInt(2, 1);
                aliases.executeUpdate();
            }
            try (PreparedStatement state = connection.prepareStatement("""
                    INSERT INTO companion_population_state (
                        profile_id, ownership_world_name, lifecycle_state, physical_world_name,
                        physical_chunk_x, physical_chunk_z, revision, source, created_at_ms, updated_at_ms
                    ) VALUES ('profile', 'default', 'UNLOADED', 'default', 4, 5, 1,
                              'lost-recovery-finalized', 1, 1)
                    """)) {
                state.executeUpdate();
            }
            try (PreparedStatement recovery = connection.prepareStatement("""
                    INSERT INTO npc_recovery_operations (
                        operation_id, profile_id, source_npc_uuid, planned_target_uuid,
                        actual_target_uuid, state, active, generation, attempt_count,
                        created_at_ms, updated_at_ms, completed_at_ms
                    ) VALUES ('recovery', 'profile', ?, ?, ?, 'FINALIZED', 0, 2, 1, 1, 2, 2)
                    """)) {
                recovery.setString(1, staleSourceUuid.toString());
                recovery.setString(2, currentUuid.toString());
                recovery.setString(3, currentUuid.toString());
                recovery.executeUpdate();
            }
        }
    }

    private static CompanionPopulationEvidence physical(String key,
                                                          UUID npcUuid,
                                                          UUID ownerUuid,
                                                          String world,
                                                          int chunkX,
                                                          int chunkZ) {
        return new CompanionPopulationEvidence(
                key,
                npcUuid,
                ownerUuid,
                CompanionPopulationEvidence.Kind.PHYSICAL_ENTITY,
                world,
                world,
                chunkX,
                chunkZ,
                "test"
        );
    }

    private static CompanionPopulationEvidence deadPhysical(String key,
                                                              UUID npcUuid,
                                                              UUID ownerUuid,
                                                              String world,
                                                              int chunkX,
                                                              int chunkZ) {
        return new CompanionPopulationEvidence(
                key,
                npcUuid,
                ownerUuid,
                CompanionPopulationEvidence.Kind.PHYSICAL_DEAD_ENTITY,
                world,
                world,
                chunkX,
                chunkZ,
                "test"
        );
    }

    private static CompanionPopulationEvidence captured(String key, UUID npcUuid, UUID ownerUuid) {
        return new CompanionPopulationEvidence(
                key,
                npcUuid,
                ownerUuid,
                CompanionPopulationEvidence.Kind.CAPTURED_ITEM,
                null,
                null,
                null,
                null,
                "test"
        );
    }

    private static CompanionPopulationEvidence dormant(
            String key,
            UUID npcUuid,
            UUID ownerUuid,
            CompanionPopulationEvidence.Kind kind
    ) {
        return new CompanionPopulationEvidence(
                key,
                npcUuid,
                ownerUuid,
                kind,
                "default",
                null,
                null,
                null,
                "test"
        );
    }

    private record Harness(SqliteConnectionManager connections,
                           PersistenceWriteQueue queue,
                           CompanionPopulationRepairRepository repair,
                           CompanionPopulationRepository population) implements AutoCloseable {
        @Override
        public void close() {
            queue.close();
        }
    }
}
