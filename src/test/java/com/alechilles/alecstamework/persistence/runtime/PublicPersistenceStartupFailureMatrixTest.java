package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.identity.CompanionAliasLiveBoundary;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLease;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupAction;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupCoordinator;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Resource-cleanup matrix for a failure at every replacement startup node. */
class PublicPersistenceStartupFailureMatrixTest {
    @TempDir
    Path tempDir;

    @Test
    void everyNodeFailureCanShutDownAndReleaseTheEngineLease()
            throws Exception {
        for (PersistenceStartupNode failedNode
                : PersistenceStartupNode.values()) {
            Path directory = tempDir.resolve(
                    failedNode.name().toLowerCase(java.util.Locale.ROOT)
            );
            Files.createDirectories(directory);
            var configuration = configuration(directory);
            var registry = PublicPersistenceFeatureRegistry.create();
            var state = new PublicPersistenceRuntimeState(
                    configuration,
                    registry,
                    new PublicPersistenceWorkflowTracker()
            );
            Map<PersistenceStartupNode, PersistenceStartupAction> real =
                    state.actions();
            EnumMap<PersistenceStartupNode, PersistenceStartupAction> injected =
                    new EnumMap<>(PersistenceStartupNode.class);
            for (PersistenceStartupNode node
                    : PersistenceStartupNode.values()) {
                injected.put(node, node == failedNode
                        ? () -> CompletableFuture.failedFuture(
                        new IllegalStateException("injected")
                )
                        : real.get(node));
            }
            PersistenceStartupCoordinator startup =
                    new PersistenceStartupCoordinator(registry, injected);
            state.bind(startup);

            assertEquals(
                    failedNode,
                    startup.advance().toCompletableFuture().join().failedNode()
            );
            assertTrue(state.shutdown(Duration.ofSeconds(5)).terminal());
            try (PersistenceEngineLease ignored =
                         PersistenceEngineLease.acquireReplacement(directory)) {
                assertEquals(
                        com.alechilles.alecstamework.persistence.control
                                .PersistenceEngineLineage.REPLACEMENT,
                        ignored.requestedLineage()
                );
            }
        }
    }

    private PublicPersistenceRuntimeConfiguration configuration(
            Path directory
    ) {
        return new PublicPersistenceRuntimeConfiguration(
                directory,
                "startup-failure-matrix",
                () -> -100,
                (claim, operation) -> LiveOperationResult
                        .confirmed("refund_confirmed").completed(),
                event -> {
                },
                new PublicPersistenceLiveBoundaries(
                        (rotation, operation) ->
                                CompanionAliasLiveBoundary.Result.confirmed(),
                        (request, operation) -> LiveOperationResult
                                .confirmed("capture_confirmed").completed(),
                        (request, operation) -> LiveOperationResult
                                .confirmed("restoration_confirmed").completed(),
                        (request, operation) -> LiveOperationResult
                                .confirmed("coop_capture_confirmed").completed(),
                        (request, operation) -> LiveOperationResult
                                .confirmed("coop_release_confirmed").completed()
                ),
                PublicPersistenceWorldReconciliation.alreadyComplete(),
                Duration.ofSeconds(5)
        );
    }
}
