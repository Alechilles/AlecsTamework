package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceDegradationArchitectureTest {
    private static final Path MAIN = Path.of("src/main/java/com/alechilles/alecstamework");
    private static final Set<String> DIRECT_GLOBAL_AUTHORITIES = Set.of(
            "ownership/CompanionPopulationBootstrapService.java",
            "ownership/reconciliation/CompanionPopulationStartupReconciler.java",
            "persistence/incidents/PersistenceIncidentReporter.java",
            "persistence/incidents/PersistenceResilienceRuntime.java",
            "persistence/sqlite/LegacyGlobalPersistenceFailureBridge.java",
            "persistence/sqlite/PersistenceHealthService.java",
            "persistence/sqlite/PersistenceWriteBatchExecutor.java",
            "persistence/sqlite/PersistenceWriteQueue.java",
            "persistence/sqlite/TameworkPersistenceRuntime.java"
    );

    @Test
    void onlyStorageBootstrapWriterIntegrityAndRecoveryAuthoritiesCanDegradeGlobally()
            throws Exception {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = Files.readString(path)
                            .replace("LegacyGlobalPersistenceFailureBridge.markDegraded", "legacyBridge");
                    if ((source.contains(".markDegraded(") || source.contains(".enterReadOnly("))
                            && !DIRECT_GLOBAL_AUTHORITIES.contains(relative(path))) {
                        violations.add(relative(path));
                    }
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }
        assertTrue(violations.isEmpty(),
                "Domain code must report structured scoped failures instead of global degradation: "
                        + violations);
    }

    @Test
    void compatibilityBridgeIsExplicitlyLimitedToLegacyFallbackCallers() throws Exception {
        Set<String> allowed = Set.of(
                "ownership/BreedingReplayJournalLoader.java",
                "ownership/OwnerPopulationJournalTerminality.java",
                "ownership/reconciliation/CompanionPopulationObservationFailureReporter.java"
        );
        List<String> callers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    if (Files.readString(path).contains("LegacyGlobalPersistenceFailureBridge.markDegraded")) {
                        callers.add(relative(path));
                    }
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }
        assertTrue(allowed.containsAll(callers) && callers.size() == allowed.size(),
                "Legacy global degradation bridge callers changed: " + callers);
    }

    private static String relative(Path path) {
        return MAIN.relativize(path).toString().replace('\\', '/');
    }
}
