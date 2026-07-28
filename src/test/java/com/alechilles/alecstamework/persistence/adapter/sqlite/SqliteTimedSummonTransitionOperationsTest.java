package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLiveBoundary;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.persistence.incidents.IncidentState;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live round-trip and late-world recovery tests for timed transitions. */
class SqliteTimedSummonTransitionOperationsTest
        extends TimedSummonTestSupport {
    @Test
    void cleanupRunsWithDurableOperationBeforePublication()
            throws Exception {
        PreparedTimed prepared = prepareStoredTimedProfile(75);
        TimedSummonTransitionRequest start = startRequest(
                prepared, lifecycleRead(PROFILE_A), -3_000
        );
        AtomicInteger cleanupCalls = new AtomicInteger();
        TimedSummonLiveBoundary boundary =
                new TimedSummonLiveBoundary() {
                    @Override
                    public java.util.concurrent.CompletionStage<
                            LiveOperationResult> applyOrResolve(
                            TimedSummonTransitionRequest request,
                            OperationEnvelope operation
                    ) {
                        return LiveOperationResult.confirmed(
                                "timed_summon_test_live"
                        ).completed();
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<
                            LiveOperationResult> cleanupAfterDurable(
                            TimedSummonTransitionRequest request,
                            OperationEnvelope operation
                    ) {
                        cleanupCalls.incrementAndGet();
                        assertEquals(
                                OperationPhase.DURABLE,
                                operation.phase()
                        );
                        return LiveOperationResult.confirmed(
                                "timed_summon_test_cleanup"
                        ).completed();
                    }
                };

        published(adapter.timedSummonTransitionOperations().submit(
                operationId(79),
                new IdempotencyKey("timed:cleanup"),
                start,
                boundary
        ).completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS));

        assertEquals(1, cleanupCalls.get());
    }

    @Test
    void summonAndStoreCommitOneLeaseLifecycleAndAliasPath()
            throws Exception {
        PreparedTimed prepared = prepareStoredTimedProfile(80);
        CompanionLifecycle stored = lifecycleRead(PROFILE_A);
        TimedSummonTransitionRequest start = startRequest(
                prepared, stored, -3_000
        );
        assertEquals(2, TimedSummonTransitionDefinition.INSTANCE
                .payloadVersion());
        assertNotNull(start.spawnPlacement());
        assertEquals(-12.5, start.spawnPlacement().x());
        assertEquals(
                start,
                TimedSummonTransitionDefinition.INSTANCE.decode(
                        TimedSummonTransitionDefinition.INSTANCE.encode(
                                start
                        )
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> withPlacement(start, null)
        );

        published(adapter.timedSummonTransitionOperations().submit(
                operationId(84),
                new IdempotencyKey("timed:start"),
                start,
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed(
                                        request.receiptKey()
                                ).completed()
        ).completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS));

        CompanionLifecycle active = lifecycleRead(PROFILE_A);
        assertEquals(LifecycleState.ACTIVE, active.state());
        assertEquals(2, active.revision().value());
        assertTrue(lease().activeSession());
        assertEquals(0, reservationCount(operationId(84)));
        assertTrue(adapter.timedSummonIndex()
                .laggingProfiles().isEmpty());

        TimedSummonTransitionRequest store = storeRequest(
                prepared, active, lease(), -2_000
        );
        assertNull(store.spawnPlacement());
        assertEquals(
                store,
                TimedSummonTransitionDefinition.INSTANCE.decode(
                        TimedSummonTransitionDefinition.INSTANCE.encode(
                                store
                        )
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> withPlacement(store, start.spawnPlacement())
        );
        published(adapter.timedSummonTransitionOperations().submit(
                operationId(85),
                new IdempotencyKey("timed:store"),
                store,
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed(
                                        request.receiptKey()
                                ).completed()
        ).completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS));

        CompanionLifecycle restored = lifecycleRead(PROFILE_A);
        assertEquals(LifecycleState.ROSTER_STORED, restored.state());
        assertEquals(4, restored.revision().value());
        assertEquals(-500L, lease().cooldownUntilMs());
        try (var connection = connections.openReadConnection()) {
            assertEquals(
                    store.snapshot(),
                    new SqliteCompanionSnapshotStore(connection)
                            .findCurrent(
                                    PROFILE_A,
                                    TimedSummonTransitionRequest.SNAPSHOT_KIND
                            ).orElseThrow()
            );
            assertEquals(
                    com.alechilles.alecstamework.companion.identity
                            .CompanionAlias.State.RETIRED,
                    new SqliteCompanionIdentityStore(connection)
                            .resolveAlias(prepared.alias())
                            .orElseThrow().state()
            );
        }
        assertTrue(adapter.timedSummonIndex()
                .laggingProfiles().isEmpty());
    }

    @Test
    void unavailableWorldLeavesExactFencesAndResumes()
            throws Exception {
        PreparedTimed prepared = prepareStoredTimedProfile(90);
        TimedSummonTransitionRequest start = startRequest(
                prepared, lifecycleRead(PROFILE_A), -3_000
        );
        var operationId = operationId(94);

        OperationWorkflowResult retryable =
                adapter.timedSummonTransitionOperations().submit(
                        operationId,
                        new IdempotencyKey("timed:late-world"),
                        start,
                        (request, operation) ->
                                com.alechilles.alecstamework.persistence
                                        .operation.LiveOperationResult
                                        .retryable(
                                                "world_not_loaded",
                                                null
                                        ).completed()
                ).completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);

        assertEquals(
                OperationWorkflowResult.Status.LIVE_RETRYABLE,
                retryable.status()
        );
        assertEquals(1, lifecycleRead(PROFILE_A).revision().value());
        assertEquals(1, reservationCount(operationId));

        published(adapter.timedSummonTransitionOperations().submit(
                operationId,
                new IdempotencyKey("timed:late-world"),
                start,
                (request, operation) ->
                        com.alechilles.alecstamework.persistence.operation
                                .LiveOperationResult.confirmed("spawned")
                                .completed()
        ).completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS));
        assertEquals(2, lifecycleRead(PROFILE_A).revision().value());
        assertEquals(0, reservationCount(operationId));
    }

    @Test
    void recoveryReverifiesKnownCaseDamagedStoreAndReleasesContainment()
            throws Exception {
        PreparedTimed prepared = prepareStoredTimedProfile(100);
        TimedSummonTransitionRequest start = startRequest(
                prepared, lifecycleRead(PROFILE_A), -3_000
        );
        published(adapter.timedSummonTransitionOperations().submit(
                operationId(104),
                new IdempotencyKey("timed:case-recovery-start"),
                start,
                (request, operation) -> LiveOperationResult.confirmed(
                        "spawned"
                ).completed()
        ).completion().toCompletableFuture()
                .get(10, TimeUnit.SECONDS));

        TimedSummonTransitionRequest store = storeRequest(
                prepared, lifecycleRead(PROFILE_A), lease(), -2_000
        );
        var operationId = operationId(105);
        OperationWorkflowResult unknown =
                adapter.timedSummonTransitionOperations().submit(
                        operationId,
                        new IdempotencyKey("timed:case-recovery-store"),
                        store,
                        (request, operation) ->
                                LiveOperationResult.unknown(
                "timed_summon_store_evidence_conflict_source_npc-uuid",
                                        null
                                ).completed()
                ).completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);

        assertEquals(
                OperationWorkflowResult.Status.LIVE_UNKNOWN,
                unknown.status()
        );
        com.alechilles.alecstamework.persistence.incidents.IncidentId
                incidentId;
        try (var connection = connections.openReadConnection()) {
            OperationEnvelope operation = new SqliteOperationStore(connection)
                    .find(operationId).orElseThrow();
            assertEquals(OperationPhase.UNKNOWN, operation.phase());
            assertTrue(TimedSummonTransitionDefinition.INSTANCE
                    .allowsUnknownLiveReverification(operation));
            var quarantine = new SqliteIncidentStore(connection)
                    .findQuarantine(OperationScope.operation(operationId))
                    .orElseThrow();
            assertEquals(QuarantineState.ACTIVE, quarantine.state());
            incidentId = quarantine.incidentId();
        }

        SqlitePublicRecoveryResult recovered = adapter.recover(
                boundaries(), "case-damaged-store-recovery"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                SqlitePublicRecoveryResult.Status.COMPLETE,
                recovered.status()
        );
        assertEquals(1, recovered.completedCount());
        assertEquals(LifecycleState.ROSTER_STORED,
                lifecycleRead(PROFILE_A).state());
        try (var connection = connections.openReadConnection()) {
            SqliteOperationStore operations =
                    new SqliteOperationStore(connection);
            SqliteIncidentStore incidents =
                    new SqliteIncidentStore(connection);
            assertEquals(
                    OperationPhase.PUBLISHED,
                    operations.find(operationId).orElseThrow().phase()
            );
            assertEquals(
                    IncidentState.RESOLVED,
                    incidents.findIncident(incidentId).orElseThrow().state()
            );
            for (OperationScope scope : List.of(
                    OperationScope.operation(operationId),
                    OperationScope.profile(PROFILE_A),
                    OperationScope.owner(OWNER),
                    OperationScope.commandFamily(FAMILY)
            )) {
                assertEquals(
                        QuarantineState.RELEASED,
                        incidents.findQuarantine(scope)
                                .orElseThrow().state()
                );
            }
        }
    }

    @Test
    void unresolvedCaseDamagedStoreRemainsUnknownAndContained()
            throws Exception {
        PreparedTimed prepared = prepareStoredTimedProfile(110);
        published(adapter.timedSummonTransitionOperations().submit(
                operationId(114),
                new IdempotencyKey("timed:case-retry-start"),
                startRequest(prepared, lifecycleRead(PROFILE_A), -3_000),
                (request, operation) -> LiveOperationResult.confirmed(
                        "spawned"
                ).completed()
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS));

        OperationId operationId = operationId(115);
        adapter.timedSummonTransitionOperations().submit(
                operationId,
                new IdempotencyKey("timed:case-retry-store"),
                storeRequest(
                        prepared, lifecycleRead(PROFILE_A), lease(), -2_000
                ),
                (request, operation) -> LiveOperationResult.unknown(
                        "timed_summon_store_evidence_conflict", null
                ).completed()
        ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        PublicPersistenceLiveBoundaries defaults = boundaries();
        PublicPersistenceLiveBoundaries unresolved =
                new PublicPersistenceLiveBoundaries(
                        defaults.captures(),
                        defaults.capturedReleases(),
                        defaults.restorations(),
                        defaults.coopCaptures(),
                        defaults.coopReleases(),
                        (request, operation) -> LiveOperationResult.retryable(
                                "live_evidence_not_ready", null
                        ).completed(),
                        defaults.provisioningActivations(),
                        defaults.paidRevivals()
                );

        SqlitePublicRecoveryResult recovered = adapter.recover(
                unresolved, "case-damaged-store-unresolved"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(SqlitePublicRecoveryResult.Status.COMPLETE,
                recovered.status());
        assertEquals(0, recovered.completedCount());
        assertEquals(1, recovered.deferredCount());
        try (var connection = connections.openReadConnection()) {
            assertEquals(OperationPhase.UNKNOWN,
                    new SqliteOperationStore(connection).find(operationId)
                            .orElseThrow().phase());
            assertEquals(QuarantineState.ACTIVE,
                    new SqliteIncidentStore(connection).findQuarantine(
                            OperationScope.operation(operationId)
                    ).orElseThrow().state());
        }
    }

    private TimedSummonTransitionRequest withPlacement(
            TimedSummonTransitionRequest request,
            CompanionSpawnPlacement placement
    ) {
        return new TimedSummonTransitionRequest(
                request.action(),
                request.familyKey(),
                request.slotId(),
                request.expectedMembershipRevision(),
                request.beforeLease(),
                request.afterLease(),
                request.groupAdmission(),
                request.liveAlias(),
                request.worldKey(),
                placement,
                request.snapshot(),
                request.receiptKey(),
                request.requestedAtMs()
        );
    }
}
