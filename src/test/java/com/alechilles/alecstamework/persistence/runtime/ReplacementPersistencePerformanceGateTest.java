package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loose cross-machine numeric gates plus exact bounded-resource assertions.
 *
 * <p>Production-like copied-world and tick-delta budgets remain live rehearsal
 * gates; this test catches order-of-magnitude regressions in the same JVM as the
 * normal suite.</p>
 */
class ReplacementPersistencePerformanceGateTest {
    private static final long STARTUP_NODE_BUDGET_NS =
            Duration.ofSeconds(5).toNanos();
    private static final long OPERATION_P99_BUDGET_NS =
            Duration.ofSeconds(2).toNanos();
    private static final int OPERATIONS = 64;

    @TempDir
    Path tempDir;

    @Test
    void startupTransactionsQueuesAndShutdownStayWithinReleaseBudgets() {
        PublicPersistenceRuntime runtime = runtime();

        assertTrue(runtime.start().toCompletableFuture().join().complete());
        for (int sequence = 0; sequence < OPERATIONS; sequence++) {
            var submission = runtime.operations().mutateProfile(
                    OperationId.create(),
                    new IdempotencyKey("performance-" + sequence),
                    create(sequence)
            );
            assertTrue(submission.accepted());
            assertEquals(
                    com.alechilles.alecstamework.persistence.operation
                            .OperationWorkflowResult.Status.PUBLISHED,
                    submission.completion().toCompletableFuture()
                            .join().status()
            );
        }

        PublicPersistencePerformanceSnapshot active =
                runtime.performance();
        assertStartupBudget(
                active, PersistenceStartupNode.OPEN_TARGET
        );
        assertStartupBudget(
                active, PersistenceStartupNode.LOAD_CANONICAL
        );
        assertStartupBudget(
                active, PersistenceStartupNode.BUILD_PROJECTIONS
        );
        assertTrue(active.writer().execution().count() >= OPERATIONS);
        assertTrue(
                active.writer().execution().p99Nanos()
                        <= OPERATION_P99_BUDGET_NS
        );
        assertTrue(
                active.writer().queueWait().p99Nanos()
                        <= OPERATION_P99_BUDGET_NS
        );
        assertTrue(active.writer().maximumDepth() <= 1_024);
        assertTrue(active.reads().maximumDepth() <= 256);

        assertTrue(runtime.shutdown(Duration.ofSeconds(5)).terminal());
        PublicPersistencePerformanceSnapshot closed =
                runtime.performance();
        assertEquals(1, closed.shutdownDrain().count());
        assertTrue(
                closed.shutdownDrain().p99Nanos()
                        <= Duration.ofSeconds(5).toNanos()
        );
        assertTrue(
                closed.lastCheckpointedFrames()
                        <= closed.lastCheckpointLogFrames()
        );
    }

    private void assertStartupBudget(
            PublicPersistencePerformanceSnapshot snapshot,
            PersistenceStartupNode node
    ) {
        var timing = snapshot.startupNodes().get(node);
        assertEquals(1, timing.count());
        assertTrue(
                timing.p99Nanos() <= STARTUP_NODE_BUDGET_NS,
                () -> node + " exceeded startup budget: "
                        + timing.p99Nanos()
        );
    }

    private PublicPersistenceRuntime runtime() {
        return new PublicPersistenceRuntime(
                new PublicPersistenceRuntimeConfiguration(
                        tempDir,
                        "performance-gate",
                        System::currentTimeMillis,
                        (claim, operation) -> LiveOperationResult
                                .confirmed("refund_confirmed")
                                .completed(),
                        event -> {
                        },
                        boundaries(),
                        PublicPersistenceWorldReconciliation
                                .alreadyComplete(),
                        Duration.ofSeconds(5)
                )
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

    private java.util.concurrent.CompletionStage<LiveOperationResult>
    confirmed(String receipt) {
        return LiveOperationResult.confirmed(receipt).completed();
    }

    private CompanionProfileMutation.Create create(int sequence) {
        ProfileId profileId = ProfileId.parse(
                UUID.nameUUIDFromBytes(
                        ("profile-" + sequence).getBytes(
                                StandardCharsets.UTF_8
                        )
                ).toString()
        );
        String metadata = "{\"performance\":" + sequence + "}";
        long now = -1_000L - sequence;
        return new CompanionProfileMutation.Create(
                new CompanionIdentity(
                        profileId,
                        "Companion " + sequence,
                        "role",
                        metadata,
                        Sha256Hash.ofUtf8(metadata),
                        "world",
                        now,
                        now,
                        now,
                        0
                ),
                new CompanionLifecycle(
                        profileId,
                        OwnerId.parse(
                                "10000000-0000-0000-0000-000000000001"
                        ),
                        LifecycleState.UNLOADED,
                        LifecycleLocation.none(),
                        LifecycleRevision.INITIAL,
                        null,
                        now,
                        ReconciliationGeneration.INITIAL,
                        null
                ),
                java.util.List.of(),
                now
        );
    }
}
