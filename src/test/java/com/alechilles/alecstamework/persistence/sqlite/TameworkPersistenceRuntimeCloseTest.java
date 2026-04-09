package com.alechilles.alecstamework.persistence.sqlite;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkPersistenceRuntimeCloseTest {
    @TempDir
    Path tempDir;

    @Test
    void closeReleasesSqliteHandlesForImmediateDirectoryDeletion() throws Exception {
        Path runtimeDir = tempDir.resolve("runtime");
        Files.createDirectories(runtimeDir);

        TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(runtimeDir, null);
        assertTrue(runtime.getNpcProfileRepository().upsertAsync(new NpcProfileRepository.ProfileUpdate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Owner",
                "Mob_Test",
                "Display",
                "Custom",
                true,
                null,
                null,
                null,
                new String[] {"tool-a"}
        )));

        runtime.close();
        deleteRecursively(runtimeDir);

        assertFalse(Files.exists(runtimeDir));
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
