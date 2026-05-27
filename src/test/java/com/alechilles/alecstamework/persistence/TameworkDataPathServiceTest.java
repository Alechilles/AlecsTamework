package com.alechilles.alecstamework.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void migratesLegacyDatFilesToUniverseDataDirectory() throws Exception {
        Path legacyDataDir = tempDir.resolve("Server").resolve("mods").resolve("Alechilles_Alec's Tamework!");
        Files.createDirectories(legacyDataDir);
        Path captures = legacyDataDir.resolve("CommandLinkedNpcCaptures.dat");
        Path coops = legacyDataDir.resolve("CommandLinkedNpcCoops.dat");
        Path extra = legacyDataDir.resolve("CustomRuntimeState.dat");
        Files.writeString(captures, "captures", StandardCharsets.UTF_8);
        Files.writeString(coops, "coops", StandardCharsets.UTF_8);
        Files.writeString(extra, "extra", StandardCharsets.UTF_8);

        TameworkDataPathService service = new TameworkDataPathService();
        Path runtimeDataDir = service.resolveAndMigrateDataDirectory(legacyDataDir);

        assertEquals(
                tempDir.resolve("Server").resolve("universe").resolve("Tamework").resolve("Data").normalize(),
                runtimeDataDir
        );
        assertFalse(Files.exists(captures));
        assertFalse(Files.exists(coops));
        assertFalse(Files.exists(extra));
        assertEquals("captures", Files.readString(runtimeDataDir.resolve("CommandLinkedNpcCaptures.dat")));
        assertEquals("coops", Files.readString(runtimeDataDir.resolve("CommandLinkedNpcCoops.dat")));
        assertEquals("extra", Files.readString(runtimeDataDir.resolve("CustomRuntimeState.dat")));
        assertTrue(Files.exists(runtimeDataDir.resolve("CommandLinkedNpcCoops.dat.runtime-v2.marker")));
    }

    @Test
    void migratesHistoricalServerAnchoredDataDirectoryToCorrectNestedRoot() throws Exception {
        Path runtimeRoot = tempDir.resolve("server").resolve("hytale");
        Path legacyDataDir = runtimeRoot.resolve("mods").resolve("Alechilles_Alec's Tamework!");
        Path historicalWrongDataDir = tempDir.resolve("server")
                .resolve("universe")
                .resolve("Tamework")
                .resolve("Data");
        Files.createDirectories(legacyDataDir);
        Files.createDirectories(runtimeRoot.resolve("universe"));
        Files.createDirectories(historicalWrongDataDir);
        Files.writeString(historicalWrongDataDir.resolve("CommandLinkedNpcCaptures.dat"), "captures", StandardCharsets.UTF_8);
        Files.writeString(historicalWrongDataDir.resolve("tamework.sqlite"), "sqlite-db", StandardCharsets.UTF_8);
        Files.writeString(historicalWrongDataDir.resolve("tamework.sqlite-wal"), "sqlite-wal", StandardCharsets.UTF_8);

        TameworkDataPathService service = new TameworkDataPathService();
        Path resolved = service.resolveAndMigrateDataDirectory(legacyDataDir);

        Path expected = runtimeRoot.resolve("universe").resolve("Tamework").resolve("Data").normalize();
        assertEquals(expected, resolved);
        assertEquals("captures", Files.readString(expected.resolve("CommandLinkedNpcCaptures.dat")));
        assertEquals("sqlite-db", Files.readString(expected.resolve("tamework.sqlite")));
        assertEquals("sqlite-wal", Files.readString(expected.resolve("tamework.sqlite-wal")));
        assertFalse(Files.exists(historicalWrongDataDir.resolve("CommandLinkedNpcCaptures.dat")));
        assertFalse(Files.exists(historicalWrongDataDir.resolve("tamework.sqlite")));
        assertFalse(Files.exists(historicalWrongDataDir.resolve("tamework.sqlite-wal")));
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
}
