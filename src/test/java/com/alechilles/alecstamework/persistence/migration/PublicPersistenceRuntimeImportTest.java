package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.companion.identity.CompanionAliasLiveBoundary;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntime;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full runtime rehearsal for copied public-v4 import and rollback. */
class PublicPersistenceRuntimeImportTest {
    @TempDir
    Path tempDir;

    @Test
    void copiedPublicV4StartsThroughTheReplacementRuntime() throws Exception {
        Path source = materializePublicSource();
        byte[] before = Files.readAllBytes(source);
        PublicPersistenceRuntime runtime = runtime(
                PublicPersistenceWorldReconciliation.alreadyComplete()
        );

        assertTrue(runtime.start().toCompletableFuture().join().complete());
        assertEquals(
                PublicPersistenceTarget.Origin.IMPORTED_PUBLIC,
                runtime.targetOrigin().orElseThrow()
        );
        assertInstanceOf(
                PersistenceReadResult.Found.class,
                runtime.queries().findProfile(ProfileId.parse(
                        "20000000-0000-0000-0000-000000000002"
                )).toCompletableFuture().join()
        );
        assertArrayEquals(before, Files.readAllBytes(source));
        assertTrue(runtime.shutdown(Duration.ofSeconds(5)).terminal());
    }

    @Test
    void failedReplacementCutoverLeavesSourceAndLegacyRuntimeUsable()
            throws Exception {
        Path source = materializePublicSource();
        byte[] before = Files.readAllBytes(source);
        PublicPersistenceWorldReconciliation failure =
                new PublicPersistenceWorldReconciliation() {
                    @Override
                    public CompletionStage<Result> awaitEvidence() {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("injected")
                        );
                    }

                    @Override
                    public CompletionStage<Result> reconcile() {
                        return CompletableFuture.completedFuture(
                                Result.COMPLETE
                        );
                    }

                    @Override
                    public void quiesce() {
                    }
                };
        PublicPersistenceRuntime replacement = runtime(failure);

        assertEquals(
                com.alechilles.alecstamework.persistence.control
                        .PersistenceStartupNode.WAIT_WORLD_EVIDENCE,
                replacement.start().toCompletableFuture().join().failedNode()
        );
        assertArrayEquals(before, Files.readAllBytes(source));
        assertTrue(replacement.shutdown(Duration.ofSeconds(5)).terminal());

        try (TameworkPersistenceRuntime legacy =
                     TameworkPersistenceRuntime.initialize(tempDir, null)) {
            assertTrue(Files.isRegularFile(source));
        }
    }

    private Path materializePublicSource() throws Exception {
        Path source = tempDir.resolve("tamework.sqlite");
        PersistenceConsolidationFixtureDatabase.materialize(
                "public-v4-representative.sql",
                source
        );
        return source;
    }

    private PublicPersistenceRuntime runtime(
            PublicPersistenceWorldReconciliation world
    ) {
        return new PublicPersistenceRuntime(
                new PublicPersistenceRuntimeConfiguration(
                        tempDir,
                        "import-rehearsal",
                        () -> -100,
                        (claim, operation) -> LiveOperationResult
                                .confirmed("refund_confirmed").completed(),
                        event -> {
                        },
                        new PublicPersistenceLiveBoundaries(
                                (rotation, operation) ->
                                        CompanionAliasLiveBoundary.Result
                                                .confirmed(),
                                (request, operation) -> LiveOperationResult
                                        .confirmed("capture_confirmed")
                                        .completed(),
                                (request, operation) -> LiveOperationResult
                                        .confirmed("restoration_confirmed")
                                        .completed(),
                                (request, operation) -> LiveOperationResult
                                        .confirmed("coop_capture_confirmed")
                                        .completed(),
                                (request, operation) -> LiveOperationResult
                                        .confirmed("coop_release_confirmed")
                                        .completed(),
                                (request, operation) -> LiveOperationResult
                                        .confirmed("timed_confirmed")
                                        .completed()
                        ),
                        world,
                        Duration.ofSeconds(5)
                )
        );
    }
}
