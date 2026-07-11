package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidence;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidenceSet;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationLegacyEvidenceRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void dormantProfileEmitsNeutralProfileAndEveryActiveLegacyLifecycle() throws Exception {
        SqliteConnectionManager connections = migrated("legacy.sqlite");
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        try (Connection connection = connections.openConnection()) {
            insertProfile(connection, "profile", npcUuid, ownerUuid);
            insertProfileState(connection, "profile", true, false, true, false);
        }

        CompanionPopulationLegacyEvidenceRepository repository =
                new CompanionPopulationLegacyEvidenceRepository(connections);
        CompanionPopulationLegacyEvidenceRepository.Batch batch = repository.loadBatch(0, 16, "test");

        assertEquals(1, batch.scannedUnits());
        assertEquals(Set.of(
                        CompanionPopulationEvidence.Kind.PROFILE_RECORD,
                        CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT,
                        CompanionPopulationEvidence.Kind.LOST_SNAPSHOT
                ), batch.evidence().stream().map(CompanionPopulationEvidence::kind)
                        .collect(Collectors.toSet()));
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(batch.evidence());
        assertFalse(set.isConflictFree());
        assertEquals("conflicting-dormant-lifecycle-evidence", set.conflicts().getFirst().reason());
    }

    @Test
    void ownerlessProfileRecordDoesNotOverrideSnapshotOwner() throws Exception {
        SqliteConnectionManager connections = migrated("neutral-owner.sqlite");
        UUID npcUuid = UUID.randomUUID();
        try (Connection connection = connections.openConnection()) {
            insertProfile(connection, "profile", npcUuid, null);
            insertProfileState(connection, "profile", false, true, false, false);
        }

        CompanionPopulationLegacyEvidenceRepository.Batch batch =
                new CompanionPopulationLegacyEvidenceRepository(connections).loadBatch(0, 16, "test");
        CompanionPopulationEvidenceSet set = new CompanionPopulationEvidenceSet(batch.evidence());

        assertTrue(set.isConflictFree());
        assertFalse(set.evidence().getFirst().ownerObserved());
        assertEquals(CompanionPopulationEvidence.Kind.DEATH_SNAPSHOT,
                set.evidence().getFirst().lifecycleKind());
    }

    @Test
    void profileWithoutResolvableNpcUuidFailsTheSourceInsteadOfBeingSkipped() throws Exception {
        SqliteConnectionManager connections = migrated("missing-uuid.sqlite");
        try (Connection connection = connections.openConnection()) {
            insertProfile(connection, "profile", null, UUID.randomUUID());
        }

        CompanionPopulationLegacyEvidenceRepository repository =
                new CompanionPopulationLegacyEvidenceRepository(connections);
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> repository.loadBatch(0, 16, "test")
        );

        assertTrue(failure.getMessage().contains("Profile has no resolvable"));
    }

    @Test
    void historicalAliasResolvesAProfileWithoutCurrentUuid() throws Exception {
        SqliteConnectionManager connections = migrated("alias.sqlite");
        UUID aliasUuid = UUID.randomUUID();
        try (Connection connection = connections.openConnection()) {
            insertProfile(connection, "profile", null, UUID.randomUUID());
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms) VALUES (?, ?, 0, 1)"
            )) {
                statement.setString(1, aliasUuid.toString());
                statement.setString(2, "profile");
                statement.executeUpdate();
            }
        }

        List<CompanionPopulationEvidence> evidence =
                new CompanionPopulationLegacyEvidenceRepository(connections)
                        .loadBatch(0, 16, "test").evidence();

        assertEquals(1, evidence.size());
        assertEquals(aliasUuid, evidence.getFirst().npcUuid());
    }

    private SqliteConnectionManager migrated(String file) throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve(file));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        return connections;
    }

    private static void insertProfile(Connection connection,
                                      String profileId,
                                      UUID npcUuid,
                                      UUID ownerUuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, owner_uuid, last_world_name,
                    created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, ?, 'default', 1, 1, 1)
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, npcUuid == null ? null : npcUuid.toString());
            statement.setString(3, ownerUuid == null ? null : ownerUuid.toString());
            statement.executeUpdate();
        }
    }

    private static void insertProfileState(Connection connection,
                                           String profileId,
                                           boolean captured,
                                           boolean dead,
                                           boolean lost,
                                           boolean coop) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO profile_states (
                    profile_id, capture_active, death_active, lost_active, in_coop,
                    coop_key, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, NULL, 1)
                """)) {
            statement.setString(1, profileId);
            statement.setInt(2, captured ? 1 : 0);
            statement.setInt(3, dead ? 1 : 0);
            statement.setInt(4, lost ? 1 : 0);
            statement.setInt(5, coop ? 1 : 0);
            statement.executeUpdate();
        }
    }
}
