package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationLegacyEvidenceRepository;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProfileStateEvidenceSourceTest {
    @TempDir
    Path tempDir;

    @Test
    void emptyProfileSnapshotCompletesAtZero() throws Exception {
        SqliteProfileStateEvidenceSource source = new SqliteProfileStateEvidenceSource(
                new CompanionPopulationLegacyEvidenceRepository(migrated())
        );

        CompanionPopulationEvidenceSource.Batch batch = source.scan(0L, 128)
                .get(2, TimeUnit.SECONDS);

        assertTrue(batch.complete());
        assertEquals(0, batch.scannedUnits());
        assertTrue(batch.evidence().isEmpty());
    }

    /** Regression: ordinary profile writes during a 426-row startup scan must not degrade it. */
    @Test
    void profileMutationDuringPagingDoesNotInvalidateCapturedSnapshot() throws Exception {
        SqliteConnectionManager connections = migrated();
        UUID firstNpc = UUID.randomUUID();
        UUID secondNpc = UUID.randomUUID();
        UUID originalOwner = UUID.randomUUID();
        try (Connection connection = connections.openConnection()) {
            insertProfile(connection, "a-profile", firstNpc, originalOwner, 1L);
            insertProfile(connection, "b-profile", secondNpc, originalOwner, 1L);
        }
        SqliteProfileStateEvidenceSource source = new SqliteProfileStateEvidenceSource(
                new CompanionPopulationLegacyEvidenceRepository(connections)
        );

        CompanionPopulationEvidenceSource.Batch first = source.scan(0L, 1)
                .get(2, TimeUnit.SECONDS);
        UUID changedOwner = UUID.randomUUID();
        try (Connection connection = connections.openConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE npc_profiles
                    SET owner_uuid = ?, updated_at_ms = 2
                    WHERE profile_id = 'b-profile'
                    """)) {
                statement.setString(1, changedOwner.toString());
                statement.executeUpdate();
            }
            insertProfile(connection, "c-profile", UUID.randomUUID(), changedOwner, 2L);
        }
        CompanionPopulationEvidenceSource.Batch second = source.scan(1L, 1)
                .get(2, TimeUnit.SECONDS);

        assertFalse(first.complete());
        assertTrue(second.complete());
        assertEquals(2L, source.descriptor().estimatedTotal());
        assertEquals(firstNpc, first.evidence().getFirst().npcUuid());
        assertEquals(secondNpc, second.evidence().getFirst().npcUuid());
        assertEquals(originalOwner, second.evidence().getFirst().ownerUuid());
    }

    private SqliteConnectionManager migrated() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(
                tempDir.resolve("profile-snapshot.sqlite")
        );
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        return connections;
    }

    private static void insertProfile(
            Connection connection,
            String profileId,
            UUID npcUuid,
            UUID ownerUuid,
            long timestamp
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, owner_uuid, last_world_name,
                    created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, ?, 'default', ?, ?, ?)
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, npcUuid.toString());
            statement.setString(3, ownerUuid.toString());
            statement.setLong(4, timestamp);
            statement.setLong(5, timestamp);
            statement.setLong(6, timestamp);
            statement.executeUpdate();
        }
    }
}
