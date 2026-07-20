package com.alechilles.alecstamework.architecture;

import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceFaultInjectionArchitectureTest {
    private static final Path MAIN = Path.of("src/main/java/com/alechilles/alecstamework");
    private static final Set<String> ALLOWED = Set.of(
            "persistence/operation/PersistenceCheckpoint.java",
            "persistence/operation/PersistenceCheckpointHook.java",
            "persistence/health/PersistenceMutationAvailabilityService.java",
            "persistence/recovery/PostCommitPublicationRecoveryVerifier.java",
            "persistence/sqlite/PersistenceWriteBatchExecutor.java",
            "persistence/sqlite/PersistenceWriteQueue.java",
            "items/CompanionPreparedSpawnService.java",
            "items/SpawnerCaptureFinalizerService.java"
    );

    @Test
    void productionCommandsConfigsAndGameplayCannotReachFaultHooks() throws Exception {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(MAIN)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String relative = MAIN.relativize(path).toString().replace('\\', '/');
                    String source = Files.readString(path);
                    if ((source.contains("PersistenceCheckpointHook")
                            || source.contains("PersistenceCheckpoint."))
                            && !ALLOWED.contains(relative)) {
                        violations.add(relative);
                    }
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }
        assertTrue(violations.isEmpty(), "Production fault-hook access expanded: " + violations);
    }

    @Test
    void everyNamedCheckpointIsWiredToAProductionBoundary() throws Exception {
        StringBuilder source = new StringBuilder();
        try (Stream<Path> paths = Files.walk(MAIN)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith("PersistenceCheckpoint.java"))
                    .forEach(path -> {
                        try {
                            source.append(Files.readString(path)).append('\n');
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
        }
        List<String> missing = new ArrayList<>();
        for (PersistenceCheckpoint checkpoint : PersistenceCheckpoint.values()) {
            if (source.indexOf("PersistenceCheckpoint." + checkpoint.name()) < 0) {
                missing.add(checkpoint.name());
            }
        }
        assertTrue(missing.isEmpty(), "Persistence checkpoints lack production boundaries: " + missing);
    }
}
