package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntime;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime-level coverage for immutable cross-directory source selection. */
class PublicPersistenceRuntimeSourceDiscoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void datOnlyLegacyCandidateImportsThroughOpenTargetBeforeFreshCreation()
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
        Path marker = legacyDirectory.resolve(
                "CommandLinkedNpcCoops.dat.runtime-v2.marker"
        );
        Files.writeString(marker, "runtime-v2", StandardCharsets.UTF_8);
        Map<Path, byte[]> sourceBefore = sourceBytes(
                List.of(captures, marker)
        );
        PublicPersistenceRuntime runtime = runtime(
                targetDirectory,
                List.of(targetDirectory, legacyDirectory)
        );

        assertTrue(runtime.start().toCompletableFuture().join().complete());
        assertEquals(
                PublicPersistenceTarget.Origin.IMPORTED_PUBLIC,
                runtime.targetOrigin().orElseThrow()
        );
        assertEquals(1, queryInt(
                runtime.databasePath().orElseThrow(),
                "SELECT COUNT(*) FROM import_manifest"
        ));
        assertEquals(1, queryInt(
                runtime.databasePath().orElseThrow(),
                "SELECT COUNT(*) FROM companion_profile"
        ));
        assertSourceBytes(sourceBefore);
        assertTrue(runtime.shutdown(Duration.ofSeconds(5)).terminal());
    }

    @Test
    void ambiguousDirectoriesFailOpenTargetWithoutSourceOrTargetMutation()
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
        Path legacy = legacyDirectory.resolve(
                LegacyDatBundleSnapshot.CAPTURES_FILE
        );
        Path historical = historicalDirectory.resolve(
                LegacyDatBundleSnapshot.LOST_FILE
        );
        Files.writeString(legacy, "legacy", StandardCharsets.UTF_8);
        Files.writeString(historical, "historical", StandardCharsets.UTF_8);
        Map<Path, byte[]> sourceBefore = sourceBytes(
                List.of(legacy, historical)
        );
        PublicPersistenceRuntime runtime = runtime(
                targetDirectory,
                List.of(
                        targetDirectory,
                        legacyDirectory,
                        historicalDirectory
                )
        );

        var report = runtime.start().toCompletableFuture().join();

        assertFalse(report.complete());
        assertEquals(
                com.alechilles.alecstamework.persistence.control
                        .PersistenceStartupNode.OPEN_TARGET,
                report.failedNode()
        );
        assertTrue(Files.notExists(
                targetDirectory.resolve("tamework-state.sqlite")
        ));
        assertSourceBytes(sourceBefore);
        assertTrue(runtime.shutdown(Duration.ofSeconds(5)).terminal());
    }

    private PublicPersistenceRuntime runtime(
            Path targetDirectory,
            List<Path> sourceDirectories
    ) {
        return new PublicPersistenceRuntime(
                new PublicPersistenceRuntimeConfiguration(
                        targetDirectory,
                        sourceDirectories,
                        "source-discovery",
                        () -> -100,
                        (claim, operation) -> LiveOperationResult
                                .confirmed("refund_confirmed").completed(),
                        event -> {
                        },
                        new PublicPersistenceLiveBoundaries(
                                (request, operation) -> LiveOperationResult
                                        .confirmed("capture_confirmed")
                                        .completed(),
                                (request, operation) -> LiveOperationResult
                                        .confirmed("capture_release_confirmed")
                                        .completed(),
                                (request, operation) -> LiveOperationResult
                                        .confirmed("restoration_confirmed")
                                        .completed(),
                                (request, operation) -> LiveOperationResult
                                        .confirmed("coop_capture_confirmed")
                                        .completed(),
                                (request, operation) -> LiveOperationResult
                                        .confirmed("coop_release_confirmed")
                                        .completed()
                        ),
                        PublicPersistenceWorldReconciliation
                                .alreadyComplete(),
                        Duration.ofSeconds(5)
                )
        );
    }

    private Map<Path, byte[]> sourceBytes(List<Path> paths)
            throws Exception {
        LinkedHashMap<Path, byte[]> result = new LinkedHashMap<>();
        for (Path path : paths) {
            result.put(path, Files.readAllBytes(path));
        }
        return Map.copyOf(result);
    }

    private void assertSourceBytes(Map<Path, byte[]> expected)
            throws Exception {
        for (Map.Entry<Path, byte[]> entry : expected.entrySet()) {
            assertArrayEquals(
                    entry.getValue(),
                    Files.readAllBytes(entry.getKey()),
                    entry.getKey().toString()
            );
        }
    }

    private int queryInt(Path database, String sql) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + database
        ); var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }
}
