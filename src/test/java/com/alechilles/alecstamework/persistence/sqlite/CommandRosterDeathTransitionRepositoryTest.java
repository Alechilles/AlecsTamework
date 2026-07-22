package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for a captured roster companion dying while its timed lease is active. */
class CommandRosterDeathTransitionRepositoryTest {
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID NPC = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String FAMILY = "hydragon:dragon_horn";
    private static final String ROLE = "Tamed_NordicDrake";

    @TempDir
    Path tempDir;

    @Test
    void deathSnapshotAtomicallyClearsCaptureAndDeactivatesRosterLease() throws Exception {
        try (HydragonPersistenceTestHarness harness = new HydragonPersistenceTestHarness(
                tempDir.resolve("command-roster-death.sqlite"))) {
            String profileId = harness.insertProfile(
                    OWNER, ROLE, "DEAD_REVIVABLE", "default", 7L);
            seedCapturedActiveRoster(harness, profileId);

            DeathRepository deaths = new DeathRepository(
                    harness.connections, harness.queue,
                    new NpcProfileRepository(harness.connections, harness.queue));
            assertTrue(deaths.upsertAsync(snapshot()));
            assertTrue(harness.queue.awaitIdle(5_000L));

            try (Connection connection = harness.connections.openConnection()) {
                assertEquals("DEAD_REVIVABLE", text(connection, """
                        SELECT command_state FROM command_family_roster_memberships
                        WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                        """, OWNER.toString(), FAMILY, profileId));
                assertEquals(7L, number(connection, """
                        SELECT profile_revision FROM command_family_roster_memberships
                        WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                        """, OWNER.toString(), FAMILY, profileId));
                assertEquals(0L, number(connection, """
                        SELECT active_for_bulk_commands FROM command_family_roster_memberships
                        WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                        """, OWNER.toString(), FAMILY, profileId));
                assertEquals(4L, number(connection, """
                        SELECT row_revision FROM command_family_rosters
                        WHERE owner_uuid = ? AND command_family_id = ?
                        """, OWNER.toString(), FAMILY));

                assertEquals("DEAD_REVIVABLE", text(connection, """
                        SELECT summon_state FROM command_timed_summon_sessions
                        WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                        """, OWNER.toString(), FAMILY, profileId));
                assertEquals(12L, number(connection, """
                        SELECT row_revision FROM command_timed_summon_sessions
                        WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                        """, OWNER.toString(), FAMILY, profileId));
                assertNull(text(connection, """
                        SELECT summon_session_id FROM command_timed_summon_sessions
                        WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                        """, OWNER.toString(), FAMILY, profileId));
                assertNull(text(connection, """
                        SELECT summon_remaining_ms FROM command_timed_summon_sessions
                        WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                        """, OWNER.toString(), FAMILY, profileId));

                assertEquals(0L, number(connection,
                        "SELECT capture_active FROM profile_states WHERE profile_id = ?", profileId));
                assertEquals(1L, number(connection,
                        "SELECT death_active FROM profile_states WHERE profile_id = ?", profileId));
                assertFalse(activeSnapshot(connection, profileId, "capture"));
                assertTrue(activeSnapshot(connection, profileId, "death"));
            }
        }
    }

    @Test
    void startupRecoveryRestoresOwnerAndRevivableDeathAfterPermanentRelease() throws Exception {
        try (HydragonPersistenceTestHarness harness = new HydragonPersistenceTestHarness(
                tempDir.resolve("command-roster-orphaned-death.sqlite"))) {
            String profileId = harness.insertProfile(
                    OWNER, ROLE, "ACTIVE", "default", 7L);
            seedCapturedActiveRoster(harness, profileId);
            try (Connection connection = harness.connections.openConnection()) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE npc_profiles SET owner_uuid = NULL WHERE profile_id = ?
                        """)) {
                    statement.setString(1, profileId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE companion_population_state
                        SET lifecycle_state = 'RELEASED', revision = 8, source = 'test-release'
                        WHERE profile_id = ?
                        """)) {
                    statement.setString(1, profileId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO companion_population_operations(
                            operation_id, profile_id, operation_type, state, expected_revision,
                            old_state_json, new_state_json, target_context_json,
                            created_at_ms, updated_at_ms, completed_at_ms)
                        VALUES ('death-release', ?, 'OWNER_CLEAR', 'COMMITTED', 7,
                            ?, ?, ?, 100, 100, 100)
                        """)) {
                    statement.setString(1, profileId);
                    statement.setString(2, "{\"ownerUuid\":\"" + OWNER
                            + "\",\"lifecycleState\":\"ACTIVE\"}");
                    statement.setString(3,
                            "{\"ownerUuid\":null,\"lifecycleState\":\"RELEASED\"}");
                    statement.setString(4, "{\"npcUuid\":\"" + NPC
                            + "\",\"permanentDeath\":true,\"deathSource\":\"lethal-damage\"}");
                    statement.executeUpdate();
                }
            }

            DeathRepository deaths = new DeathRepository(
                    harness.connections, harness.queue,
                    new NpcProfileRepository(harness.connections, harness.queue));
            assertEquals(1, deaths.recoverOrphanedCommandRosterDeaths().recovered());
            assertEquals(0, deaths.recoverOrphanedCommandRosterDeaths().recovered());

            try (Connection connection = harness.connections.openConnection()) {
                assertEquals(OWNER.toString(), text(connection,
                        "SELECT owner_uuid FROM npc_profiles WHERE profile_id = ?", profileId));
                assertEquals("DEAD_REVIVABLE", text(connection, """
                        SELECT lifecycle_state FROM companion_population_state WHERE profile_id = ?
                        """, profileId));
                assertEquals("DEAD_REVIVABLE", text(connection, """
                        SELECT command_state FROM command_family_roster_memberships
                        WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                        """, OWNER.toString(), FAMILY, profileId));
                assertEquals("DEAD_REVIVABLE", text(connection, """
                        SELECT summon_state FROM command_timed_summon_sessions
                        WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                        """, OWNER.toString(), FAMILY, profileId));
                assertEquals(0L, number(connection,
                        "SELECT capture_active FROM profile_states WHERE profile_id = ?", profileId));
                assertEquals(1L, number(connection,
                        "SELECT death_active FROM profile_states WHERE profile_id = ?", profileId));
                assertFalse(activeSnapshot(connection, profileId, "capture"));
                assertTrue(activeSnapshot(connection, profileId, "death"));
            }
        }
    }

    private static void seedCapturedActiveRoster(HydragonPersistenceTestHarness harness,
                                                  String profileId) throws Exception {
        try (Connection connection = harness.connections.openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE npc_profiles SET current_npc_uuid = ? WHERE profile_id = ?")) {
                statement.setString(1, NPC.toString());
                statement.setString(2, profileId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO profile_states(profile_id, capture_active, death_active,
                        lost_active, in_coop, updated_at_ms)
                    VALUES (?, 1, 0, 0, 0, 10)
                    """)) {
                statement.setString(1, profileId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO npc_snapshots(profile_id, snapshot_type, snapshot_version,
                        payload_json, is_active, created_at_ms)
                    VALUES (?, 'capture', 1, '{}', 1, 10)
                    """)) {
                statement.setString(1, profileId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO command_family_rosters(owner_uuid, command_family_id,
                        row_revision, created_at_ms, updated_at_ms)
                    VALUES (?, ?, 3, 10, 10)
                    """)) {
                statement.setString(1, OWNER.toString());
                statement.setString(2, FAMILY);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO command_family_roster_memberships(owner_uuid, command_family_id,
                        profile_id, role_id, profile_revision, command_state,
                        active_for_bulk_commands, created_at_ms, updated_at_ms)
                    VALUES (?, ?, ?, ?, 2, 'ACTIVE', 1, 10, 10)
                    """)) {
                statement.setString(1, OWNER.toString());
                statement.setString(2, FAMILY);
                statement.setString(3, profileId);
                statement.setString(4, ROLE);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO command_timed_summon_sessions(owner_uuid, command_family_id,
                        profile_id, row_revision, summon_state, summon_session_id,
                        summon_remaining_ms, summon_config_id, summon_policy_json,
                        warning_receipts_json, summon_last_checkpoint_at_ms,
                        created_at_ms, updated_at_ms)
                    VALUES (?, ?, ?, 11, 'ACTIVE', 'session', 450000, 'hydragon', '{}',
                        '[60000]', 10, 10, 10)
                    """)) {
                statement.setString(1, OWNER.toString());
                statement.setString(2, FAMILY);
                statement.setString(3, profileId);
                statement.executeUpdate();
            }
        }
    }

    private static CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot() {
        return new CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot(
                NPC, OWNER, "owner", new String[] {"roster:" + OWNER + ":" + FAMILY},
                ROLE, true, "Drake", "Drake", null, null, 100L, 200L,
                null, null, 0L, null, null, 0L, null, null, null, 0L,
                null, 0L, 0L, 0L, 0L, 0.55, 0.80, 0.80, 0.80, 1.0, 1.0,
                false, null, null, false, null, 1, 0.0, null, 0, null,
                CommandLinkedNpcDeathService.DeathCauseKind.UNKNOWN, null, null);
    }

    private static boolean activeSnapshot(Connection connection, String profileId, String type)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM npc_snapshots
                WHERE profile_id = ? AND snapshot_type = ? AND is_active = 1
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, type);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static String text(Connection connection, String sql, String... parameters)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return result.getString(1);
            }
        }
    }

    private static long number(Connection connection, String sql, String... parameters)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AssertionError("Expected one result row");
                return result.getLong(1);
            }
        }
    }
}
