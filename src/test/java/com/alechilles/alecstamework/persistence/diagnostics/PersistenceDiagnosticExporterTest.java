package com.alechilles.alecstamework.persistence.diagnostics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.zip.ZipFile;
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
}
