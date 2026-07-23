package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.LoadedNpcIdentitySnapshot;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepairRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Shared SQLite fixture for operation-recovery scenario tests. */
final class CompanionPopulationOperationRecoveryTestSupport {
    private CompanionPopulationOperationRecoveryTestSupport() {
    }

    static Harness open(Path tempDir, String file) throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve(file));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        PersistenceWriteQueue queue = new PersistenceWriteQueue(
                connections, new PersistenceHealthService(), null
        );
        ManagedCoopResidentRepository residents = new ManagedCoopResidentRepository(
                connections, queue);
        CoopLifecycleOperationRepository lifecycle = new CoopLifecycleOperationRepository(
                connections, queue, residents);
        CompanionPopulationRepository repository = new CompanionPopulationRepository(
                connections, queue, lifecycle);
        return new Harness(
                connections,
                queue,
                repository,
                residents,
                lifecycle,
                new CompanionPopulationRepairRepository(queue),
                new CompanionPopulationOperationRecoveryService(repository)
        );
    }

    static void insertScenario(Harness harness,
                               String profileId,
                               UUID npcUuid,
                               UUID oldOwner,
                               UUID newOwner,
                               CompanionLifecycleState oldLifecycle,
                               CompanionLifecycleState newLifecycle,
                               String oldWorld,
                               String newWorld,
                               OwnerPopulationOperation operation,
                               CompanionPopulationOperationRecord.State operationState,
                               boolean breedingContext) throws Exception {
        try (Connection connection = harness.connections().openConnection()) {
            insertProfile(connection, profileId, npcUuid, oldOwner, oldWorld);
            insertAlias(connection, profileId, npcUuid);
            insertState(connection, profileId, oldLifecycle, oldWorld);
            insertOperation(
                    connection,
                    profileId,
                    npcUuid,
                    oldOwner,
                    newOwner,
                    oldLifecycle,
                    newLifecycle,
                    oldWorld,
                    newWorld,
                    operation,
                    operationState,
                    breedingContext
            );
        }
    }

    private static void insertProfile(Connection connection,
                                      String profileId,
                                      UUID npcUuid,
                                      UUID oldOwner,
                                      String oldWorld) throws Exception {
        try (PreparedStatement profile = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, owner_uuid, last_world_name,
                    created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, ?, ?, 1, 1, 1)
                """)) {
            profile.setString(1, profileId);
            profile.setString(2, npcUuid.toString());
            profile.setString(3, oldOwner == null ? null : oldOwner.toString());
            profile.setString(4, oldWorld);
            profile.executeUpdate();
        }
    }

    private static void insertAlias(Connection connection, String profileId, UUID npcUuid) throws Exception {
        try (PreparedStatement alias = connection.prepareStatement("""
                INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms)
                VALUES (?, ?, 1, 1)
                """)) {
            alias.setString(1, npcUuid.toString());
            alias.setString(2, profileId);
            alias.executeUpdate();
        }
    }

    private static void insertState(Connection connection,
                                    String profileId,
                                    CompanionLifecycleState oldLifecycle,
                                    String oldWorld) throws Exception {
        boolean oldPhysical = oldLifecycle == CompanionLifecycleState.ACTIVE
                || oldLifecycle == CompanionLifecycleState.UNLOADED
                || oldLifecycle == CompanionLifecycleState.RESTORING
                || oldLifecycle == CompanionLifecycleState.STORING;
        try (PreparedStatement state = connection.prepareStatement("""
                INSERT INTO companion_population_state (
                    profile_id, ownership_world_name, lifecycle_state, physical_world_name,
                    physical_chunk_x, physical_chunk_z, revision, source, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, 0, 'test', 1, 1)
                """)) {
            state.setString(1, profileId);
            state.setString(2, oldWorld);
            state.setString(3, oldLifecycle.name());
            state.setString(4, oldPhysical ? oldWorld : null);
            if (oldPhysical) {
                state.setInt(5, 0);
                state.setInt(6, 0);
            } else {
                state.setObject(5, null);
                state.setObject(6, null);
            }
            state.executeUpdate();
        }
    }

    private static void insertOperation(Connection connection,
                                        String profileId,
                                        UUID npcUuid,
                                        UUID oldOwner,
                                        UUID newOwner,
                                        CompanionLifecycleState oldLifecycle,
                                        CompanionLifecycleState newLifecycle,
                                        String oldWorld,
                                        String newWorld,
                                        OwnerPopulationOperation operation,
                                        CompanionPopulationOperationRecord.State operationState,
                                        boolean breedingContext) throws Exception {
        String targetKey = breedingContext ? "plannedNpcUuid" : "npcUuid";
        try (PreparedStatement journal = connection.prepareStatement("""
                INSERT INTO companion_population_operations (
                    operation_id, profile_id, operation_type, state, expected_revision,
                    old_state_json, new_state_json, target_context_json,
                    created_at_ms, updated_at_ms, completed_at_ms, last_error
                ) VALUES ('operation', ?, ?, ?, 0, ?, ?, ?, 1, 1, 0, NULL)
                """)) {
            journal.setString(1, profileId);
            journal.setString(2, operation.name());
            journal.setString(3, operationState.name());
            journal.setString(4, stateJson(oldOwner, oldLifecycle, oldWorld));
            journal.setString(5, stateJson(newOwner, newLifecycle, newWorld));
            String breedingFields = breedingContext
                    ? "\"idempotencyKey\":\"attempt\",\"childKey\":\"child-0000\"," : "";
            journal.setString(6, "{" + breedingFields + "\"" + targetKey + "\":\"" + npcUuid
                    + "\",\"world\":\"" + newWorld + "\",\"chunkX\":0,\"chunkZ\":0}");
            journal.executeUpdate();
        }
    }

    private static String stateJson(UUID owner,
                                    CompanionLifecycleState lifecycle,
                                    String world) {
        String ownerJson = owner == null ? "null" : "\"" + owner + "\"";
        return "{\"ownerUuid\":" + ownerJson
                + ",\"lifecycleState\":\"" + lifecycle.name()
                + "\",\"ownershipWorldName\":\"" + world + "\"}";
    }

    static void updateTargetContext(Harness harness, String targetContextJson) throws Exception {
        try (Connection connection = harness.connections().openConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE companion_population_operations SET target_context_json = ?"
             )) {
            update.setString(1, targetContextJson);
            update.executeUpdate();
        }
    }

    static void markPermanentRelease(Harness harness, UUID npcUuid) throws Exception {
        updateTargetContext(
                harness,
                "{\"npcUuid\":\"" + npcUuid
                        + "\",\"world\":\"default\",\"chunkX\":0,\"chunkZ\":0,"
                        + "\"permanentRelease\":true}"
        );
    }

    static void markPermanentDeath(Harness harness, UUID npcUuid) throws Exception {
        updateTargetContext(
                harness,
                "{\"npcUuid\":\"" + npcUuid
                        + "\",\"world\":\"default\",\"chunkX\":0,\"chunkZ\":0,"
                        + "\"permanentRelease\":true,\"permanentDeath\":true}"
        );
    }

    static CompanionPopulationEvidence physical(UUID npcUuid,
                                                 UUID ownerUuid,
                                                 String world,
                                                 int chunkX,
                                                 int chunkZ) {
        return new CompanionPopulationEvidence(
                "physical-" + npcUuid,
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

    static CompanionPopulationEvidence physicalDead(UUID npcUuid,
                                                     UUID ownerUuid,
                                                     String world,
                                                     int chunkX,
                                                     int chunkZ) {
        return new CompanionPopulationEvidence(
                "physical-dead-" + npcUuid,
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

    static CompanionPopulationEvidence dormant(UUID npcUuid,
                                                UUID ownerUuid,
                                                CompanionPopulationEvidence.Kind kind,
                                                String world) {
        return new CompanionPopulationEvidence(
                kind.name() + "-" + npcUuid,
                npcUuid,
                ownerUuid,
                kind,
                world,
                null,
                null,
                null,
                "test"
        );
    }

    record Harness(SqliteConnectionManager connections,
                   PersistenceWriteQueue queue,
                   CompanionPopulationRepository repository,
                   ManagedCoopResidentRepository residents,
                   CoopLifecycleOperationRepository lifecycle,
                   CompanionPopulationRepairRepository repair,
                   CompanionPopulationOperationRecoveryService recovery) implements AutoCloseable {
        CompanionPopulationOperationRecoveryService.RecoveryResult recover(
                List<CompanionPopulationEvidence> evidence
        ) throws Exception {
            return recover(evidence, new LoadedNpcIdentitySnapshot(0L, true, List.of()));
        }

        CompanionPopulationOperationRecoveryService.RecoveryResult recover(
                List<CompanionPopulationEvidence> evidence,
                LoadedNpcIdentitySnapshot loadedIdentities
        ) throws Exception {
            return recovery.recoverAsync(
                    repository.loadNonterminalOperations(),
                    new CompanionPopulationEvidenceSet(evidence),
                    loadedIdentities
            ).get(3, TimeUnit.SECONDS);
        }

        CompanionPopulationStateRecord state() throws Exception {
            return repository.loadAllStates().getFirst();
        }

        CompanionPopulationOperationRecord.State operationState() throws Exception {
            try (Connection connection = connections.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT state FROM companion_population_operations WHERE operation_id = 'operation'"
                 );
                 ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return CompanionPopulationOperationRecord.State.valueOf(resultSet.getString(1));
            }
        }

        @Override
        public void close() {
            queue.close();
        }
    }
}
