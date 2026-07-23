package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transaction-level tests for materializing an immutable public import plan. */
class PublicImportSqlWriterTest {
    private static final LegacySourceFingerprint FINGERPRINT =
            new LegacySourceFingerprint("a".repeat(64), 10, -100);

    @TempDir
    Path tempDir;

    @Test
    void writesRepresentativePlanWithNoFabricatedOperationsOrEvents() throws Exception {
        Path target = write("public-v4-representative.sql", 4);

        assertEquals(6, count(target, "companion_profile"));
        assertEquals(7, count(target, "companion_alias"));
        assertEquals(6, count(target, "companion_lifecycle"));
        assertEquals(4, count(target, "companion_snapshot"));
        assertEquals(1, count(target, "profile_extension_data"));
        assertEquals(1, count(target, "coop_residency"));
        assertEquals(1, count(target, "import_manifest"));
        assertEquals(0, count(target, "operation_envelope"));
        assertEquals(0, count(target, "projection_outbox"));
        assertEquals("COOP", queryString(target, """
                SELECT lifecycle_state FROM companion_lifecycle
                WHERE profile_id = '20000000-0000-0000-0000-000000000005'
                """));
        assertEquals("world-a", queryString(target, """
                SELECT owner_world_key FROM companion_lifecycle
                WHERE profile_id = '20000000-0000-0000-0000-000000000005'
                """));
        assertEquals(1, queryLong(target,
                "SELECT payload_version FROM profile_extension_data"));
        assertEquals(1, queryLong(target,
                "SELECT revision FROM profile_extension_data"));
        assertEquals(3, queryLong(target, """
                SELECT COUNT(*) FROM companion_snapshot
                WHERE snapshot_kind IN ('capture', 'death', 'lost')
                  AND payload_version = 1
                """));
        assertEquals(
                Sha256Hash.ofUtf8(
                        "{\"message\":\"preserve Ω and negative time\",\"worldTimeMs\":-3000}"
                ).toString(),
                queryString(target, "SELECT payload_hash FROM profile_extension_data")
        );
        assertEquals("ok", queryString(target, "PRAGMA integrity_check"));
        assertEquals(0, rowCount(target, "PRAGMA foreign_key_check"));
    }

    @Test
    void writesBoundedConflictAsUnresolvedProfileQuarantine() throws Exception {
        Path target = write("public-v4-conflicting-flags.sql", 4);

        assertEquals(1, count(target, "persistence_incident"));
        assertEquals(1, count(target, "persistence_quarantine"));
        assertEquals("UNRESOLVED", queryString(target,
                "SELECT lifecycle_state FROM companion_lifecycle"));
        assertEquals(0, queryLong(target,
                "SELECT SUM(is_current) FROM companion_snapshot"));
    }

    private Path write(String resource, int version) throws Exception {
        Path source = tempDir.resolve(resource + ".sqlite");
        Path target = tempDir.resolve(resource + "-target.sqlite");
        PersistenceConsolidationFixtureDatabase.materialize(resource, source);
        LegacyPublicData data;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            data = new LegacyPublicDataReader().read(connection, version);
        }
        PublicImportPlan plan = new PublicImportPlanner().plan(data, FINGERPRINT, -500);
        SqliteConnectionFactory connections = new SqliteConnectionFactory(target);
        new SqliteSchemaV1Manager(connections, () -> -500).initialize();
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            new PublicImportSqlWriter().write(
                    connection,
                    plan,
                    new PublicImportManifest(
                            "00000000-0000-0000-0000-000000000001",
                            FINGERPRINT.snapshotSha256(),
                            version,
                            1,
                            "tamework.sqlite@" + FINGERPRINT.snapshotSha256(),
                            "{\"profiles\":" + plan.profiles().size() + "}",
                            -500
                    )
            );
            connection.commit();
        }
        assertTrue(new SqliteSchemaV1Manager(connections).verify()
                instanceof com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult.Found<?>);
        return target;
    }

    private long count(Path database, String table) throws Exception {
        return queryLong(database, "SELECT COUNT(*) FROM " + table);
    }

    private long queryLong(Path database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private String queryString(Path database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }

    private int rowCount(Path database, String sql) throws Exception {
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
