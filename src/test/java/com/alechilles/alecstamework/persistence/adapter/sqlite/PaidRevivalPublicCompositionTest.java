package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.revival.PaidRevivalBoundaries;
import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceShutdownReport;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the fail-closed paid-revival facade before managed
 * lifecycle admission is bound by the production Tamework composition.
 */
class PaidRevivalPublicCompositionTest {
    private static final OperationId OPERATION = OperationId.parse(
            "60000000-0000-0000-0000-000000000301"
    );
    private static final IdempotencyKey IDEMPOTENCY =
            new IdempotencyKey("paid-revival-public-composition");

    @TempDir
    Path tempDir;

    @Test
    void directBootstrapRejectsPaidRevivalUntilLifecycleAdmissionIsBound()
            throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                PersistenceFiles.replacementDatabase(tempDir)
        );
        new SqliteSchemaV1Manager(
                connections, () -> PaidRevivalTestSupport.CLOCK
        ).initialize();
        PaidRevivalTestSupport.seed(connections);
        AtomicInteger paidBoundaryCalls = new AtomicInteger();

        try (PersistenceBootstrap bootstrap = new PersistenceBootstrap(
                configuration(paidBoundaryCalls)
        )) {
            // Startup constructs a recovery registry that must exactly match
            // every public operation definition, including paid revival.
            assertTrue(
                    bootstrap.start().toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).complete()
            );

            var submitted = bootstrap.facades().operations().reviveCompanion(
                    OPERATION,
                    IDEMPOTENCY,
                    PaidRevivalTestSupport.request()
            );
            assertTrue(submitted.accepted());
            OperationWorkflowResult result = submitted.completion()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(
                    OperationWorkflowResult.Status.PREPARE_FAILED,
                    result.status(),
                    () -> String.valueOf(result.failure())
            );
            assertEquals(0, paidBoundaryCalls.get());
            assertEquals(
                    PublicPersistenceShutdownReport.Status.COMPLETE,
                    bootstrap.shutdown(Duration.ofSeconds(5)).status()
            );
        }
    }

    @Test
    void preStartLifecycleAdmissionBindingAppliesAfterTargetOpen()
            throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                PersistenceFiles.replacementDatabase(tempDir)
        );
        new SqliteSchemaV1Manager(
                connections, () -> PaidRevivalTestSupport.CLOCK
        ).initialize();
        PaidRevivalTestSupport.seed(connections);
        AtomicInteger paidBoundaryCalls = new AtomicInteger();

        try (PersistenceBootstrap bootstrap = new PersistenceBootstrap(
                configuration(paidBoundaryCalls)
        )) {
            bootstrap.bindLifecycleAdmission(request ->
                    CompletableFuture.completedFuture(
                            LifecycleAdmissionEvidence.unmanaged()
                    )
            );
            assertTrue(
                    bootstrap.start().toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).complete()
            );

            var submitted = bootstrap.facades().operations().reviveCompanion(
                    OPERATION,
                    IDEMPOTENCY,
                    PaidRevivalTestSupport.request()
            );
            assertTrue(submitted.accepted());
            OperationWorkflowResult result = submitted.completion()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(
                    OperationWorkflowResult.Status.PUBLISHED,
                    result.status(),
                    () -> String.valueOf(result.failure())
            );
            assertEquals(1, paidBoundaryCalls.get());
        }
    }

    private PublicPersistenceRuntimeConfiguration configuration(
            AtomicInteger paidBoundaryCalls
    ) {
        PublicPersistenceLiveBoundaries boundaries =
                new PublicPersistenceLiveBoundaries(
                        (request, operation) -> LiveOperationResult.confirmed(
                                "capture"
                        ).completed(),
                        (request, operation) -> LiveOperationResult.confirmed(
                                "capture-release"
                        ).completed(),
                        (request, operation) -> LiveOperationResult.confirmed(
                                "restoration"
                        ).completed(),
                        (request, operation) -> LiveOperationResult.confirmed(
                                "coop-capture"
                        ).completed(),
                        (request, operation) -> LiveOperationResult.confirmed(
                                "coop-release"
                        ).completed(),
                        (request, operation) -> LiveOperationResult.confirmed(
                                "timed-summon"
                        ).completed(),
                        (request, operation) -> LiveOperationResult.confirmed(
                                "provisioning"
                        ).completed(),
                        new PaidRevivalBoundaries(
                                (request, operation) -> {
                                    paidBoundaryCalls.incrementAndGet();
                                    return PaidRevivalLiveResult.confirmed(
                                            "paid-revival"
                                    ).completed();
                                },
                                (request, operation) ->
                                        LiveOperationResult.confirmed(
                                                "paid-revival-release"
                                        ).completed(),
                                (request, operation) ->
                                        LiveOperationResult.confirmed(
                                                "paid-revival-cleanup"
                                        ).completed()
                        )
                );
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "paid-revival-public-composition",
                () -> PaidRevivalTestSupport.CLOCK,
                (claim, operation) -> LiveOperationResult.confirmed(
                        "refund"
                ).completed(),
                ignored -> {
                },
                boundaries,
                PublicPersistenceWorldReconciliation.alreadyComplete(),
                Duration.ofSeconds(5)
        );
    }
}
