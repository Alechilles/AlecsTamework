package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.NpcLiveAliasRepairRepository.RepairStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies stale lost state and current-alias repair commit as one transaction. */
class NpcLiveAliasRepairRepositoryTest {
    private static final UUID SOURCE = new UUID(0L, 1L);
    private static final UUID LIVE_ALIAS = new UUID(0L, 2L);

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceWriteQueue queue;
    private NpcLiveAliasRepairRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("live-alias-repair.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            insertProfile(connection, "profile-a", SOURCE);
            insertAlias(connection, "profile-a", SOURCE, true);
            insertAlias(connection, "profile-a", LIVE_ALIAS, false);
            insertLostState(connection, "profile-a");
        }
        queue = new PersistenceWriteQueue(connections, new PersistenceHealthService(), null);
        repository = new NpcLiveAliasRepairRepository(queue);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
    }

    @Test
    void remapsSoleLiveAliasClearsLostAndMergesToolLinksAtomically() throws Exception {
        var result = committed(repository.repair(new NpcLiveAliasRepairRepository.RepairRequest(
                "profile-a", SOURCE, LIVE_ALIAS, List.of("tool-b", "tool-a", "tool-b"))));

        assertEquals(RepairStatus.APPLIED, result.status());
        assertEquals(LIVE_ALIAS, result.currentNpcUuid());
        assertEquals(1, result.clearedLostSnapshots());
        assertEquals(LIVE_ALIAS.toString(), scalarString(
                "SELECT current_npc_uuid FROM npc_profiles WHERE profile_id = 'profile-a'"));
        assertEquals(0, scalarInt("SELECT is_current FROM npc_uuid_aliases WHERE npc_uuid = '" + SOURCE + "'"));
        assertEquals(1, scalarInt("SELECT is_current FROM npc_uuid_aliases WHERE npc_uuid = '" + LIVE_ALIAS + "'"));
        assertEquals(0, scalarInt("SELECT lost_active FROM profile_states WHERE profile_id = 'profile-a'"));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM npc_snapshots WHERE profile_id = 'profile-a' AND is_active = 1"));
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM npc_tool_links WHERE profile_id = 'profile-a'"));

        var replay = committed(repository.repair(new NpcLiveAliasRepairRepository.RepairRequest(
                "profile-a", LIVE_ALIAS, LIVE_ALIAS, List.of("tool-a"))));
        assertEquals(RepairStatus.REPLAYED, replay.status());
    }

    @Test
    void rejectsOptimisticAndLifecycleConflictsWithoutMutation() throws Exception {
        var stale = committed(repository.repair(new NpcLiveAliasRepairRepository.RepairRequest(
                "profile-a", UUID.randomUUID(), LIVE_ALIAS, List.of("tool"))));
        assertEquals(RepairStatus.CURRENT_UUID_CONFLICT, stale.status());

        try (Connection connection = connections.openConnection();
             PreparedStatement operation = connection.prepareStatement("""
                     INSERT INTO npc_recovery_operations (
                       operation_id, profile_id, source_npc_uuid, planned_target_uuid,
                       state, active, generation, attempt_count, created_at_ms, updated_at_ms
                     ) VALUES ('op', 'profile-a', ?, ?, 'SPAWN_CLAIMED', 1, 0, 1, 1, 1)
                     """)) {
            operation.setString(1, SOURCE.toString());
            operation.setString(2, UUID.randomUUID().toString());
            operation.executeUpdate();
        }
        var blocked = committed(repository.repair(new NpcLiveAliasRepairRepository.RepairRequest(
                "profile-a", SOURCE, LIVE_ALIAS, List.of("tool"))));
        assertEquals(RepairStatus.LIFECYCLE_CONFLICT, blocked.status());
        assertEquals(SOURCE.toString(), scalarString(
                "SELECT current_npc_uuid FROM npc_profiles WHERE profile_id = 'profile-a'"));
        assertEquals(1, scalarInt("SELECT lost_active FROM profile_states WHERE profile_id = 'profile-a'"));
    }

    private <T> T committed(PersistenceWriteQueue.WriteSubmission<T> submission) throws Exception {
        assertTrue(submission.accepted());
        var outcome = submission.completion().get(3, TimeUnit.SECONDS);
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

    private void insertAlias(Connection connection, String profileId, UUID npcUuid,
                             boolean current) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO npc_uuid_aliases VALUES (?, ?, ?, 1)")) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, profileId);
            statement.setInt(3, current ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void insertLostState(Connection connection, String profileId) throws Exception {
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
                ) VALUES (?, 'lost', 1, '{}', 1, 1)
                """)) {
            snapshot.setString(1, profileId);
            snapshot.executeUpdate();
        }
    }

    private String scalarString(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
