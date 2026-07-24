package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplacementPersistenceDiagnosticsApiTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsReleasedDiagnosticsFromOneReplacementControlPlane() {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(
                configuration()
        )) {
            ReplacementPersistenceDiagnosticsApi diagnostics =
                    new ReplacementPersistenceDiagnosticsApi(persistence);

            assertTrue(persistence.start().toCompletableFuture().join().complete());

            assertEquals(
                    "HEALTHY",
                    diagnostics.getPersistenceDiagnostics().health().status()
            );
        }
    }

    private PublicPersistenceRuntimeConfiguration configuration() {
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "diagnostics-api-test",
                () -> -100L,
                (claim, operation) -> confirmed("refund"),
                event -> {
                },
                boundaries(),
                PublicPersistenceWorldReconciliation.alreadyComplete(),
                Duration.ofSeconds(5)
        );
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) -> confirmed("capture"),
                (request, operation) -> confirmed("capture_release"),
                (request, operation) -> confirmed("restoration"),
                (request, operation) -> confirmed("coop_capture"),
                (request, operation) -> confirmed("coop_release")
        );
    }

    private java.util.concurrent.CompletionStage<LiveOperationResult> confirmed(
            String code
    ) {
        return LiveOperationResult.confirmed(code).completed();
    }
}
