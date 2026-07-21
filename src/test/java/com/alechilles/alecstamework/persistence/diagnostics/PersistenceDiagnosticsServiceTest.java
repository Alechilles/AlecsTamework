package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureContext;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.alechilles.alecstamework.persistence.incidents.PersistenceTransactionOutcome;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceDiagnosticsServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void exportIsBoundedHashedAndNeverContainsTheDatabaseOrSave() throws Exception {
        TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null);
        PersistenceDiagnosticsService.BundleResult result;
        try {
            result = new PersistenceDiagnosticsService(runtime, tempDir).export(null);
        } finally {
            runtime.close();
        }

        assertTrue(result.path().startsWith(
                tempDir.resolve("Diagnostics").resolve("Persistence").resolve("bundles")));
        try (ZipFile zip = new ZipFile(result.path().toFile())) {
            assertNotNull(zip.getEntry("manifest.json"));
            assertNotNull(zip.getEntry("health.json"));
            assertNotNull(zip.getEntry("incidents.json"));
            assertNotNull(zip.getEntry("quarantines.json"));
            assertNotNull(zip.getEntry("operations.json"));
            assertNotNull(zip.getEntry("integrity.json"));
            assertNotNull(zip.getEntry("reconciliation.json"));
            assertNotNull(zip.getEntry("breadcrumbs.jsonl"));
            assertNotNull(zip.getEntry("telemetry.json"));
            assertNotNull(zip.getEntry("settings.json"));
            assertNotNull(zip.getEntry("logs.txt"));
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().endsWith(".sqlite")));
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().endsWith(".sqlite.bak")));

            JsonObject operations = readJson(zip, zip.getEntry("operations.json"));
            assertEquals("complete", operations.get("status").getAsString());
            assertTrue(operations.getAsJsonArray("rows").isEmpty());

            JsonObject manifest = readJson(zip, zip.getEntry("manifest.json"));
            assertEquals(3, manifest.get("formatVersion").getAsInt());
            assertEquals("bounded_redacted_persistence_evidence",
                    manifest.get("scope").getAsString());
            assertEquals(8, manifest.get("schemaVersion").getAsInt());
            assertEquals(PersistenceDiagnosticsService.MAX_UNCOMPRESSED_BUNDLE_BYTES,
                    manifest.get("maxUncompressedBundleBytes").getAsInt());
            assertEquals(PersistenceDiagnosticsService.MAX_EXPORT_MILLIS,
                    manifest.get("maxExportMillis").getAsLong());
            assertTrue(manifest.get("note").getAsString().contains("never creates whole-save backups"));
            Map<String, ZipEntry> entries = new HashMap<>();
            zip.stream().forEach(entry -> entries.put(entry.getName(), entry));
            JsonArray members = manifest.getAsJsonArray("members");
            for (var element : members) {
                JsonObject member = element.getAsJsonObject();
                ZipEntry entry = entries.get(member.get("name").getAsString());
                assertNotNull(entry);
                assertEquals(member.get("sizeBytes").getAsLong(), entry.getSize());
                assertEquals(member.get("sha256").getAsString(), sha256(zip, entry));
            }
        }
    }

    @Test
    void incidentExportExcludesRawScopeKeysAndFailureMessages() throws Exception {
        String rawIdentity = UUID.randomUUID().toString();
        String privatePath = "C:\\Users\\tester\\world-save";
        TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null);
        PersistenceDiagnosticsService.BundleResult result;
        try {
            var submission = runtime.getIncidentReporter().report(new PersistenceFailureContext(
                    "publication_failed", PersistenceDomain.OWNER_MUTATION,
                    PersistenceOperationPhase.PUBLICATION, PersistenceTransactionOutcome.COMMITTED,
                    List.of(runtime.getPersistenceScopeFactory().profile(rawIdentity)),
                    true, true, false, false, false, false, false, true,
                    "operation-test", new IllegalStateException(privatePath)));
            assertTrue(submission.durableCompletion().get(5, TimeUnit.SECONDS));
            result = new PersistenceDiagnosticsService(runtime, tempDir).export(submission.incidentId());
        } finally {
            runtime.close();
        }

        try (ZipFile zip = new ZipFile(result.path().toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                try (InputStream input = zip.getInputStream(entry)) {
                    String text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    assertFalse(text.contains(rawIdentity));
                    assertFalse(text.contains(privatePath));
                }
            }
        }
    }

    @Test
    void exportIncludesPreProfileCaptureAttemptWithHashedIdentity() throws Exception {
        String actorUuid = UUID.randomUUID().toString();
        String targetUuid = UUID.randomUUID().toString();
        TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null);
        PersistenceDiagnosticsService.BundleResult result;
        try {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + runtime.getSqlitePath());
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO capture_attempts(
                             attempt_id, actor_uuid, target_npc_uuid, source_item_id,
                             source_context_json, spawner_config_id, spawner_config_revision,
                             target_policy_bypassed, state, guaranteed, expires_at_ms,
                             created_at_ms, updated_at_ms)
                         VALUES (?, ?, ?, ?, '{}', ?, 1, 0, 'PREPARED', 0, 2000, 1000, 1000)
                         """)) {
                statement.setString(1, "capture-diagnostic");
                statement.setString(2, actorUuid);
                statement.setString(3, targetUuid);
                statement.setString(4, "Spawner_Test");
                statement.setString(5, "Capture_Test");
                statement.executeUpdate();
            }
            result = new PersistenceDiagnosticsService(runtime, tempDir).export(null);
        } finally {
            runtime.close();
        }

        try (ZipFile zip = new ZipFile(result.path().toFile())) {
            JsonObject operations = readJson(zip, zip.getEntry("operations.json"));
            assertEquals("complete", operations.get("status").getAsString());
            JsonObject operation = operations.getAsJsonArray("rows").get(0).getAsJsonObject();
            assertEquals("capture_policy", operation.get("domain").getAsString());
            assertEquals("capture-diagnostic", operation.get("operationId").getAsString());
            assertEquals(64, operation.get("profileHash").getAsString().length());
            String serialized = operations.toString();
            assertFalse(serialized.contains(actorUuid));
            assertFalse(serialized.contains(targetUuid));
        }
    }

    private JsonObject readJson(ZipFile zip, ZipEntry entry) throws Exception {
        try (InputStream input = zip.getInputStream(entry)) {
            return JsonParser.parseString(new String(
                    input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private String sha256(ZipFile zip, ZipEntry entry) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
