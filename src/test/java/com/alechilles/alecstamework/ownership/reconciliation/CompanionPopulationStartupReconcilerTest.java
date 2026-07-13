package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.items.LoadedNpcIdentitySnapshot;
import com.alechilles.alecstamework.ownership.CompanionPopulationBootstrapService;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationStartupReconcilerTest {
    private static final LoadedNpcIdentityIndex.Location WORLD =
            new LoadedNpcIdentityIndex.Location("default", "store-default");
    private static final UUID NPC_UUID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void identityGateBindsCurrentGenerationOnlyAfterUniverseReady() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        index.markInitializationComplete();
        CompletableFuture<LoadedNpcIdentitySnapshot> stale =
                CompletableFuture.completedFuture(index.snapshot());
        CompletableFuture<LoadedNpcIdentitySnapshot> current = new CompletableFuture<>();
        AtomicReference<CompletableFuture<LoadedNpcIdentitySnapshot>> source =
                new AtomicReference<>(stale);
        AtomicInteger sourceReads = new AtomicInteger();
        CompletableFuture<Void> universeReady = new CompletableFuture<>();
        LoadedNpcIdentityStartupGate gate = new LoadedNpcIdentityStartupGate(
                index, Runnable::run, () -> false
        );

        CompletableFuture<LoadedNpcIdentitySnapshot> result = gate.awaitAfter(
                universeReady,
                () -> {
                    sourceReads.incrementAndGet();
                    return source.get();
                }
        );
        index.markInitializationIncomplete();
        source.set(current);

        assertEquals(0, sourceReads.get());
        universeReady.complete(null);
        assertEquals(1, sourceReads.get());
        assertFalse(result.isDone());

        index.recordAdded(observation());
        index.markInitializationComplete();
        current.complete(index.snapshot());

        assertTrue(result.join().initializationComplete());
        assertEquals(NPC_UUID, result.join().observations().getFirst().componentUuid());
    }

    @Test
    void identityGateRebindsWhenObservedGenerationIsSuperseded() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        index.markInitializationComplete();
        LoadedNpcIdentitySnapshot stale = index.snapshot();
        CompletableFuture<LoadedNpcIdentitySnapshot> current = new CompletableFuture<>();
        AtomicInteger sourceReads = new AtomicInteger();
        LoadedNpcIdentityStartupGate gate = new LoadedNpcIdentityStartupGate(
                index, Runnable::run, () -> false
        );

        CompletableFuture<LoadedNpcIdentitySnapshot> result = gate.awaitAfter(
                CompletableFuture.completedFuture(null),
                () -> {
                    if (sourceReads.getAndIncrement() == 0) {
                        index.markInitializationIncomplete();
                        return CompletableFuture.completedFuture(stale);
                    }
                    return current;
                }
        );

        assertEquals(2, sourceReads.get());
        assertFalse(result.isDone());
        index.recordAdded(observation());
        index.markInitializationComplete();
        current.complete(index.snapshot());

        assertTrue(result.join().initializationComplete());
        assertEquals(1, result.join().observations().size());
    }

    @Test
    void finalStatusRequiresEveryReconciliationAndIndexLayerToBeReady() {
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.READY,
                status(
                        CompanionPopulationReconciliationService.Status.READY,
                        OwnerPopulationReadiness.READY,
                        OwnerPopulationReadiness.READY
                )
        );
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.RECONCILING,
                status(
                        CompanionPopulationReconciliationService.Status.RECONCILING,
                        OwnerPopulationReadiness.READY,
                        OwnerPopulationReadiness.READY
                )
        );
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.RECONCILING,
                status(
                        CompanionPopulationReconciliationService.Status.READY,
                        OwnerPopulationReadiness.RECONCILING,
                        OwnerPopulationReadiness.READY
                )
        );
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.RECONCILING,
                status(
                        CompanionPopulationReconciliationService.Status.READY,
                        OwnerPopulationReadiness.READY,
                        OwnerPopulationReadiness.LOADING
                )
        );
    }

    @Test
    void finalStatusPropagatesAnyDegradedLayer() {
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.DEGRADED,
                status(
                        CompanionPopulationReconciliationService.Status.DEGRADED,
                        OwnerPopulationReadiness.READY,
                        OwnerPopulationReadiness.READY
                )
        );
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.DEGRADED,
                status(
                        CompanionPopulationReconciliationService.Status.READY,
                        OwnerPopulationReadiness.DEGRADED,
                        OwnerPopulationReadiness.READY
                )
        );
        assertEquals(
                CompanionPopulationReconciliationProgress.Status.DEGRADED,
                status(
                        CompanionPopulationReconciliationService.Status.READY,
                        OwnerPopulationReadiness.READY,
                        OwnerPopulationReadiness.DEGRADED
                )
        );
    }

    private static CompanionPopulationReconciliationProgress.Status status(
            CompanionPopulationReconciliationService.Status resultStatus,
            OwnerPopulationReadiness global,
            OwnerPopulationReadiness perWorld
    ) {
        CompanionPopulationReconciliationService.Result result =
                new CompanionPopulationReconciliationService.Result(resultStatus, "test", 0, 0, 0, 0);
        CompanionPopulationBootstrapService.BootstrapResult bootstrap =
                new CompanionPopulationBootstrapService.BootstrapResult(
                        global, perWorld, 0, 0, 0, "test"
                );
        return CompanionPopulationStartupReconciler.finalStatus(result, bootstrap);
    }

    @Test
    void ordinaryLiveIdentityRacesRetryWithoutClassifyingCorruptionAsTransient() {
        assertTrue(CompanionPopulationReconciliationRetryPolicy.shouldRetry(
                "reconciliation-loaded-identity-mutated-during-scan"));
        assertTrue(CompanionPopulationReconciliationRetryPolicy.shouldRetry(
                "reconciliation-live-evidence-mutated-during-final-reload"));
        assertFalse(CompanionPopulationReconciliationRetryPolicy.shouldRetry(
                "reconciliation-operation-ambiguous"));
        assertFalse(CompanionPopulationReconciliationRetryPolicy.shouldRetry(
                "reconciliation-source-failed:IllegalStateException"));
    }

    @Test
    void transientRetryBackoffStaysBounded() {
        assertEquals(25L, CompanionPopulationReconciliationRetryPolicy.delayMs(0));
        assertEquals(1_000L, CompanionPopulationReconciliationRetryPolicy.delayMs(100));
    }

    private static LoadedNpcIdentityIndex.LoadedNpcObservation observation() {
        return new LoadedNpcIdentityIndex.LoadedNpcObservation(
                NPC_UUID, NPC_UUID, WORLD, null
        );
    }
}
