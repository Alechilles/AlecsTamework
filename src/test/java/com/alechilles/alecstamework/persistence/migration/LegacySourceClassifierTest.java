package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV2Manager;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-shape and non-mutation tests for replacement startup classification. */
class LegacySourceClassifierTest {
    private static final String RESOURCE_ROOT = "/persistence-consolidation/";

    @TempDir
    Path tempDir;

    private final LegacySourceClassifier classifier = new LegacySourceClassifier();

    @Test
    void classifiesEverySupportedPublicFixtureFromAConsistentSnapshot() throws Exception {
        assertFixture("public-v2-empty.sql", LegacySourceKind.PUBLIC_V2, 2);
        assertFixture("public-v3-extension-data.sql", LegacySourceKind.PUBLIC_V3, 3);
        assertFixture("public-v4-representative.sql", LegacySourceKind.PUBLIC_V4, 4);
        assertFixture("public-v4-conflicting-flags.sql", LegacySourceKind.PUBLIC_V4, 4);
    }

    @Test
    void publicLegacyDatImportMarkerDoesNotMasqueradeAsANewerSchema() throws Exception {
        Path source = tempDir.resolve("public-v2-from-dat.sqlite");
        materialize("public-v2-empty.sql", source);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO schema_migrations(version, name, applied_at_ms)
                    VALUES (2001, 'legacy_dat_import_v2', 1001)
                    """);
        }

        LegacySourceClassification classification =
                classifier.classify(source, tempDir.resolve("work-v2-dat"));

        assertEquals(LegacySourceKind.PUBLIC_V2, classification.kind());
        assertEquals(2, classification.schemaVersion());
    }

    @Test
    void refusesDevelopmentFixturesWithoutCreatingAReplacementTarget() throws Exception {
        Map<String, Integer> fixtures = Map.of(
                "development-v5-marker.sql", 5,
                "development-v9-marker.sql", 9
        );
        for (Map.Entry<String, Integer> fixture : fixtures.entrySet()) {
            String resource = fixture.getKey();
            Path caseDirectory = Files.createDirectory(tempDir.resolve(resource));
            Path source = caseDirectory.resolve("tamework.sqlite");
            materialize(resource, source);
            Map<String, FileEvidence> before = evidence(caseDirectory);

            LegacySourceClassification classification =
                    classifier.classify(source, caseDirectory.resolve("classification-work"));

            assertEquals(LegacySourceKind.DEVELOPMENT_V5_TO_V9, classification.kind());
            assertEquals(fixture.getValue(), classification.schemaVersion());
            assertEquals("UNSUPPORTED_DEVELOPMENT_SCHEMA", classification.diagnosticCode());
            assertEquals(before, evidence(caseDirectory));
            assertFalse(Files.exists(caseDirectory.resolve("tamework-state.sqlite")));
            assertFalse(Files.exists(caseDirectory.resolve("persistence-engine.json")));
        }
    }

    @Test
    void malformedForeignKeysAndBytesFailClosed() throws Exception {
        Path corruptForeignKey = tempDir.resolve("corrupt-fk.sqlite");
        materialize("public-v4-corrupt-foreign-key.sql", corruptForeignKey);
        LegacySourceClassification corrupt =
                classifier.classify(corruptForeignKey, tempDir.resolve("work-fk"));
        assertEquals(LegacySourceKind.MALFORMED, corrupt.kind());
        assertEquals("PUBLIC_FOREIGN_KEY_VIOLATION", corrupt.diagnosticCode());

        Path invalidBytes = tempDir.resolve("invalid.sqlite");
        Files.writeString(invalidBytes, "not a SQLite database", StandardCharsets.UTF_8);
        LegacySourceClassification invalid =
                classifier.classify(invalidBytes, tempDir.resolve("work-invalid"));
        assertEquals(LegacySourceKind.MALFORMED, invalid.kind());
        assertEquals("SOURCE_SNAPSHOT_FAILED", invalid.diagnosticCode());
    }

    @Test
    void missingAndUnknownSourcesDoNotCreateFilesAtTheSourcePath() throws Exception {
        Path missing = tempDir.resolve("missing").resolve("tamework.sqlite");
        LegacySourceClassification absent =
                classifier.classify(missing, tempDir.resolve("unused-work"));
        assertEquals(LegacySourceKind.NO_SOURCE, absent.kind());
        assertFalse(Files.exists(missing));
        assertFalse(Files.exists(tempDir.resolve("unused-work")));

        Path unknown = tempDir.resolve("unknown.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + unknown);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE mystery(id INTEGER PRIMARY KEY)");
        }
        LegacySourceClassification ambiguous =
                classifier.classify(unknown, tempDir.resolve("work-unknown"));
        assertEquals(LegacySourceKind.AMBIGUOUS, ambiguous.kind());
        assertEquals("MIGRATION_HISTORY_MISSING", ambiguous.diagnosticCode());
    }

    @Test
    void recognizesOnlyAnExactlyVerifiedReplacementLineage() throws Exception {
        Path replacement = tempDir.resolve("tamework-state.sqlite");
        SqliteSchemaV1Manager manager =
                new SqliteSchemaV1Manager(new SqliteConnectionFactory(replacement), () -> 100);
        manager.initialize();

        LegacySourceClassification valid =
                classifier.classify(replacement, tempDir.resolve("work-replacement"));
        assertEquals(LegacySourceKind.REPLACEMENT_V1, valid.kind());

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + replacement);
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE schema_history SET schema_hash = '" + "0".repeat(64) + "'");
        }
        LegacySourceClassification tampered =
                classifier.classify(replacement, tempDir.resolve("work-tampered"));
        assertEquals(LegacySourceKind.AMBIGUOUS, tampered.kind());
        assertEquals("REPLACEMENT_LINEAGE_INVALID", tampered.diagnosticCode());
    }

    @Test
    void classifiesV2SeparatelyFromTheV1RollbackLineage() throws Exception {
        Path replacement = tempDir.resolve("tamework-state-v2.sqlite");
        SqliteSchemaV2Manager manager = new SqliteSchemaV2Manager(
                new SqliteConnectionFactory(replacement), () -> 100
        );
        manager.initialize();

        LegacySourceClassification valid = classifier.classify(
                replacement, tempDir.resolve("work-replacement-v2")
        );

        assertEquals(LegacySourceKind.REPLACEMENT_V2, valid.kind());
        assertEquals(2, valid.schemaVersion());
        assertEquals("REPLACEMENT_V2", valid.diagnosticCode());
    }

    @Test
    void readOnlyBackupIncludesCommittedWalContentWithoutChangingSourceArtifacts() throws Exception {
        Path sourceDirectory = Files.createDirectory(tempDir.resolve("wal-source"));
        Path source = sourceDirectory.resolve("tamework.sqlite");
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + source);
             Statement statement = writer.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("CREATE TABLE durable_value(value TEXT NOT NULL)");
            statement.execute("INSERT INTO durable_value(value) VALUES ('from-wal')");
            assertTrue(Files.exists(source.resolveSibling("tamework.sqlite-wal")));
            Map<String, FileEvidence> before = evidence(sourceDirectory);

            try (SqliteReadOnlySnapshotter.Snapshot snapshot =
                         new SqliteReadOnlySnapshotter().create(
                                 source,
                                 tempDir.resolve("snapshot-work")
                         );
                 Connection copy = DriverManager.getConnection("jdbc:sqlite:" + snapshot.path());
                 Statement query = copy.createStatement();
                 ResultSet row = query.executeQuery("SELECT value FROM durable_value")) {
                assertTrue(row.next());
                assertEquals("from-wal", row.getString(1));
            }

            assertEquals(before, evidence(sourceDirectory));
        }
    }

    private void assertFixture(String resource, LegacySourceKind kind, int version) throws Exception {
        Path source = tempDir.resolve(resource + ".sqlite");
        materialize(resource, source);
        LegacySourceClassification classification =
                classifier.classify(source, tempDir.resolve(resource + "-work"));
        assertEquals(kind, classification.kind(), resource);
        assertEquals(version, classification.schemaVersion(), resource);
        assertTrue(classification.fingerprint().isPresent(), resource);
        assertEquals(64, classification.fingerprint().orElseThrow().snapshotSha256().length());
    }

    private Map<String, FileEvidence> evidence(Path directory) throws Exception {
        HashMap<String, FileEvidence> result = new HashMap<>();
        if (!Files.isDirectory(directory)) {
            return Map.of();
        }
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

    private void materialize(String resource, Path database) throws Exception {
        String sql = expand(resource, new HashSet<>());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=OFF");
            for (String command : splitStatements(sql)) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    private String expand(String resource, Set<String> stack) throws Exception {
        assertTrue(stack.add(resource), "fixture include cycle: " + resource);
        StringBuilder expanded = new StringBuilder();
        for (String line : readResource(resource).replace("\r\n", "\n")
                .replace('\r', '\n').split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-- @include ")) {
                expanded.append(expand(trimmed.substring("-- @include ".length()).trim(), stack));
            } else {
                expanded.append(line).append('\n');
            }
        }
        stack.remove(resource);
        return expanded.toString();
    }

    private List<String> splitStatements(String sql) {
        ArrayList<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char value = sql.charAt(index);
            current.append(value);
            if (value == '\'' && quoted && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '\'') {
                current.append(sql.charAt(++index));
            } else if (value == '\'') {
                quoted = !quoted;
            } else if (value == ';' && !quoted) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private String readResource(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE_ROOT + resource)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing fixture: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
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

    private record FileEvidence(long size, FileTime modifiedAt, String sha256) {
    }
}
