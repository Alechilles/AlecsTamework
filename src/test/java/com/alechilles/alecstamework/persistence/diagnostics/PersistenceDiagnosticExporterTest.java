package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLineage;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.Random;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;
import com.alechilles.alecstamework.persistence.kernel.PersistenceFailureEvidence;
import com.google.gson.JsonParser;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV2Manager;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSingleWriter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteWriterConfiguration;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteTransactionCommand;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.runtime.PersistenceThroughputSnapshot;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceMetricsSnapshot;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperationalStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards bounded, diagnostic-only replacement persistence exports. */
class PersistenceDiagnosticExporterTest {

    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void actualSqliteFailureEvidenceSurvivesStrictUploadValidationAfterRollback() throws Exception {
        var connections = new SqliteConnectionFactory(temporaryDirectory.resolve("state.sqlite"));
        org.junit.jupiter.api.Assertions.assertInstanceOf(PersistenceTransactionResult.Committed.class,
                new SqliteSchemaV2Manager(connections).initialize());
        OperationId id = OperationId.create();
        IllegalStateException failure = new IllegalStateException("operation_prepared_detail_missing");
        try (var writer = new SqliteSingleWriter(connections, SqliteWriterConfiguration.DEFAULT,
                (point, operation) -> { }, PersistenceKernelMetrics.NO_OP)) {
            writer.submit(new SqliteTransactionCommand<>(id, new OperationKind("companion_dormant_transition"),
                    TransactionReplayPolicy.NEVER, connection -> {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO operation_envelope(operation_id,idempotency_key,operation_kind,payload_version,payload_json,
                        phase,feature_scope,expected_lifecycle_revision,created_at_ms,updated_at_ms)
                        VALUES(?,?,'companion_dormant_transition',1,'{"snapshot":{"kind":"coop","current":false}}',
                        'PREPARED','dormant',12,1,1)
                        """)) {
                    statement.setString(1, id.toString());
                    statement.setString(2, id.toString());
                    statement.executeUpdate();
                }
                try (var participant = connection.prepareStatement(
                        "INSERT INTO operation_participant(operation_id,scope_type,scope_key) VALUES(?,'COOP',?)")) {
                    participant.setString(1, id.toString());
                    participant.setString(2, "private-coop-identifier");
                    participant.executeUpdate();
                }
                throw failure;
            })).completion().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        var result = PersistenceDiagnosticExporter.buildFailurePackage(
                new PersistenceFailureContext("persistence_write_failed", id.toString(), "companion_dormant_transition",
                        "write", "sqlite_unknown", failure), 524_288, Map.of());
        var records = JsonParser.parseString(zipMembers(result.content()).get("failure-records.json")).getAsJsonObject();
        assertEquals("captured", records.get("status").getAsString(), records.toString());
        var row = records.getAsJsonArray("captures").get(0).getAsJsonObject().getAsJsonObject("tables")
                .getAsJsonArray("operation_envelope").get(0).getAsJsonObject();
        assertEquals(12, row.get("expected_lifecycle_revision").getAsInt());
        assertEquals("coop", row.getAsJsonObject("payload_evidence").getAsJsonObject("snapshot").get("kind").getAsString());
        var participant = records.getAsJsonArray("captures").get(0).getAsJsonObject().getAsJsonObject("tables")
                .getAsJsonArray("operation_participant").get(0).getAsJsonObject();
        assertEquals("COOP", participant.get("scope_type").getAsString());
        assertFalse(records.toString().contains("private-coop-identifier"));
        assertFalse(row.getAsJsonObject("payload_evidence").getAsJsonObject("snapshot").get("current").getAsBoolean());
        assertFalse(records.toString().contains(id.toString()));
    }

    @Test
    void failurePackageStillExistsWhenDatabaseEvidenceIsUnavailable()
            throws Exception {
        PersistenceDiagnosticExporter.FailurePackage result =
                PersistenceDiagnosticExporter.buildFailurePackage(
                        new PersistenceFailureContext(
                                "persistence_write_failed",
                                "incident-1",
                                "SAVE_PROFILE",
                                "final_write",
                                "sqlite_failure",
                                new IllegalStateException("sensitive message")
                        ),
                        524_288,
                        Map.of()
                );

        Map<String, String> members = zipMembers(result.content());
        assertTrue(members.containsKey("failure.json"));
        assertTrue(members.containsKey("manifest.json"));
        assertFalse(members.get("failure.json").contains("sensitive message"));
        assertTrue(members.get("failure.json").contains("IllegalStateException"));
        assertTrue(members.get("failure-records.json").contains("not_captured_at_failure_boundary"));
    }

    @Test
    void startupReportRetainsCapturedRecordsAndNestedReasonAfterStorageHasClosed() throws Exception {
        IllegalStateException original = new IllegalStateException("operation_prepared_detail_missing");
        original.addSuppressed(new PersistenceFailureEvidence("""
                {"version":1,"status":"complete","view":"transaction_local_not_commit_evidence",
                 "tables":{"operation_envelope":[{"expected_lifecycle_revision":12}],
                 "companion_lifecycle":[{"revision":14}]},"issues":[],"truncated":false,"elapsedMs":1}
                """));
        var failure = new java.util.concurrent.CompletionException(
                new IllegalStateException("operation_recovery_failed:dispatch_failed", original));
        var result = PersistenceDiagnosticExporter.buildFailurePackage(
                new PersistenceFailureContext("persistence_startup_failed", "startup-1",
                        "recover_operations", "startup", "startup_action_failed", failure),
                524_288, Map.of());
        Map<String, String> members = zipMembers(result.content());
        var errors = JsonParser.parseString(members.get("failure.json"))
                .getAsJsonObject().getAsJsonArray("exceptions");
        assertEquals("operation_prepared_detail_missing",
                errors.get(2).getAsJsonObject().get("code").getAsString());
        var capture = JsonParser.parseString(members.get("failure-records.json"))
                .getAsJsonObject().getAsJsonArray("captures").get(0).getAsJsonObject();
        assertEquals(12, capture.getAsJsonObject("tables").getAsJsonArray("operation_envelope")
                .get(0).getAsJsonObject().get("expected_lifecycle_revision").getAsInt());
        assertEquals(14, capture.getAsJsonObject("tables").getAsJsonArray("companion_lifecycle")
                .get(0).getAsJsonObject().get("revision").getAsInt());
        assertEquals("persistence_failure_evidence", original.getSuppressed()[0].getMessage());
    }

    @Test
    void failureReportIncludesSqlCodesWithoutMessagesAndBoundsCyclicCauses() throws Exception {
        var sql = new java.sql.SQLException("private player and C:/secret/save.db", "HY000", 11);
        var outer = new IllegalStateException("private-token", sql);
        sql.initCause(outer);
        sql.setNextException(new java.sql.SQLException(null, "HY000", 10));
        var result = PersistenceDiagnosticExporter.buildFailurePackage(
                new PersistenceFailureContext("persistence_read_failed", "read-1", "canonical",
                        "read", "sqlite_corrupt", outer), 524_288, Map.of());
        String json = zipMembers(result.content()).get("failure.json");
        assertFalse(json.contains("private"));
        assertFalse(json.contains("secret/save.db"));
        var errors = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("exceptions");
        assertEquals(3, errors.size());
        assertEquals(11, errors.get(1).getAsJsonObject().get("sqlErrorCode").getAsInt());
        assertEquals(10, errors.get(2).getAsJsonObject().get("sqlErrorCode").getAsInt());
    }

    @Test
    void oversizedOptionalEvidenceCannotDiscardFailureAndRecordEvidence() throws Exception {
        var failure = new IllegalStateException("operation_prepared_detail_missing");
        failure.addSuppressed(new PersistenceFailureEvidence("""
                {"version":1,"status":"complete","view":"transaction_local_not_commit_evidence",
                 "tables":{"companion_lifecycle":[{"revision":14}]},"issues":[],"truncated":false,"elapsedMs":1}
                """));
        byte[] excessive = new byte[PersistenceDiagnosticExporter.MAX_UNCOMPRESSED_BYTES + 1];
        var result = PersistenceDiagnosticExporter.buildFailurePackage(
                new PersistenceFailureContext("persistence_write_failed", "write-1", "dormant",
                        "write", "sqlite_unknown", failure), 32_000,
                Map.of("diagnostic-detail.json", excessive));
        var members = zipMembers(result.content());
        assertTrue(members.get("failure-records.json").contains("14"));
        assertTrue(members.get("collection-limits.json").contains("diagnostic-detail.json"));
        assertFalse(members.containsKey("diagnostic-detail.json"));
        assertTrue(result.content().length <= 32_000);
    }

    @Test
    void invalidCapturedEvidenceIsOmittedFromTheActualZipReport() throws Exception {
        var failure = new IllegalStateException("operation_prepared_detail_missing");
        failure.addSuppressed(new PersistenceFailureEvidence("""
                {"version":1,"status":"complete","view":"transaction_local_not_commit_evidence",
                 "tables":{"companion_lifecycle":[{"profile_id":"raw-player-id","coordinates":"10,20,30"}]},
                 "issues":[],"truncated":false,"elapsedMs":1}
                """));

        var result = PersistenceDiagnosticExporter.buildFailurePackage(
                new PersistenceFailureContext("persistence_write_failed", "write-1", "dormant",
                        "write", "sqlite_unknown", failure), 524_288, Map.of());
        String records = zipMembers(result.content()).get("failure-records.json");
        assertTrue(records.contains("invalid_evidence"));
        assertFalse(records.contains("raw-player-id"));
        assertFalse(records.contains("10,20,30"));
    }

    @Test
    void findsSuppressedEvidenceBeyondTheExceptionSerializationLimit() throws Exception {
        IllegalStateException deepest = new IllegalStateException("operation_prepared_detail_missing");
        Throwable failure = deepest;
        for (int index = 0; index < 13; index++) {
            failure = new IllegalStateException("wrapper", failure);
        }
        deepest.addSuppressed(new PersistenceFailureEvidence("""
                {"version":1,"status":"complete","view":"transaction_local_not_commit_evidence",
                 "tables":{"companion_lifecycle":[{"revision":14}]},"issues":[],"truncated":false,"elapsedMs":1}
                """));
        var result = PersistenceDiagnosticExporter.buildFailurePackage(
                new PersistenceFailureContext("persistence_write_failed", "write-1", "dormant",
                        "write", "sqlite_unknown", failure), 524_288, Map.of());
        Map<String, String> members = zipMembers(result.content());
        assertEquals(14, JsonParser.parseString(members.get("failure-records.json")).getAsJsonObject()
                .getAsJsonArray("captures").get(0).getAsJsonObject().getAsJsonObject("tables")
                .getAsJsonArray("companion_lifecycle").get(0).getAsJsonObject().get("revision").getAsInt());
        assertTrue(JsonParser.parseString(members.get("failure.json")).getAsJsonObject()
                .get("exceptionsTruncated").getAsBoolean());
    }

    @Test
    void automaticStatusOmitsFreeTextAndLocalPaths() {
        EnumMap<PersistenceStartupNode,
                PublicPersistenceOperationalStatus.NodeState> nodes =
                new EnumMap<>(PersistenceStartupNode.class);
        for (PersistenceStartupNode node : PersistenceStartupNode.values()) {
            nodes.put(node, PublicPersistenceOperationalStatus.NodeState.PENDING);
        }
        PublicPersistenceOperationalStatus status =
                new PublicPersistenceOperationalStatus(
                        PersistenceEngineLineage.REPLACEMENT,
                        temporaryDirectory.resolve("secret-data-path"),
                        Optional.of(temporaryDirectory.resolve("secret.db")),
                        Optional.empty(),
                        OptionalInt.empty(),
                        PublicPersistenceOperationalStatus.StorageMode.STARTING,
                        new PersistenceStartupReport(
                                Set.of(), null, null,
                                PersistenceStartupNode.OPEN_TARGET,
                                "secret-token-message",
                                PersistenceReadinessLevel.GLOBAL_READ_ONLY
                        ),
                        nodes,
                        new PublicPersistenceOperationalStatus.CheckpointEvidence(
                                PublicPersistenceOperationalStatus
                                        .CheckpointEvidence.Status.NOT_ATTEMPTED,
                                0, 0, null
                        ),
                        List.of("open secret-data-path to repair")
                );

        String json = PersistenceDiagnosticExporter
                .automaticStatusJson(status).toString();

        assertFalse(json.contains("secret-token-message"));
        assertFalse(json.contains("secret-data-path"));
        assertFalse(json.contains("secret.db"));
        assertFalse(json.contains("guidance"));
        assertTrue(json.contains("GLOBAL_READ_ONLY"));
    }

    @Test
    void failurePackageDropsDetailBeforeEssentialEvidence() throws Exception {
        byte[] detail = new byte[200_000];
        new Random(7L).nextBytes(detail);
        Map<String, byte[]> evidence = new LinkedHashMap<>();
        evidence.put("operational-status.json", "{}".getBytes(StandardCharsets.UTF_8));
        evidence.put("metrics.json", "{}".getBytes(StandardCharsets.UTF_8));
        evidence.put("diagnostic-detail.json", detail);

        PersistenceDiagnosticExporter.FailurePackage result =
                PersistenceDiagnosticExporter.buildFailurePackage(
                        new PersistenceFailureContext(
                                "persistence_read_failed",
                                "incident-2",
                                "READ_PROFILE",
                                "read",
                                "read_failed",
                                null
                        ),
                        32_000,
                        evidence
                );

        Map<String, String> members = zipMembers(result.content());
        assertTrue(result.content().length <= 32_000);
        assertTrue(members.containsKey("failure.json"));
        assertTrue(members.containsKey("operational-status.json"));
        assertTrue(members.containsKey("metrics.json"));
        assertFalse(members.containsKey("diagnostic-detail.json"));
    }

    private static Map<String, String> zipMembers(byte[] content) throws Exception {
        LinkedHashMap<String, String> members = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                members.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return Map.copyOf(members);
    }

    @Test
    void bundleContainsOnlyManifestAndSuppliedSanitizedMembers()
            throws Exception {
        var result = PersistenceDiagnosticExporter.writeBundle(
                temporaryDirectory,
                "support123",
                Instant.parse("2026-07-24T13:00:00Z"),
                Map.of(
                        "operational-status.json",
                        "{}".getBytes(StandardCharsets.UTF_8),
                        "diagnostic-detail.json",
                        "{}".getBytes(StandardCharsets.UTF_8)
                )
        );

        assertTrue(Files.isRegularFile(result.path()));
        try (ZipFile zip = new ZipFile(result.path().toFile())) {
            Set<String> names = zip.stream()
                    .map(entry -> entry.getName())
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(
                    Set.of(
                            "manifest.json",
                            "operational-status.json",
                            "diagnostic-detail.json"
                    ),
                    names
            );
            assertFalse(
                    names.stream().anyMatch(
                            name -> name.endsWith(".sqlite")
                                    || name.endsWith(".db")
                    ),
                    "A support bundle must never contain the database"
            );
        }
    }

    @Test
    void oversizedEvidenceIsRejectedBeforeWriting() {
        byte[] oversized =
                new byte[PersistenceDiagnosticExporter
                        .MAX_UNCOMPRESSED_BYTES + 1];

        assertThrows(
                IllegalArgumentException.class,
                () -> PersistenceDiagnosticExporter.writeBundle(
                        temporaryDirectory,
                        "oversized",
                        Instant.parse("2026-07-24T13:00:00Z"),
                        Map.of("detail.json", oversized)
                )
        );
        assertFalse(Files.exists(
                temporaryDirectory.resolve(
                        "tamework-persistence-oversized.zip"
                )
        ));
    }

    @Test
    void bondedContributorAddsOnlyItsFixedRedactedEntry() {
        BondedCompanionDiagnosticContributor contributor =
                new BondedCompanionDiagnosticContributor(
                        () -> com.alechilles.alecstamework.persistence.bonded
                                .BondedCompanionPersistenceReadiness.ready(),
                        () -> new com.alechilles.alecstamework.persistence.bonded
                                .BondedCompanionStoreDiagnostics(4, 1, 2, 1, 3),
                        3
                );
        LinkedHashMap<String, byte[]> members = new LinkedHashMap<>();
        members.put("operational-status.json", "{}".getBytes(
                StandardCharsets.UTF_8
        ));

        PersistenceDiagnosticExporter.appendBondedEntry(
                members, contributor
        );

        assertEquals(
                Set.of("operational-status.json", "bonded-companions.json"),
                members.keySet()
        );
        String bonded = new String(
                members.get("bonded-companions.json"),
                StandardCharsets.UTF_8
        );
        assertTrue(bonded.contains("\"storedProfiles\": 4"));
        assertFalse(bonded.contains("profileId"));
        assertFalse(bonded.contains("ownerUuid"));
        assertFalse(bonded.contains("liveNpcUuid"));
        assertFalse(bonded.contains("snapshot"));
        assertFalse(bonded.contains("position"));
    }

    @Test
    void bondedOnlyExportSucceedsWithoutGenericDiagnostics() throws Exception {
        BondedCompanionDiagnosticContributor contributor =
                new BondedCompanionDiagnosticContributor(
                        () -> com.alechilles.alecstamework.persistence.bonded
                                .BondedCompanionPersistenceReadiness.ready(),
                        () -> new com.alechilles.alecstamework.persistence.bonded
                                .BondedCompanionStoreDiagnostics(1, 0, 0, 0, 0),
                        4
                );
        PersistenceDiagnosticExporter exporter =
                PersistenceDiagnosticExporter.bondedOnly(
                        temporaryDirectory, contributor
                );

        PersistenceDiagnosticExporter.ExportResult result = exporter.export()
                .toCompletableFuture().join();

        try (ZipFile zip = new ZipFile(result.path().toFile())) {
            Set<String> names = zip.stream().map(entry -> entry.getName())
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(
                    Set.of("manifest.json", "bonded-companions.json"),
                    names
            );
        }
    }

    @Test
    void metricsExportContainsCountsAndNoCompanionIdentity() {
        PublicPersistenceMetricsSnapshot metrics =
                new PublicPersistenceMetricsSnapshot(
                        0,
                        0,
                        0,
                        0,
                        null,
                        Map.of(
                                new PersistenceFeatureId("test"),
                                new PublicPersistenceMetricsSnapshot.FeatureMetrics(
                                        "test", 0, 0, 0, 0, 0
                                )
                        ),
                        new PersistenceThroughputSnapshot.Values(
                                1,
                                10_000,
                                1,
                                0,
                                0, 0, 0, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, 0, 0, 0, 0,
                                0, 0, 0
                        )
                );

        String json = new String(
                PersistenceDiagnosticExporter.metricsJson(metrics),
                StandardCharsets.UTF_8
        );

        assertTrue(json.contains("projectionSequencePositionsBypassed"));
        assertTrue(json.contains("10000"));
        assertFalse(json.contains("profileId"));
        assertFalse(json.contains("ownerId"));
        assertFalse(json.contains("npcUuid"));
    }
}
