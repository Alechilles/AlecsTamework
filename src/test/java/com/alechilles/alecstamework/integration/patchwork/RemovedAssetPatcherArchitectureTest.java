package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Prevents Tamework's retired private asset patcher from returning. */
final class RemovedAssetPatcherArchitectureTest {
    @Test
    void legacyPatcherAndCommandsAreAbsent() throws Exception {
        Path legacyPackage = Path.of(
                "src/main/java/com/alechilles/alecstamework/assets/patches");
        if (Files.exists(legacyPackage)) {
            try (var files = Files.walk(legacyPackage)) {
                assertFalse(files.anyMatch(path -> path.toString().endsWith(".java")));
            }
        }
        assertFalse(Files.exists(Path.of(
                "src/main/java/com/alechilles/alecstamework/commands/TameworkPatchesCommand.java")));
        assertFalse(Files.exists(Path.of(
                "src/main/java/com/alechilles/alecstamework/commands/TameworkPatchesReloadCommand.java")));
        assertFalse(Files.exists(Path.of(
                "src/main/java/com/alechilles/alecstamework/commands/TameworkPatchesSelfTestCommand.java")));
        assertFalse(Files.exists(Path.of(
                "src/main/java/com/alechilles/alecstamework/commands/TameworkPatchesStatusCommand.java")));
    }
}
