package com.alechilles.alecstamework.architecture;

import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
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
            "persistence/recovery/PostCommitPublicationRecoveryVerifier.java",
            "persistence/kernel/PersistenceCheckpointHook.java",
            "persistence/adapter/sqlite/SqliteSingleWriter.java"
    );
    private static final Path SINGLE_WRITER = MAIN.resolve(
            "persistence/adapter/sqlite/SqliteSingleWriter.java"
    );
    private static final Path LIVE_OPERATION_COORDINATOR = MAIN.resolve(
            "persistence/adapter/sqlite/SqliteLiveOperationCoordinator.java"
    );
    private static final Path COMPENSATION_COORDINATOR = MAIN.resolve(
            "persistence/adapter/sqlite/SqliteCompensationCoordinator.java"
    );
    private static final Path PUBLIC_LIVE_BOUNDARIES = MAIN.resolve(
            "persistence/runtime/PublicPersistenceLiveBoundaries.java"
    );
    private static final List<String> RELEASED_LIVE_BOUNDARY_DECLARATIONS = List.of(
            "@Nonnull CompanionCaptureLiveBoundary captures,",
            "@Nonnull CompanionCaptureReleaseLiveBoundary capturedReleases,",
            "@Nonnull CompanionRestorationLiveBoundary restorations,",
            "@Nonnull CompanionCoopCaptureLiveBoundary coopCaptures,",
            "@Nonnull CompanionCoopReleaseLiveBoundary coopReleases"
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
    void everyReplacementTransactionCheckpointIsWiredToTheSingleWriter() throws Exception {
        String source = Files.readString(SINGLE_WRITER);
        List<String> missing = new ArrayList<>();
        for (PersistenceCheckpoint checkpoint : PersistenceCheckpoint.values()) {
            if (!source.contains("checkpoints.hit(PersistenceCheckpoint." + checkpoint.name())) {
                missing.add(checkpoint.name());
            }
        }
        assertTrue(
                missing.isEmpty(),
                "Replacement transaction checkpoints lack single-writer boundaries: " + missing
        );
    }

    @Test
    void liveEffectFaultSeamsRemainExplicitDependencies() throws Exception {
        String liveCoordinator = Files.readString(LIVE_OPERATION_COORDINATOR);
        String compensationCoordinator = Files.readString(COMPENSATION_COORDINATOR);
        String publicBoundaries = Files.readString(PUBLIC_LIVE_BOUNDARIES);
        List<String> missing = new ArrayList<>();

        if (!liveCoordinator.contains("LiveOperationBoundary<T> liveBoundary")) {
            missing.add("normal live-operation boundary");
        }
        if (!compensationCoordinator.contains("LiveOperationBoundary<T> liveBoundary")) {
            missing.add("compensation live-operation boundary");
        }
        for (String declaration : RELEASED_LIVE_BOUNDARY_DECLARATIONS) {
            if (!publicBoundaries.contains(declaration)) {
                missing.add(declaration);
            }
        }

        assertTrue(
                missing.isEmpty(),
                "Replacement live-effect fault seams are not explicit: " + missing
        );
    }
}
