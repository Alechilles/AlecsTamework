package com.alechilles.alecstamework.persistence.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Golden and fail-closed coverage for the released five-file legacy DAT contract. */
class LegacyDatPersistenceImporterTest {
    private static final String RESOURCE_ROOT =
            "/persistence-consolidation/legacy-dat/representative/";
    private static final String NPC = "00000000-0000-0000-0000-000000000201";
    private static final String OWNER_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String OWNER_TWO = "10000000-0000-0000-0000-000000000002";
    private static final String TOOL_TOKEN =
            "MzAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDAx";

    @TempDir
    Path tempDir;

    @Test
    void representativeBundlePublishesGoldenRowsWithoutMutatingSources() throws Exception {
        Path source = tempDir.resolve("source");
        Map<String, byte[]> original = copyRepresentative(source);
        Path target = tempDir.resolve("tamework-state.sqlite");
        LegacyDatPersistenceImporter importer =
                new LegacyDatPersistenceImporter(() -> -7_000L);

        PublicImportResult.Imported first = assertInstanceOf(
                PublicImportResult.Imported.class,
                importer.importDirectory(source, target)
        );
        assertSourceBytes(source, original);
        assertGoldenTarget(target);

        PublicImportResult.AlreadyImported replay = assertInstanceOf(
                PublicImportResult.AlreadyImported.class,
                importer.importDirectory(source, target)
        );
        assertEquals(first.importId(), replay.importId());
        assertSourceBytes(source, original);
    }

    @Test
    void retiredCoopSnapshotCacheOnlyChangesFingerprintAndNeverFabricatesState()
            throws Exception {
        Path firstSource = tempDir.resolve("opaque-one");
        Path secondSource = tempDir.resolve("opaque-two");
        Files.createDirectories(firstSource);
        Files.createDirectories(secondSource);
        Files.writeString(
                firstSource.resolve(LegacyDatBundleSnapshot.RETIRED_COOP_SNAPSHOTS_FILE),
                "opaque-one\tunparsed",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                secondSource.resolve(LegacyDatBundleSnapshot.RETIRED_COOP_SNAPSHOTS_FILE),
                "opaque-two\tunparsed",
                StandardCharsets.UTF_8
        );
        Path firstTarget = tempDir.resolve("opaque-one.sqlite");
        Path secondTarget = tempDir.resolve("opaque-two.sqlite");
        LegacyDatPersistenceImporter importer =
                new LegacyDatPersistenceImporter(() -> -6_000L);

        assertInstanceOf(PublicImportResult.Imported.class,
                importer.importDirectory(firstSource, firstTarget));
        assertInstanceOf(PublicImportResult.Imported.class,
                importer.importDirectory(secondSource, secondTarget));

        assertEmptyCanonicalState(firstTarget);
        assertEmptyCanonicalState(secondTarget);
        assertNotEquals(
                queryString(firstTarget, "SELECT source_sha256 FROM import_manifest"),
                queryString(secondTarget, "SELECT source_sha256 FROM import_manifest")
        );
        assertTrue(queryString(firstTarget,
                "SELECT source_snapshot_name FROM import_manifest")
                .contains("CoopResidentSnapshots.dat"));
    }

    @Test
    void malformedRowsRefuseBeforeCreatingTarget() throws Exception {
        Path source = tempDir.resolve("malformed");
        Files.createDirectories(source);
        Path captures = source.resolve(LegacyDatBundleSnapshot.CAPTURES_FILE);
        Files.writeString(captures, String.join("\t",
                NPC, OWNER_ONE, TOOL_TOKEN, "", "", "", "", "not-a-timestamp"));
        byte[] original = Files.readAllBytes(captures);
        Path target = tempDir.resolve("malformed.sqlite");

        PublicImportResult.Refused refused = assertInstanceOf(
                PublicImportResult.Refused.class,
                new LegacyDatPersistenceImporter().importDirectory(source, target)
        );

        assertEquals(LegacySourceKind.LEGACY_DAT, refused.sourceKind());
        assertEquals("MALFORMED_LEGACY_DAT_ROW", refused.code());
        assertFalse(Files.exists(target));
        assertArrayEquals(original, Files.readAllBytes(captures));
    }

    @Test
    void contradictoryOwnerEvidenceRefusesTheWholeBundle() throws Exception {
        Path source = tempDir.resolve("owner-conflict");
        writeCapture(source, OWNER_ONE);
        writeMinimalDeath(source, OWNER_TWO);
        Path target = tempDir.resolve("owner-conflict.sqlite");

        PublicImportResult.Refused refused = assertInstanceOf(
                PublicImportResult.Refused.class,
                new LegacyDatPersistenceImporter().importDirectory(source, target)
        );

        assertEquals("CONFLICTING_LEGACY_DAT_OWNER", refused.code());
        assertFalse(Files.exists(target));
    }

    @Test
    void mutuallyExclusiveLifecycleEvidenceImportsOnlyAsQuarantinedUnresolvedState()
            throws Exception {
        Path source = tempDir.resolve("lifecycle-conflict");
        writeCapture(source, OWNER_ONE);
        writeMinimalDeath(source, OWNER_ONE);
        Path target = tempDir.resolve("lifecycle-conflict.sqlite");

        assertInstanceOf(
                PublicImportResult.Imported.class,
                new LegacyDatPersistenceImporter(() -> -5_000L)
                        .importDirectory(source, target)
        );

        assertEquals("UNRESOLVED", queryString(target,
                "SELECT lifecycle_state FROM companion_lifecycle"));
        assertEquals(0, queryInt(target,
                "SELECT COUNT(*) FROM companion_snapshot WHERE is_current = 1"));
        assertEquals("MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS", queryString(target,
                "SELECT reason_code FROM persistence_quarantine"));
    }

    private Map<String, byte[]> copyRepresentative(Path target) throws Exception {
        Files.createDirectories(target);
        LinkedHashMap<String, byte[]> copied = new LinkedHashMap<>();
        for (String fileName : LegacyDatBundleSnapshot.FILE_NAMES) {
            try (InputStream stream = getClass().getResourceAsStream(RESOURCE_ROOT + fileName)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing fixture " + fileName);
                }
                byte[] bytes = stream.readAllBytes();
                Files.write(target.resolve(fileName), bytes);
                copied.put(fileName, bytes);
            }
        }
        return Map.copyOf(copied);
    }

    private void assertSourceBytes(Path source, Map<String, byte[]> expected) throws Exception {
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(source.resolve(entry.getKey())),
                    entry.getKey());
        }
    }

    private void assertGoldenTarget(Path target) throws Exception {
        JsonObject golden = loadGolden();
        for (Map.Entry<String, JsonElement> count
                : golden.getAsJsonObject("counts").entrySet()) {
            assertEquals(count.getValue().getAsInt(),
                    queryInt(target, "SELECT COUNT(*) FROM " + count.getKey()),
                    count.getKey());
        }
        assertGoldenLifecycles(target, golden.getAsJsonObject("lifecycles"));
        assertEquals(golden.get("coopQuarantineReason").getAsString(),
                queryString(target, "SELECT reason_code FROM persistence_quarantine"));
        assertGoldenDeathPayload(target, golden.getAsJsonObject("deathPayload"));
    }

    private void assertGoldenLifecycles(Path target, JsonObject expected) throws Exception {
        String sql = """
                SELECT a.npc_uuid, l.lifecycle_state
                FROM companion_alias a
                INNER JOIN companion_lifecycle l ON l.profile_id = a.profile_id
                WHERE a.alias_state = 'CURRENT'
                """;
        try (Connection connection = connection(target);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            int count = 0;
            while (rows.next()) {
                assertEquals(expected.get(rows.getString(1)).getAsString(), rows.getString(2));
                count++;
            }
            assertEquals(expected.size(), count);
        }
    }

    private void assertGoldenDeathPayload(Path target, JsonObject expected) throws Exception {
        String raw = queryString(target, """
                SELECT s.payload_json
                FROM companion_snapshot s
                INNER JOIN companion_alias a ON a.profile_id = s.profile_id
                WHERE a.npc_uuid = '00000000-0000-0000-0000-000000000102'
                  AND s.snapshot_kind = 'death'
                """);
        JsonObject actual = JsonParser.parseString(raw).getAsJsonObject();
        for (Map.Entry<String, JsonElement> field : expected.entrySet()) {
            assertEquals(field.getValue(), actual.get(field.getKey()), field.getKey());
        }
    }

    private void assertEmptyCanonicalState(Path target) throws Exception {
        assertEquals(0, queryInt(target, "SELECT COUNT(*) FROM companion_profile"));
        assertEquals(0, queryInt(target, "SELECT COUNT(*) FROM companion_snapshot"));
        assertEquals(0, queryInt(target, "SELECT COUNT(*) FROM coop_slot"));
        assertEquals(1, queryInt(target, "SELECT COUNT(*) FROM import_manifest"));
    }

    private void writeCapture(Path source, String owner) throws Exception {
        Files.createDirectories(source);
        Files.writeString(
                source.resolve(LegacyDatBundleSnapshot.CAPTURES_FILE),
                String.join("\t", NPC, owner, TOOL_TOKEN, "", "", "", "", "10"),
                StandardCharsets.UTF_8
        );
    }

    private void writeMinimalDeath(Path source, String owner) throws Exception {
        Files.createDirectories(source);
        Files.writeString(
                source.resolve(LegacyDatBundleSnapshot.DEATHS_FILE),
                String.join("\t",
                        NPC, owner, "", TOOL_TOKEN, "", "false",
                        "", "", "", "", "20", "30"),
                StandardCharsets.UTF_8
        );
    }

    private JsonObject loadGolden() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                RESOURCE_ROOT + "expected-canonical.json")) {
            if (stream == null) {
                throw new IllegalStateException("Missing DAT golden fixture");
            }
            return JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private Connection connection(Path database) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private int queryInt(Path database, String sql) throws Exception {
        try (Connection connection = connection(database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    private String queryString(Path database, String sql) throws Exception {
        try (Connection connection = connection(database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
