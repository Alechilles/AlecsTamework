package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** End-to-end public import, refusal, idempotency, and non-mutation acceptance tests. */
class PublicPersistenceImporterTest {
    @TempDir
    Path tempDir;

    private final PublicPersistenceImporter importer =
            new PublicPersistenceImporter(() -> -7_000);

    @Test
    void importsEveryPublicFixtureIntoAVerifiedFreshLineage() throws Exception {
        Map<String, Integer> fixtures = Map.of(
                "public-v2-empty.sql", 0,
                "public-v3-extension-data.sql", 1,
                "public-v4-representative.sql", 6
        );
        for (Map.Entry<String, Integer> fixture : fixtures.entrySet()) {
            ImportCase testCase = importCase(fixture.getKey());
            Map<String, FileEvidence> sourceBefore = sourceEvidence(testCase.source());

            PublicImportResult.Imported result = assertInstanceOf(
                    PublicImportResult.Imported.class,
                    importer.importSource(testCase.source(), testCase.target()),
                    fixture.getKey()
            );

            assertEquals(testCase.target(), result.targetPath());
            assertTrue(Files.isRegularFile(testCase.target()));
            assertTrue(result.reportPath().isPresent());
            assertEquals(sourceBefore, sourceEvidence(testCase.source()));
            assertEquals(fixture.getValue().longValue(),
                    count(testCase.target(), "companion_profile"));
            assertEquals(fixture.getValue().longValue(),
                    count(testCase.target(), "companion_lifecycle"));
            assertEquals(1, count(testCase.target(), "schema_history"));
            assertEquals(1, count(testCase.target(), "import_manifest"));
            assertEquals("ok", queryString(testCase.target(), "PRAGMA integrity_check"));
            assertEquals(0, rowCount(testCase.target(), "PRAGMA foreign_key_check"));
            assertNoTemporaryTargets(testCase.directory());
        }
    }

    @Test
    void boundedV4ConflictPublishesOnlyUnresolvedQuarantinedEvidence() throws Exception {
        ImportCase testCase = importCase("public-v4-conflicting-flags.sql");

        PublicImportResult.Imported result = assertInstanceOf(
                PublicImportResult.Imported.class,
                importer.importSource(testCase.source(), testCase.target())
        );

        assertTrue(result.reportPath().isPresent());
        assertEquals(1, count(testCase.target(), "persistence_incident"));
        assertEquals(1, count(testCase.target(), "persistence_quarantine"));
        assertEquals("UNRESOLVED", queryString(
                testCase.target(), "SELECT lifecycle_state FROM companion_lifecycle"));
        assertEquals(0, queryLong(
                testCase.target(), "SELECT SUM(is_current) FROM companion_snapshot"));
        assertTrue(queryString(testCase.target(),
                "SELECT evidence_json FROM persistence_incident")
                .contains("MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS"));
    }

    @Test
    void publicSnapshotRevisionCountersDoNotBecomeTargetPayloadVersions() throws Exception {
        ImportCase testCase = importCase("public-v4-representative.sql");

        assertInstanceOf(
                PublicImportResult.Imported.class,
                importer.importSource(testCase.source(), testCase.target())
        );

        assertEquals(3, queryLong(testCase.source(), """
                SELECT COUNT(*) FROM npc_snapshots
                WHERE snapshot_type IN ('capture', 'death', 'lost')
                  AND snapshot_version > 1
                """));
        assertEquals(3, queryLong(testCase.target(), """
                SELECT COUNT(*) FROM companion_snapshot
                WHERE snapshot_kind IN ('capture', 'death', 'lost')
                  AND payload_version = 1
                """));
        assertEquals(0, queryLong(testCase.target(), """
                SELECT COUNT(*) FROM companion_snapshot
                WHERE snapshot_kind IN ('capture', 'death', 'lost')
                  AND source_lifecycle_revision <> 0
                """));
    }

    @Test
    void refusesCorruptAndDevelopmentSourcesWithNoTargetSideEffects() throws Exception {
        Map<String, ExpectedRefusal> fixtures = Map.of(
                "public-v4-corrupt-foreign-key.sql",
                new ExpectedRefusal(LegacySourceKind.MALFORMED,
                        "PUBLIC_FOREIGN_KEY_VIOLATION"),
                "development-v5-marker.sql",
                new ExpectedRefusal(LegacySourceKind.DEVELOPMENT_V5_TO_V9,
                        "UNSUPPORTED_DEVELOPMENT_SCHEMA"),
                "development-v9-marker.sql",
                new ExpectedRefusal(LegacySourceKind.DEVELOPMENT_V5_TO_V9,
                        "UNSUPPORTED_DEVELOPMENT_SCHEMA")
        );
        for (Map.Entry<String, ExpectedRefusal> fixture : fixtures.entrySet()) {
            ImportCase testCase = importCase(fixture.getKey());
            Map<String, FileEvidence> allBefore = directoryEvidence(testCase.directory());

            PublicImportResult.Refused result = assertInstanceOf(
                    PublicImportResult.Refused.class,
                    importer.importSource(testCase.source(), testCase.target())
            );

            assertEquals(fixture.getValue().kind(), result.sourceKind());
            assertEquals(fixture.getValue().code(), result.code());
            assertEquals(allBefore, directoryEvidence(testCase.directory()));
            assertFalse(Files.exists(testCase.target()));
            assertFalse(Files.exists(testCase.directory().resolve(".tamework-import.lock")));
        }
    }

    @Test
    void repeatedImportRecognizesTheExactExistingTargetWithoutChangingIt() throws Exception {
        ImportCase testCase = importCase("public-v4-representative.sql");
        PublicImportResult.Imported first = assertInstanceOf(
                PublicImportResult.Imported.class,
                importer.importSource(testCase.source(), testCase.target())
        );
        FileEvidence before = fileEvidence(testCase.target());

        PublicImportResult.AlreadyImported second = assertInstanceOf(
                PublicImportResult.AlreadyImported.class,
                importer.importSource(testCase.source(), testCase.target())
        );

        assertEquals(first.importId(), second.importId());
        assertEquals(before, fileEvidence(testCase.target()));
        assertEquals(1, count(testCase.target(), "import_manifest"));
    }

    @Test
    void independentImportsOfTheSameSourceProduceIdenticalLogicalTargets() throws Exception {
        ImportCase testCase = importCase("public-v4-representative.sql");
        Path secondTarget = testCase.directory().resolve("second-tamework-state.sqlite");

        PublicImportResult.Imported first = assertInstanceOf(
                PublicImportResult.Imported.class,
                importer.importSource(testCase.source(), testCase.target())
        );
        PublicImportResult.Imported second = assertInstanceOf(
                PublicImportResult.Imported.class,
                importer.importSource(testCase.source(), secondTarget)
        );

        assertEquals(first.importId(), second.importId());
        assertEquals(logicalRows(testCase.target()), logicalRows(secondTarget));
    }

    @Test
    void representativeImportStaysWithinDocumentedOfflineFixtureBudget() throws Exception {
        ImportCase testCase = importCase("public-v4-representative.sql");

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertInstanceOf(
                        PublicImportResult.Imported.class,
                        importer.importSource(testCase.source(), testCase.target())
                )
        );
        assertTrue(Files.size(testCase.target()) <= 2L * 1024 * 1024,
                "representative target exceeded 2 MiB");
    }

    @Test
    void differentPublicSourceNeverMergesIntoAnExistingTarget() throws Exception {
        ImportCase first = importCase("public-v2-empty.sql");
        assertInstanceOf(PublicImportResult.Imported.class,
                importer.importSource(first.source(), first.target()));
        FileEvidence targetBefore = fileEvidence(first.target());
        Path differentSource = first.directory().resolve("different-tamework.sqlite");
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v3-extension-data.sql", differentSource);

        PublicImportResult.Refused refusal = assertInstanceOf(
                PublicImportResult.Refused.class,
                importer.importSource(differentSource, first.target())
        );

        assertEquals("EXISTING_TARGET_MISMATCH", refusal.code());
        assertEquals(targetBefore, fileEvidence(first.target()));
        assertEquals(0, count(first.target(), "companion_profile"));
    }

    @Test
    void admissionLockUsesBackupExcludedLockFileAndStillSerializesAcquisition() throws Exception {
        Path lockPath = tempDir
                .resolve(".tamework-import-lock")
                .resolve("LOCK");

        try (ImportAdmissionLock ignored = ImportAdmissionLock.acquire(tempDir)) {
            assertTrue(Files.isRegularFile(lockPath));
            try (var files = Files.walk(tempDir)) {
                assertFalse(files.filter(Files::isRegularFile)
                        .filter(path -> !path.endsWith("LOCK"))
                        .anyMatch(lockPath::equals));
            }

            IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> ImportAdmissionLock.acquire(tempDir)
            );
            assertEquals("persistence_import_lock_unavailable", failure.getMessage());
        }
    }

    @Test
    void activeAdmissionLockPreventsASecondPublisherFromStarting() throws Exception {
        ImportCase testCase = importCase("public-v4-representative.sql");
        try (ImportAdmissionLock ignored = ImportAdmissionLock.acquire(testCase.directory())) {
            PublicImportResult.Failed failure = assertInstanceOf(
                    PublicImportResult.Failed.class,
                    importer.importSource(testCase.source(), testCase.target())
            );
            assertEquals("PUBLIC_IMPORT_PUBLICATION_FAILED", failure.code());
            assertFalse(Files.exists(testCase.target()));
            assertNoTemporaryTargets(testCase.directory());
        }
    }

    @Test
    void restartDiscardsIncompleteOwnedAttemptAndBuildsANewVerifiedTarget() throws Exception {
        ImportCase testCase = importCase("public-v4-representative.sql");
        Path incomplete = temporaryTarget(testCase.target());
        new SqliteSchemaV1Manager(
                new SqliteConnectionFactory(incomplete), () -> -7_000
        ).initialize();

        PublicImportResult.Imported result = assertInstanceOf(
                PublicImportResult.Imported.class,
                importer.importSource(testCase.source(), testCase.target())
        );

        assertTrue(Files.isRegularFile(result.targetPath()));
        assertFalse(Files.exists(incomplete));
        assertNoTemporaryTargets(testCase.directory());
        assertEquals(6, count(testCase.target(), "companion_profile"));
    }

    @Test
    void restartPublishesCommittedButUnpublishedMatchingAttempt() throws Exception {
        ImportCase testCase = importCase("public-v4-representative.sql");
        Path committed = temporaryTarget(testCase.target());
        buildCommittedAttempt(testCase.source(), committed);

        PublicImportResult.Imported result = assertInstanceOf(
                PublicImportResult.Imported.class,
                importer.importSource(testCase.source(), testCase.target())
        );

        assertFalse(Files.exists(committed));
        assertEquals(1, count(result.targetPath(), "import_manifest"));
        assertNoTemporaryTargets(testCase.directory());
    }

    @Test
    void matchingPublishedTargetRecreatesAMissingExternalReport() throws Exception {
        ImportCase testCase = importCase("public-v4-representative.sql");
        PublicImportResult.Imported first = assertInstanceOf(
                PublicImportResult.Imported.class,
                importer.importSource(testCase.source(), testCase.target())
        );
        Path report = first.reportPath().orElseThrow();
        Files.delete(report);

        assertInstanceOf(
                PublicImportResult.AlreadyImported.class,
                importer.importSource(testCase.source(), testCase.target())
        );

        assertTrue(Files.isRegularFile(report));
    }

    @Test
    void sourceAndTargetPathCollisionIsRejectedBeforeAnyWrite() throws Exception {
        ImportCase testCase = importCase("public-v2-empty.sql");
        FileEvidence before = fileEvidence(testCase.source());

        PublicImportResult.Failed failure = assertInstanceOf(
                PublicImportResult.Failed.class,
                importer.importSource(testCase.source(), testCase.source())
        );

        assertEquals("SOURCE_TARGET_PATH_COLLISION", failure.code());
        assertEquals(before, fileEvidence(testCase.source()));
    }

    private ImportCase importCase(String resource) throws Exception {
        Path directory = Files.createDirectory(
                tempDir.resolve(resource.replace(".sql", ""))
        );
        Path source = directory.resolve("tamework.sqlite");
        Path target = directory.resolve("tamework-state.sqlite");
        PersistenceConsolidationFixtureDatabase.materialize(resource, source);
        return new ImportCase(directory, source, target);
    }

    private Path temporaryTarget(Path target) {
        return target.resolveSibling(
                target.getFileName() + ".importing." + java.util.UUID.randomUUID()
        );
    }

    private void buildCommittedAttempt(Path source, Path temporary) throws Exception {
        Path workspace = Files.createTempDirectory(tempDir, "committed-attempt-");
        try (SqliteReadOnlySnapshotter.Snapshot snapshot =
                     new SqliteReadOnlySnapshotter().create(source, workspace)) {
            LegacySourceClassification classification =
                    new LegacySourceClassifier().classifySnapshot(snapshot);
            LegacyPublicData data;
            try (Connection connection =
                         new SqliteConnectionFactory(snapshot.path()).openReadConnection()) {
                data = new LegacyPublicDataReader().read(
                        connection, classification.schemaVersion());
            }
            PublicImportPlan plan = new PublicImportPlanner().plan(
                    data, snapshot.fingerprint(), -7_000);
            PublicImportManifest manifest = new PublicImportManifestFactory().create(
                    plan, snapshot.fingerprint(), classification.schemaVersion(),
                    source.getFileName().toString(), -7_000);
            SqliteConnectionFactory connections = new SqliteConnectionFactory(temporary);
            new SqliteSchemaV1Manager(connections, () -> -7_000).initialize();
            try (Connection connection = connections.openWriterConnection()) {
                connection.setAutoCommit(false);
                new PublicImportSqlWriter().write(connection, plan, manifest);
                new PublicImportVerifier().verify(connection, plan, manifest);
                connection.commit();
            }
        } finally {
            Files.deleteIfExists(workspace);
        }
    }

    private Map<String, FileEvidence> sourceEvidence(Path source) throws Exception {
        HashMap<String, FileEvidence> evidence = new HashMap<>();
        for (Path path : new Path[]{
                source,
                source.resolveSibling(source.getFileName() + "-wal"),
                source.resolveSibling(source.getFileName() + "-shm")
        }) {
            if (Files.isRegularFile(path)) {
                evidence.put(path.getFileName().toString(), fileEvidence(path));
            }
        }
        return Map.copyOf(evidence);
    }

    private Map<String, FileEvidence> directoryEvidence(Path directory) throws Exception {
        HashMap<String, FileEvidence> evidence = new HashMap<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                evidence.put(path.getFileName().toString(), fileEvidence(path));
            }
        }
        return Map.copyOf(evidence);
    }

    private FileEvidence fileEvidence(Path path) throws Exception {
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
        return new FileEvidence(
                Files.size(path),
                Files.getLastModifiedTime(path),
                HexFormat.of().formatHex(digest.digest())
        );
    }

    private void assertNoTemporaryTargets(Path directory) throws Exception {
        Set<String> names;
        try (var files = Files.list(directory)) {
            names = files.map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
        assertFalse(names.stream().anyMatch(name -> name.contains(".importing.")), names.toString());
        assertFalse(names.stream().anyMatch(name -> name.endsWith(".writing")), names.toString());
    }

    private List<String> logicalRows(Path database) throws Exception {
        ArrayList<String> rows = new ArrayList<>();
        List<String> tables = new ArrayList<>(
                com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager
                        .requiredTables()
        );
        tables.sort(String::compareTo);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (ResultSet result = statement.executeQuery(
                        "SELECT * FROM " + table + " ORDER BY rowid"
                )) {
                    ResultSetMetaData metadata = result.getMetaData();
                    while (result.next()) {
                        StringBuilder row = new StringBuilder(table);
                        for (int column = 1; column <= metadata.getColumnCount(); column++) {
                            row.append('|').append(metadata.getColumnName(column))
                                    .append('=').append(result.getString(column));
                        }
                        rows.add(row.toString());
                    }
                }
            }
        }
        return List.copyOf(rows);
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

    private record ImportCase(Path directory, Path source, Path target) {
    }

    private record ExpectedRefusal(LegacySourceKind kind, String code) {
    }

    private record FileEvidence(long size, FileTime modifiedAt, String sha256) {
    }
}
