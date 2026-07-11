package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for fail-closed profile and historical-UUID resolution. */
class NpcIdentityRepositoryTest {
    private static final UUID HISTORICAL_A = uuid(1L);
    private static final UUID CURRENT_A = uuid(2L);
    private static final UUID CURRENT_B = uuid(3L);

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private NpcIdentityRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("identity.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            insertProfile(connection, "profile-a", CURRENT_A);
            insertAlias(connection, "profile-a", HISTORICAL_A, false, 1L);
            insertAlias(connection, "profile-a", CURRENT_A, true, 2L);
            insertProfile(connection, "profile-b", CURRENT_B);
            insertAlias(connection, "profile-b", CURRENT_B, true, 2L);
        }
        repository = new NpcIdentityRepository(connections);
    }

    @Test
    void resolvesHistoricalAliasAndLoadsEveryReplacementSuppressor() throws Exception {
        try (Connection connection = connections.openConnection()) {
            execute(connection, "INSERT INTO profile_states VALUES "
                    + "('profile-a', 0, 0, 1, 0, NULL, 10)");
            execute(connection, "INSERT INTO managed_coop_authority VALUES "
                    + "('world|1|2|3', 'world', 'coop', 1, 2, 3, 'TWORK_MANAGED', 1, 0, 1, 1, NULL)");
            execute(connection, """
                    INSERT INTO managed_coop_residents (
                        resident_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                        profile_id, resident_uuid, source_npc_uuid, deployed_npc_uuid, state,
                        generation, active, created_at_ms, updated_at_ms
                    ) VALUES (
                        'resident-a', 'world|1|2|3', 'world', 'coop', 1, 2, 3, 0,
                        'profile-a', '00000000-0000-0000-0000-000000000002',
                        '00000000-0000-0000-0000-000000000001',
                        '00000000-0000-0000-0000-000000000002', 'DEPLOYED', 4, 1, 1, 1
                    )
                    """);
            execute(connection, """
                    INSERT INTO npc_recovery_operations (
                        operation_id, profile_id, source_npc_uuid, planned_target_uuid, state,
                        active, generation, attempt_count, created_at_ms, updated_at_ms
                    ) VALUES (
                        'recover-a', 'profile-a',
                        '00000000-0000-0000-0000-000000000001',
                        '00000000-0000-0000-0000-000000000004',
                        'SPAWN_CLAIMED', 1, 2, 1, 1, 1
                    )
                    """);
        }

        NpcIdentityRepository.IdentityLoadResult result = repository.load(null, HISTORICAL_A);

        assertEquals(NpcIdentityRepository.LoadStatus.FOUND, result.status());
        NpcIdentityRepository.ProfileIdentity identity = result.identity();
        assertNotNull(identity);
        assertEquals("profile-a", identity.profileId());
        assertEquals(CURRENT_A, identity.currentNpcUuid());
        assertEquals(CURRENT_A, identity.aliases().get(0));
        assertTrue(identity.aliases().contains(HISTORICAL_A));
        assertTrue(identity.historicalUuidKnown());
        assertTrue(identity.flags().lost());
        assertEquals(ManagedCoopResidentRepository.ResidentState.DEPLOYED,
                identity.managedAssignment().state());
        assertEquals("recover-a", identity.activeRecovery().operationId());
        assertTrue(identity.replacementSuppressedByDurableState());
    }

    @Test
    void rejectsProfileAndUuidThatResolveToDifferentProfiles() {
        NpcIdentityRepository.IdentityLoadResult result = repository.load("profile-b", HISTORICAL_A);

        assertEquals(NpcIdentityRepository.LoadStatus.CONFLICT, result.status());
        assertEquals("profile-b", result.requestedProfileId());
        assertEquals("profile-a", result.uuidProfileId());
    }

    @Test
    void profileIdRemainsAuthoritativeForUnknownCachedUuid() {
        NpcIdentityRepository.IdentityLoadResult result = repository.load("profile-a", uuid(99L));

        assertEquals(NpcIdentityRepository.LoadStatus.FOUND, result.status());
        assertFalse(result.identity().historicalUuidKnown());
        assertEquals(CURRENT_A, result.identity().currentNpcUuid());
    }

    @Test
    void distinguishesUnresolvedIdentityMissingProfileAndIntegrityFailure() throws Exception {
        assertEquals(NpcIdentityRepository.LoadStatus.NOT_FOUND,
                repository.load(null, uuid(90L)).status());
        assertEquals(NpcIdentityRepository.LoadStatus.FAILED,
                repository.load("missing-profile", null).status());

        try (Connection connection = connections.openConnection()) {
            insertProfile(connection, "profile-c", uuid(91L));
        }
        NpcIdentityRepository.IdentityLoadResult inconsistent = repository.load("profile-c", null);
        assertEquals(NpcIdentityRepository.LoadStatus.FAILED, inconsistent.status());
        assertTrue(inconsistent.failureReason().startsWith("current_uuid_missing_alias:"));
    }

    @Test
    void sqlFailureNeverLooksLikeNotFound() throws Exception {
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE npc_uuid_aliases");
        }

        NpcIdentityRepository.IdentityLoadResult result = repository.load(null, HISTORICAL_A);

        assertEquals(NpcIdentityRepository.LoadStatus.FAILED, result.status());
        assertNotNull(result.failure());
    }

    private static void insertProfile(Connection connection, String profileId, UUID currentUuid)
            throws Exception {
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

    private static void insertAlias(Connection connection,
                                    String profileId,
                                    UUID npcUuid,
                                    boolean current,
                                    long mappedAtMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO npc_uuid_aliases VALUES (?, ?, ?, ?)")) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, profileId);
            statement.setInt(3, current ? 1 : 0);
            statement.setLong(4, mappedAtMs);
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
