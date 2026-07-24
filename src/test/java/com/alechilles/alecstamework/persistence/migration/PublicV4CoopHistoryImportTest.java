package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * End-to-end regression for real public-v2.16.1 coop history retained beside current residency.
 */
class PublicV4CoopHistoryImportTest {
    private static final String ACTIVE_PROFILE =
            "20000000-0000-0000-0000-000000000001";
    private static final String COOPED_PROFILE =
            "20000000-0000-0000-0000-000000000005";
    private static final CoopSlotKey CURRENT_SLOT =
            new CoopSlotKey("world-a", "fixture-coop", 10, 64, 20, 0);

    @TempDir
    Path tempDir;

    @Test
    void importsOnlyPositiveHousedResidencyAndPreservesReleasedSnapshotsAsHistory()
            throws Exception {
        Path source = tempDir.resolve("tamework.sqlite");
        Path target = tempDir.resolve("tamework-state.sqlite");
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-coop-history.sql", source
        );
        byte[] sourceBefore = Files.readAllBytes(source);

        assertInstanceOf(
                PublicImportResult.Imported.class,
                new PublicPersistenceImporter(() -> -7_000)
                        .importSource(source, target)
        );

        assertArrayEquals(sourceBefore, Files.readAllBytes(source));
        assertEquals(3, scalar(target, "SELECT COUNT(*) FROM coop_slot"));
        assertEquals(1, scalar(target, "SELECT COUNT(*) FROM coop_residency"));
        assertEquals(0, scalar(target, "SELECT COUNT(*) FROM persistence_incident"));
        assertEquals(0, scalar(target, "SELECT COUNT(*) FROM persistence_quarantine"));
        assertEquals(1, scalar(target, """
                SELECT COUNT(*) FROM companion_snapshot
                WHERE snapshot_kind = 'coop' AND is_current = 1
                """));
        assertEquals(2, scalar(target, """
                SELECT COUNT(*) FROM companion_snapshot
                WHERE snapshot_kind = 'coop' AND is_current = 0
                """));
        assertEquals("COOP", text(target, """
                SELECT lifecycle_state FROM companion_lifecycle
                WHERE profile_id = '%s'
                """.formatted(COOPED_PROFILE)));
        assertEquals(CURRENT_SLOT.toString(), text(target, """
                SELECT location_key FROM companion_lifecycle
                WHERE profile_id = '%s'
                """.formatted(COOPED_PROFILE)));
        assertEquals("UNRESOLVED", text(target, """
                SELECT lifecycle_state FROM companion_lifecycle
                WHERE profile_id = '%s'
                """.formatted(ACTIVE_PROFILE)));
        assertEquals("ok", text(target, "PRAGMA integrity_check"));
        assertEquals(0, rows(target, "PRAGMA foreign_key_check"));
    }

    private long scalar(Path database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            return row.next() ? row.getLong(1) : -1;
        }
    }

    private String text(Path database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            return row.next() ? row.getString(1) : null;
        }
    }

    private int rows(Path database, String sql) throws Exception {
        int count = 0;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                count++;
            }
        }
        return count;
    }
}
