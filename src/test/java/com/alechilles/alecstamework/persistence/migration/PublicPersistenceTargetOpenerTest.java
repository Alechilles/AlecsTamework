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
