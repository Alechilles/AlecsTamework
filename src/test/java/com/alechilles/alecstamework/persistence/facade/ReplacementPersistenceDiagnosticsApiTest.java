package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityRequest;
import com.alechilles.alecstamework.api.PersistenceMutationDirection;
import com.alechilles.alecstamework.api.PersistenceMutationDomain;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplacementPersistenceDiagnosticsApiTest {
    @TempDir
    Path tempDir;

    @Test
    void mapsOneReplacementControlPlaneWithoutLegacyCatalogs() {
        PopulationDiagnosticsView population =
                PopulationDiagnosticsView.unavailable();
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(
                configuration()
        )) {
            ReplacementPersistenceDiagnosticsApi diagnostics =
                    new ReplacementPersistenceDiagnosticsApi(
                            persistence,
                            () -> population,
                            Duration.ofSeconds(5)
                    );
            assertEquals(
                    "GLOBAL_READ_ONLY",
                    diagnostics.queryPersistenceAvailability(request()).status()
            );

            assertTrue(persistence.start().toCompletableFuture().join().complete());

            assertEquals(
                    "HEALTHY",
                    diagnostics.getPersistenceDiagnostics().health().status()
            );
            assertEquals(
                    "HEALTHY",
                    diagnostics.getPersistenceResilience().storageState()
            );
            assertEquals(
                    PublicPersistenceFeatureRegistry.create().descriptors().size(),
                    diagnostics.getPersistenceResilience().circuits().size()
            );
            assertFalse(
                    diagnostics.getPersistenceResilience().coverage().isEmpty()
            );
            assertEquals(population, diagnostics.getPopulationDiagnostics());
            assertTrue(diagnostics.queryPersistenceAvailability(
                    request()
            ).allowed());
            assertTrue(diagnostics.findPersistenceIncident("abcd").isEmpty());
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

    private PersistenceMutationAvailabilityRequest request() {
        return new PersistenceMutationAvailabilityRequest(
                PersistenceMutationDomain.ALL_PERSISTENCE,
                "diagnostics",
                List.of(),
                Set.of(),
                PersistenceMutationDirection.ZERO,
                null,
                null,
                false,
                false
        );
    }
}
