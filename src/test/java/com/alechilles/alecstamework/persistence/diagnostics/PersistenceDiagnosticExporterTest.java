package com.alechilles.alecstamework.persistence.diagnostics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
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
}
