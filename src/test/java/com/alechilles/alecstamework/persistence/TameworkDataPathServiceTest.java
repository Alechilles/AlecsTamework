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
    void resolvesUniverseDataDirectoryFromServerPath() {
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
    void fallsBackToLegacyDirectoryWhenNoServerAncestorExists() throws Exception {
        Path legacyDataDir = tempDir.resolve("TameworkData");
        Files.createDirectories(legacyDataDir);
        Files.writeString(legacyDataDir.resolve("CommandLinkedNpcCaptures.dat"), "captures", StandardCharsets.UTF_8);

        TameworkDataPathService service = new TameworkDataPathService();
        Path resolved = service.resolveAndMigrateDataDirectory(legacyDataDir);

        assertEquals(legacyDataDir.toAbsolutePath().normalize(), resolved);
        assertTrue(Files.exists(legacyDataDir.resolve("CommandLinkedNpcCaptures.dat")));
    }
}
