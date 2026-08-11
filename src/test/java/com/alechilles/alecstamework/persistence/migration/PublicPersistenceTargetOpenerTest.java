package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.TameworkDataPathLayout;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Target selection tests for fresh, existing, and unsupported source paths. */
class PublicPersistenceTargetOpenerTest {
    @TempDir
    Path tempDir;

    @Test
    void createsAndThenReusesOneVerifiedFreshTarget() {
        PublicPersistenceTargetOpener opener =
                new PublicPersistenceTargetOpener(() -> -100);

        PublicPersistenceTarget fresh = opener.open(tempDir);
        PublicPersistenceTarget existing = opener.open(tempDir);

        assertEquals(PublicPersistenceTarget.Origin.FRESH, fresh.origin());
        assertEquals(PublicPersistenceTarget.Origin.EXISTING, existing.origin());
        assertEquals(fresh.databasePath(), existing.databasePath());
        assertInstanceOf(
                PersistenceReadResult.Found.class,
                new SqliteSchemaV1Manager(new SqliteConnectionFactory(
                        fresh.databasePath()
                )).verify()
        );
        assertNoCreatingAttempts();
    }

    @Test
    void refusesDevelopmentSourceBeforeCreatingTarget() throws Exception {
        Path source = PersistenceFiles.legacyDatabase(tempDir);
        executeFixture(source, "development-v5-marker.sql");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new PublicPersistenceTargetOpener(() -> -100)
                        .open(tempDir)
        );

        assertTrue(failure.getMessage().endsWith(
                ":UNSUPPORTED_DEVELOPMENT_SCHEMA"
        ));
        assertTrue(Files.isRegularFile(source));
        assertTrue(Files.notExists(
                PersistenceFiles.replacementDatabase(tempDir)
        ));
        assertNoCreatingAttempts();
    }

    @Test
    void importsDatOnlyLegacyDirectoryBeforeCreatingAFreshTarget()
            throws Exception {
        Path targetDirectory = Files.createDirectory(
                tempDir.resolve("current")
        );
        Path legacyDirectory = Files.createDirectory(
                tempDir.resolve("legacy")
        );
        Path captures = legacyDirectory.resolve(
                LegacyDatBundleSnapshot.CAPTURES_FILE
        );
        Files.writeString(
                captures,
                String.join("\t",
                        "00000000-0000-0000-0000-000000000201",
                        "10000000-0000-0000-0000-000000000001",
                        "MzAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDAx",
                        "", "", "", "", "10"),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                legacyDirectory.resolve(
                        "CommandLinkedNpcCoops.dat.runtime-v2.marker"
                ),
                "runtime-v2",
                StandardCharsets.UTF_8
        );
        Map<String, FileEvidence> before = evidence(legacyDirectory);

        PublicPersistenceTarget opened =
                new PublicPersistenceTargetOpener(() -> -100).open(
                        targetDirectory,
                        List.of(targetDirectory, legacyDirectory)
                );

        assertEquals(
                PublicPersistenceTarget.Origin.IMPORTED_PUBLIC,
                opened.origin()
        );
        assertEquals(
                1,
                queryInt(opened.databasePath(),
                        "SELECT COUNT(*) FROM import_manifest")
        );
        assertEquals(
                1,
                queryInt(opened.databasePath(),
                        "SELECT COUNT(*) FROM companion_profile")
        );
        assertEquals(before, evidence(legacyDirectory));
    }

    @Test
    void importsOneHistoricalSqliteSourceWithoutChangingMainWalShmOrMarker()
            throws Exception {
        Path targetDirectory = Files.createDirectory(
                tempDir.resolve("current")
        );
        Path legacyDirectory = Files.createDirectory(
                tempDir.resolve("legacy")
        );
        Path historicalDirectory = Files.createDirectory(
                tempDir.resolve("historical")
        );
        Path source = PersistenceFiles.legacyDatabase(historicalDirectory);
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-representative.sql",
                source
        );
        Files.writeString(
                historicalDirectory.resolve(
                        "tamework.sqlite.legacy-dat-import-v2.marker"
                ),
                "legacy-dat-imported",
                StandardCharsets.UTF_8
        );

        Class.forName("org.sqlite.JDBC");
        try (var writer = DriverManager.getConnection(
                "jdbc:sqlite:" + source
        ); var statement = writer.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.executeUpdate("""
                    UPDATE npc_profiles
                    SET updated_at_ms = updated_at_ms + 1
                    WHERE current_npc_uuid = (
                        SELECT current_npc_uuid FROM npc_profiles LIMIT 1
                    )
                    """);
            assertTrue(Files.isRegularFile(
                    source.resolveSibling("tamework.sqlite-wal")
            ));
            assertTrue(Files.isRegularFile(
                    source.resolveSibling("tamework.sqlite-shm")
            ));
            Map<String, FileEvidence> before =
                    evidence(historicalDirectory);

            PublicPersistenceTarget opened =
                    new PublicPersistenceTargetOpener(() -> -100).open(
                            new TameworkDataPathLayout(
                                    targetDirectory,
                                    legacyDirectory,
                                    Optional.of(historicalDirectory)
                            )
                    );

            assertEquals(
                    PublicPersistenceTarget.Origin.IMPORTED_PUBLIC,
                    opened.origin()
            );
            assertEquals(before, evidence(historicalDirectory));
        }
    }

    @Test
    void refusesMultipleSourceDirectoriesWithoutCreatingTarget()
            throws Exception {
        Path targetDirectory = Files.createDirectory(
                tempDir.resolve("current")
        );
        Path legacyDirectory = Files.createDirectory(
                tempDir.resolve("legacy")
        );
        Path historicalDirectory = Files.createDirectory(
                tempDir.resolve("historical")
        );
        Files.writeString(
                legacyDirectory.resolve(
                        LegacyDatBundleSnapshot.CAPTURES_FILE
                ),
                "legacy",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                historicalDirectory.resolve(
                        LegacyDatBundleSnapshot.LOST_FILE
                ),
                "historical",
                StandardCharsets.UTF_8
        );
        Map<String, FileEvidence> legacyBefore = evidence(legacyDirectory);
        Map<String, FileEvidence> historicalBefore =
                evidence(historicalDirectory);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new PublicPersistenceTargetOpener(() -> -100).open(
                        targetDirectory,
                        List.of(
                                targetDirectory,
                                legacyDirectory,
                                historicalDirectory
                        )
                )
        );

        assertTrue(failure.getMessage().endsWith(
                ":AMBIGUOUS_PERSISTENCE_SOURCE_DIRECTORIES"
        ));
        assertTrue(Files.notExists(
                PersistenceFiles.replacementDatabase(targetDirectory)
        ));
        assertEquals(legacyBefore, evidence(legacyDirectory));
        assertEquals(historicalBefore, evidence(historicalDirectory));
        assertNoCreatingAttempts(targetDirectory);
    }

    @Test
    void refusesSqliteAndDatInOneDirectoryWithoutChoosingPrecedence()
            throws Exception {
        Path sourceDirectory = Files.createDirectory(
                tempDir.resolve("mixed")
        );
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-representative.sql",
                PersistenceFiles.legacyDatabase(sourceDirectory)
        );
        Files.writeString(
                sourceDirectory.resolve(
                        LegacyDatBundleSnapshot.CAPTURES_FILE
                ),
                "legacy-dat",
                StandardCharsets.UTF_8
        );
        Map<String, FileEvidence> before = evidence(sourceDirectory);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new PublicPersistenceTargetOpener(() -> -100)
                        .open(sourceDirectory)
        );

        assertTrue(failure.getMessage().endsWith(
                ":AMBIGUOUS_SQLITE_AND_DAT_SOURCES"
        ));
        assertTrue(Files.notExists(
                PersistenceFiles.replacementDatabase(sourceDirectory)
        ));
        assertEquals(before, evidence(sourceDirectory));
    }

    @Test
    void existingTargetNeverMergesNewlyDiscoveredLegacySources()
            throws Exception {
        Path targetDirectory = Files.createDirectory(
                tempDir.resolve("current")
        );
        Path legacyDirectory = Files.createDirectory(
                tempDir.resolve("legacy")
        );
        PublicPersistenceTargetOpener opener =
                new PublicPersistenceTargetOpener(() -> -100);
        PublicPersistenceTarget fresh = opener.open(targetDirectory);
        Files.writeString(
                legacyDirectory.resolve(
                        LegacyDatBundleSnapshot.CAPTURES_FILE
                ),
                "late-source",
                StandardCharsets.UTF_8
        );
        Map<String, FileEvidence> sourceBefore = evidence(legacyDirectory);

        PublicPersistenceTarget existing = opener.open(
                targetDirectory,
                List.of(targetDirectory, legacyDirectory)
        );

        assertEquals(PublicPersistenceTarget.Origin.FRESH, fresh.origin());
        assertEquals(
                PublicPersistenceTarget.Origin.EXISTING,
                existing.origin()
        );
        assertEquals(0, queryInt(
                existing.databasePath(),
                "SELECT COUNT(*) FROM import_manifest"
        ));
        assertEquals(sourceBefore, evidence(legacyDirectory));
    }

    @Test
    void repairsLegacyFlagQuarantineInPlaceWithoutRollback()
            throws Exception {
        Path targetDirectory = Files.createDirectory(
                tempDir.resolve("current")
        );
        Path historicalDirectory = Files.createDirectory(
                tempDir.resolve("historical")
        );
        Path source = PersistenceFiles.legacyDatabase(historicalDirectory);
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-conflicting-flags.sql",
                source
        );
        PublicPersistenceTargetOpener opener =
                new PublicPersistenceTargetOpener(() -> -100);
        PublicPersistenceTarget imported = opener.open(
                targetDirectory,
                List.of(targetDirectory, historicalDirectory)
        );
        simulateOlderQuarantinedImport(imported.databasePath());
        Map<String, FileEvidence> sourceBefore = evidence(
                historicalDirectory
        );

        PublicPersistenceTarget reopened = opener.open(
                targetDirectory,
                List.of(targetDirectory, historicalDirectory)
        );

        assertEquals(PublicPersistenceTarget.Origin.EXISTING, reopened.origin());
        assertEquals(imported.databasePath(), reopened.databasePath());
        assertEquals("DEAD_REVIVABLE", queryString(
                reopened.databasePath(),
                "SELECT lifecycle_state FROM companion_lifecycle"
        ));
        assertEquals(1, queryInt(reopened.databasePath(), """
                SELECT revision FROM companion_lifecycle
                """));
        assertEquals("death", queryString(reopened.databasePath(), """
                SELECT snapshot_kind FROM companion_snapshot
                WHERE is_current = 1
                """));
        assertEquals("RELEASED", queryString(reopened.databasePath(), """
                SELECT state FROM persistence_quarantine
                """));
        assertEquals("RESOLVED", queryString(reopened.databasePath(), """
                SELECT state FROM persistence_incident
                """));
        assertEquals(sourceBefore, evidence(historicalDirectory));
    }

    @Test
    void changedTargetProfileCannotReleaseAnExistingQuarantine()
            throws Exception {
        Path targetDirectory = Files.createDirectory(
                tempDir.resolve("current")
        );
        Path historicalDirectory = Files.createDirectory(
                tempDir.resolve("historical")
        );
        Path source = PersistenceFiles.legacyDatabase(historicalDirectory);
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-conflicting-flags.sql",
                source
        );
        PublicPersistenceTargetOpener opener =
                new PublicPersistenceTargetOpener(() -> -100);
        PublicPersistenceTarget imported = opener.open(
                targetDirectory,
                List.of(targetDirectory, historicalDirectory)
        );
        simulateOlderQuarantinedImport(imported.databasePath());
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + imported.databasePath()
        ); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE companion_profile
                    SET metadata_revision = 1
                    """);
        }

        opener.open(
                targetDirectory,
                List.of(targetDirectory, historicalDirectory)
        );

        assertEquals("UNRESOLVED", queryString(
                imported.databasePath(),
                "SELECT lifecycle_state FROM companion_lifecycle"
        ));
        assertEquals("ACTIVE", queryString(imported.databasePath(), """
                SELECT state FROM persistence_quarantine
                """));
        assertEquals(0, queryInt(imported.databasePath(), """
                SELECT COUNT(*) FROM companion_snapshot WHERE is_current = 1
                """));
    }

    @Test
    void exactSourceCanRestoreAnImportedCoopRelation() throws Exception {
        String profileId = "20000000-0000-0000-0000-000000000005";
        Path targetDirectory = Files.createDirectory(
                tempDir.resolve("current")
        );
        Path historicalDirectory = Files.createDirectory(
                tempDir.resolve("historical")
        );
        Path source = PersistenceFiles.legacyDatabase(historicalDirectory);
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-representative.sql",
                source
        );
        addStaleCaptureFlagToCoopSource(source, profileId);
        PublicPersistenceTargetOpener opener =
                new PublicPersistenceTargetOpener(() -> -100);
        PublicPersistenceTarget imported = opener.open(
                targetDirectory,
                List.of(targetDirectory, historicalDirectory)
        );
        simulateOlderCoopQuarantine(imported.databasePath(), profileId);

        opener.open(
                targetDirectory,
                List.of(targetDirectory, historicalDirectory)
        );

        assertEquals("COOP", queryString(imported.databasePath(), """
                SELECT lifecycle_state FROM companion_lifecycle
                WHERE profile_id = '20000000-0000-0000-0000-000000000005'
                """));
        assertEquals(1, queryInt(imported.databasePath(), """
                SELECT COUNT(*) FROM coop_residency
                WHERE profile_id = '20000000-0000-0000-0000-000000000005'
                """));
        assertEquals("coop", queryString(imported.databasePath(), """
                SELECT snapshot_kind FROM companion_snapshot
                WHERE profile_id =
                    '20000000-0000-0000-0000-000000000005'
                  AND is_current = 1
                """));
        assertEquals("RELEASED", queryString(imported.databasePath(), """
                SELECT state FROM persistence_quarantine
                WHERE scope_key =
                    '20000000-0000-0000-0000-000000000005'
                """));
    }

    @Test
    void inactiveNewerHistoryCannotReleaseAFlagQuarantine()
            throws Exception {
        Path targetDirectory = Files.createDirectory(
                tempDir.resolve("current")
        );
        Path historicalDirectory = Files.createDirectory(
                tempDir.resolve("historical")
        );
        Path source = PersistenceFiles.legacyDatabase(historicalDirectory);
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-conflicting-flags.sql",
                source
        );
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source
        ); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO npc_snapshots(
                        profile_id, snapshot_type, snapshot_version,
                        payload_json, is_active, created_at_ms
                    ) VALUES (
                        '21000000-0000-0000-0000-000000000001',
                        'lost', 1, '{"lostAtMs":300}', 0, 300
                    )
                    """);
            statement.executeUpdate("""
                    UPDATE profile_states SET updated_at_ms = 300
                    WHERE profile_id =
                        '21000000-0000-0000-0000-000000000001'
                    """);
        }
        PublicPersistenceTargetOpener opener =
                new PublicPersistenceTargetOpener(() -> -100);
        PublicPersistenceTarget imported = opener.open(
                targetDirectory,
                List.of(targetDirectory, historicalDirectory)
        );

        opener.open(
                targetDirectory,
                List.of(targetDirectory, historicalDirectory)
        );

        assertEquals("UNRESOLVED", queryString(
                imported.databasePath(),
                "SELECT lifecycle_state FROM companion_lifecycle"
        ));
        assertEquals("ACTIVE", queryString(imported.databasePath(), """
                SELECT state FROM persistence_quarantine
                """));
        assertEquals(0, queryInt(imported.databasePath(), """
                SELECT COUNT(*) FROM companion_snapshot WHERE is_current = 1
                """));
    }

    private void addStaleCaptureFlagToCoopSource(
            Path source,
            String profileId
    ) throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source
        ); var snapshot = connection.prepareStatement("""
                INSERT INTO npc_snapshots(
                    profile_id, snapshot_type, snapshot_version,
                    payload_json, is_active, created_at_ms
                ) VALUES (?, 'capture', 1, '{"capturedAtMs":270}', 1, 270)
                """); var state = connection.prepareStatement("""
                UPDATE profile_states SET capture_active = 1
                WHERE profile_id = ?
                """)) {
            snapshot.setString(1, profileId);
            snapshot.executeUpdate();
            state.setString(1, profileId);
            state.executeUpdate();
        }
    }

    private void simulateOlderCoopQuarantine(
            Path database,
            String profileId
    ) throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database
        )) {
            connection.setAutoCommit(false);
            try (var snapshots = connection.prepareStatement("""
                    UPDATE companion_snapshot SET is_current = 0
                    WHERE profile_id = ?
                    """); var lifecycle = connection.prepareStatement("""
                    UPDATE companion_lifecycle
                    SET lifecycle_state = 'UNRESOLVED',
                        location_kind = 'UNRESOLVED', location_key = NULL,
                        revision = 0,
                        quarantine_incident_id =
                            '91000000-0000-0000-0000-000000000002'
                    WHERE profile_id = ?
                    """); var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO persistence_incident(
                            incident_id, failure_kind, failure_code, state,
                            summary, evidence_json, created_at_ms,
                            resolved_at_ms
                        ) VALUES (
                            '91000000-0000-0000-0000-000000000002',
                            'IMPORT_CONFLICT',
                            'MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS',
                            'OPEN', 'Legacy coop import conflict', '{}',
                            -100, NULL
                        )
                        """);
                statement.executeUpdate("""
                        DELETE FROM coop_residency
                        WHERE profile_id =
                            '20000000-0000-0000-0000-000000000005'
                        """);
                statement.executeUpdate("""
                        UPDATE coop_slot SET residency_revision = 0
                        WHERE coop_id = 'fixture-coop'
                        """);
                snapshots.setString(1, profileId);
                snapshots.executeUpdate();
                lifecycle.setString(1, profileId);
                lifecycle.executeUpdate();
                try (var quarantine = connection.prepareStatement("""
                        INSERT INTO persistence_quarantine(
                            scope_type, scope_key, incident_id, state,
                            reason_code, created_at_ms, released_at_ms
                        ) VALUES (
                            'PROFILE', ?,
                            '91000000-0000-0000-0000-000000000002',
                            'ACTIVE',
                            'MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS',
                            -100, NULL
                        )
                        """)) {
                    quarantine.setString(1, profileId);
                    quarantine.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void simulateOlderQuarantinedImport(Path database)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database
        ); var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE companion_snapshot SET is_current = 0"
            );
            statement.executeUpdate("""
                    INSERT INTO persistence_incident(
                        incident_id, failure_kind, failure_code, state,
                        summary, evidence_json, created_at_ms, resolved_at_ms
                    ) VALUES (
                        '91000000-0000-0000-0000-000000000001',
                        'IMPORT_CONFLICT',
                        'MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS',
                        'OPEN',
                        'Legacy import conflict',
                        '{"conflicts":["MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS"]}',
                        -100,
                        NULL
                    )
                    """);
            statement.executeUpdate("""
                    UPDATE companion_lifecycle
                    SET lifecycle_state = 'UNRESOLVED',
                        location_kind = 'UNRESOLVED',
                        location_key = NULL,
                        world_key = NULL,
                        revision = 0,
                        active_operation_id = NULL,
                        last_reconciled_generation = 0,
                        quarantine_incident_id =
                            '91000000-0000-0000-0000-000000000001'
                    """);
            statement.executeUpdate("""
                    INSERT INTO persistence_quarantine(
                        scope_type, scope_key, incident_id, state,
                        reason_code, created_at_ms, released_at_ms
                    ) SELECT
                        'PROFILE', profile_id,
                        '91000000-0000-0000-0000-000000000001',
                        'ACTIVE',
                        'MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS',
                        -100,
                        NULL
                    FROM companion_profile
                    """);
        }
    }

    private void executeFixture(Path database, String fixture)
            throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/persistence-consolidation/" + fixture
        )) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture " + fixture);
            }
            sql = new String(
                    stream.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8
            );
        }
        Class.forName("org.sqlite.JDBC");
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + database
        ); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void assertNoCreatingAttempts() {
        assertNoCreatingAttempts(tempDir);
    }

    private void assertNoCreatingAttempts(Path directory) {
        try (var paths = Files.list(directory)) {
            assertTrue(paths.noneMatch(path ->
                    path.getFileName().toString().contains(".creating.")));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private int queryInt(Path database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database
        ); var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    private String queryString(Path database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database
        ); var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private Map<String, FileEvidence> evidence(Path directory)
            throws Exception {
        HashMap<String, FileEvidence> result = new HashMap<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                result.put(
                        file.getFileName().toString(),
                        new FileEvidence(
                                Files.size(file),
                                Files.getLastModifiedTime(file),
                                sha256(file)
                        )
                );
            }
        }
        return Map.copyOf(result);
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record FileEvidence(
            long size,
            FileTime modifiedAt,
            String sha256
    ) {
    }
}
