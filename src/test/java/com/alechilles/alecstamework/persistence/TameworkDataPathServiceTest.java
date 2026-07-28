package com.alechilles.alecstamework.persistence;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkDataPathServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesUniverseDataDirectoryFromServerPath() throws Exception {
        Files.createDirectories(tempDir.resolve("universe"));
        Path legacyDataDir = tempDir.resolve("Server").resolve("mods").resolve("Alechilles_Alec's Tamework!");
        TameworkDataPathService service = new TameworkDataPathService();

        Path resolved = service.resolvePreferredDataDirectory(legacyDataDir);

        assertEquals(
                tempDir.resolve("Server").resolve("universe").resolve("Tamework").resolve("Data").normalize(),
                resolved
        );
    }

    @Test
    void resolvesUniverseDataDirectoryForNestedLinuxStyleLayout() throws Exception {
        Path runtimeRoot = tempDir.resolve("server").resolve("hytale");
        Files.createDirectories(runtimeRoot.resolve("universe"));
        Path legacyDataDir = runtimeRoot.resolve("mods").resolve("Alechilles_Alec's Tamework!");
        TameworkDataPathService service = new TameworkDataPathService();

        Path resolved = service.resolvePreferredDataDirectory(legacyDataDir);

        assertEquals(
                runtimeRoot.resolve("universe").resolve("Tamework").resolve("Data").normalize(),
                resolved
        );
    }

    @Test
    void prefersNearestUniverseAncestorOverHigherServerAncestor() throws Exception {
        Path outerServer = tempDir.resolve("server");
        Path innerRoot = outerServer.resolve("hytale");
        Files.createDirectories(outerServer.resolve("universe"));
        Files.createDirectories(innerRoot.resolve("universe"));
        Path legacyDataDir = innerRoot.resolve("mods").resolve("Alechilles_Alec's Tamework!");
        TameworkDataPathService service = new TameworkDataPathService();

        Path resolved = service.resolvePreferredDataDirectory(legacyDataDir);

        assertEquals(
                innerRoot.resolve("universe").resolve("Tamework").resolve("Data").normalize(),
                resolved
        );
    }

    @Test
    void initializesTargetDirectoryWithoutRelocatingLegacySourceArtifacts()
            throws Exception {
        Path legacyDataDir = tempDir.resolve("Server").resolve("mods")
                .resolve("Alechilles_Alec's Tamework!");
        Files.createDirectories(legacyDataDir);
        writeSourceFamily(legacyDataDir);
        Map<String, FileEvidence> before = evidence(legacyDataDir);

        TameworkDataPathService service = new TameworkDataPathService();
        TameworkDataPathLayout layout =
                service.resolveAndInitializeDataPathLayout(legacyDataDir);
        Path runtimeDataDir = layout.targetDirectory();

        assertEquals(
                tempDir.resolve("Server").resolve("universe").resolve("Tamework").resolve("Data").normalize(),
                runtimeDataDir
        );
        assertEquals(
                List.of(
                        runtimeDataDir.toAbsolutePath().normalize(),
                        legacyDataDir.toAbsolutePath().normalize()
                ),
                layout.persistenceSourceDirectories()
        );
        assertEquals(before, evidence(legacyDataDir));
        assertTrue(Files.isDirectory(runtimeDataDir));
        assertFalse(Files.exists(
                runtimeDataDir.resolve("CommandLinkedNpcCaptures.dat")
        ));
        assertFalse(Files.exists(runtimeDataDir.resolve("tamework.sqlite")));
    }

    @Test
    void exposesCurrentLegacyAndHistoricalCandidatesWithoutChangingAnyOfThem()
            throws Exception {
        Path runtimeRoot = tempDir.resolve("server").resolve("hytale");
        Path legacyDataDir = runtimeRoot.resolve("mods")
                .resolve("Alechilles_Alec's Tamework!");
        Path historicalWrongDataDir = tempDir.resolve("server")
                .resolve("universe")
                .resolve("Tamework")
                .resolve("Data");
        Files.createDirectories(legacyDataDir);
        Files.createDirectories(runtimeRoot.resolve("universe"));
        Files.createDirectories(historicalWrongDataDir);
        writeSourceFamily(legacyDataDir);
        Files.writeString(
                historicalWrongDataDir.resolve("CommandLinkedNpcLost.dat"),
                "historical-lost",
                StandardCharsets.UTF_8
        );
        Map<String, FileEvidence> legacyBefore = evidence(legacyDataDir);
        Map<String, FileEvidence> historicalBefore =
                evidence(historicalWrongDataDir);

        TameworkDataPathService service = new TameworkDataPathService();
        TameworkDataPathLayout layout =
                service.resolveDataPathLayout(legacyDataDir);

        Path expected = runtimeRoot.resolve("universe").resolve("Tamework").resolve("Data").normalize();
        assertEquals(expected, layout.targetDirectory());
        assertEquals(
                List.of(expected, legacyDataDir.toAbsolutePath().normalize(),
                        historicalWrongDataDir.toAbsolutePath().normalize()),
                layout.persistenceSourceDirectories()
        );
        assertEquals(legacyBefore, evidence(legacyDataDir));
        assertEquals(historicalBefore, evidence(historicalWrongDataDir));
        assertFalse(Files.exists(expected));
    }

    @Test
    void fallsBackToLegacyDirectoryWhenNoServerAncestorExists() throws Exception {
        Files.createDirectories(tempDir.resolve("universe"));
        Path legacyDataDir = tempDir.resolve("TameworkData");
        Files.createDirectories(legacyDataDir);
        Files.writeString(legacyDataDir.resolve("CommandLinkedNpcCaptures.dat"), "captures", StandardCharsets.UTF_8);

        TameworkDataPathService service = new TameworkDataPathService();
        Path resolved = service.resolveAndMigrateDataDirectory(legacyDataDir);

        assertEquals(legacyDataDir.toAbsolutePath().normalize(), resolved);
        assertTrue(Files.exists(legacyDataDir.resolve("CommandLinkedNpcCaptures.dat")));
    }

    @Test
    void sourceCandidatesAreDeduplicatedWhenLegacyIsCanonical() {
        Path legacyDataDir = tempDir.resolve("TameworkData");

        TameworkDataPathLayout layout =
                new TameworkDataPathService()
                        .resolveDataPathLayout(legacyDataDir);

        assertEquals(
                List.of(legacyDataDir.toAbsolutePath().normalize()),
                layout.persistenceSourceDirectories()
        );
    }

    private void writeSourceFamily(Path directory) throws Exception {
        Map<String, String> source = Map.of(
                "CommandLinkedNpcCaptures.dat", "captures",
                "CommandLinkedNpcCoops.dat.runtime-v2.marker", "runtime-v2",
                "tamework.sqlite", "sqlite-main",
                "tamework.sqlite-wal", "sqlite-wal",
                "tamework.sqlite-shm", "sqlite-shm",
                "tamework.sqlite.legacy-dat-import-v2.marker", "imported"
        );
        for (Map.Entry<String, String> entry : source.entrySet()) {
            Files.writeString(
                    directory.resolve(entry.getKey()),
                    entry.getValue(),
                    StandardCharsets.UTF_8
            );
        }
    }

    private Map<String, FileEvidence> evidence(Path directory)
            throws Exception {
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
                                sha256(file),
                                Files.readAllBytes(file)
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
            String sha256,
            byte[] bytes
    ) {
        @Override
        public boolean equals(Object value) {
            return value instanceof FileEvidence other
                    && size == other.size
                    && modifiedAt.equals(other.modifiedAt)
                    && sha256.equals(other.sha256)
                    && java.util.Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    size, modifiedAt, sha256, java.util.Arrays.hashCode(bytes)
            );
        }
    }
}
