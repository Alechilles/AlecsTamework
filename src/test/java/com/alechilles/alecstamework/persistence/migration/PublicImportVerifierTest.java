package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Logical target verification tests independent of file publication. */
class PublicImportVerifierTest {
    private static final LegacySourceFingerprint FINGERPRINT =
            new LegacySourceFingerprint("b".repeat(64), 10, -100);

    @TempDir
    Path tempDir;

    @Test
    void verifiesExactManifestCountsHashesAndCanonicalCoverage() throws Exception {
        PreparedImport prepared = prepare();
        try (Connection connection = prepared.connections().openReadConnection()) {
            new PublicImportVerifier().verify(
                    connection, prepared.plan(), prepared.manifest());
        }
    }

    @Test
    void detectsLogicalHashTamperingEvenWhenSqliteIntegrityStillPasses() throws Exception {
        PreparedImport prepared = prepare();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + prepared.connections().databasePath()
        ); Statement statement = connection.createStatement()) {
            statement.execute("""
                    UPDATE companion_profile
                    SET metadata_hash = 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
                    WHERE profile_id = '20000000-0000-0000-0000-000000000001'
                    """);
        }
        try (Connection connection = prepared.connections().openReadConnection()) {
            PublicImportException failure = assertThrows(
                    PublicImportException.class,
                    () -> new PublicImportVerifier().verify(
                            connection, prepared.plan(), prepared.manifest())
            );
            assertEquals("PROFILE_METADATA_HASH_MISMATCH", failure.code());
        }
    }

    private PreparedImport prepare() throws Exception {
        Path source = tempDir.resolve("source-" + java.util.UUID.randomUUID() + ".sqlite");
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-representative.sql", source);
        LegacyPublicData data;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            data = new LegacyPublicDataReader().read(connection, 4);
        }
        PublicImportPlan plan = new PublicImportPlanner().plan(data, FINGERPRINT, -500);
        PublicImportManifest manifest = new PublicImportManifestFactory().create(
                plan, FINGERPRINT, 4, "tamework.sqlite", -500);
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("target-" + java.util.UUID.randomUUID() + ".sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -500).initialize();
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            new PublicImportSqlWriter().write(connection, plan, manifest);
            connection.commit();
        }
        return new PreparedImport(connections, plan, manifest);
    }

    private record PreparedImport(
            SqliteConnectionFactory connections,
            PublicImportPlan plan,
            PublicImportManifest manifest
    ) {
    }
}
