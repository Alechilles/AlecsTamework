package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Random;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;
import com.alechilles.alecstamework.persistence.runtime.PersistenceThroughputSnapshot;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceMetricsSnapshot;
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
