package com.alechilles.alecstamework.companion.bonded;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for nonblocking lifecycle and expiry world-scan fan-out. */
class BondedCompanionAsyncProjectionReconcilerTest {
    private RecordingBondedDurability durability;
    private RecordingBondedWorld world;

    @BeforeEach
    void setUp() {
        durability = new RecordingBondedDurability();
        world = new RecordingBondedWorld();
    }

    @Test
    void waitsForCompletedWorldFanOutBeforeReconcilingLifecycleEvidence() {
        var lease = lease("profile-a", 40L);
        durability.activate(lease);
        CompletableFuture<BondedCompanionProjectionRecoverySystem.ScanResult> scan =
                new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        BondedCompanionAsyncProjectionReconciler reconciler = new
                BondedCompanionAsyncProjectionReconciler(
                observer(), (leases, maximum) -> {
                    calls.incrementAndGet();
                    assertEquals(List.of(lease), leases);
                    return scan;
                }, 8
        );

        reconciler.reconcileAsync(List.of(lease),
                BondedCompanionProjectionService.RecoveryCause.LOGOUT, -500L);

        assertEquals(1, calls.get());
        assertEquals(0, reconciler.tick());
        assertEquals(BondedCompanionState.ACTIVE, durability.states.get("profile-a"));

        scan.complete(new BondedCompanionProjectionRecoverySystem.ScanResult(
                List.of(), List.of(lease)
        ));

        assertEquals(1, reconciler.tick());
        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-a"));
    }

    @Test
    void incompleteWorldFanOutRetainsTheExactLeaseForAnotherAsynchronousAttempt() {
        var lease = lease("profile-retry", 41L);
        durability.activate(lease);
        AtomicInteger calls = new AtomicInteger();
        BondedCompanionAsyncProjectionReconciler reconciler = new
                BondedCompanionAsyncProjectionReconciler(
                observer(), (leases, maximum) -> {
                    int attempt = calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            attempt == 1
                                    ? new BondedCompanionProjectionRecoverySystem.ScanResult(
                                            List.of(), List.of())
                                    : new BondedCompanionProjectionRecoverySystem.ScanResult(
                                            List.of(), leases)
                    );
                }, 8
        );

        reconciler.reconcileAsync(List.of(lease),
                BondedCompanionProjectionService.RecoveryCause.EXPIRED, -500L);

        assertEquals(0, reconciler.tick());
        assertEquals(BondedCompanionState.ACTIVE,
                durability.states.get("profile-retry"));
        assertEquals(1, reconciler.tick());
        assertEquals(2, calls.get());
        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-retry"));
    }

    @Test
    void coalescesConcurrentLifecycleNotificationsByStableLeaseIdentity() throws Exception {
        var lease = lease("profile-coalesced", 42L);
        durability.activate(lease);
        AtomicInteger calls = new AtomicInteger();
        BondedCompanionAsyncProjectionReconciler reconciler = new
                BondedCompanionAsyncProjectionReconciler(
                observer(), (leases, maximum) -> {
                    calls.incrementAndGet();
                    return new CompletableFuture<>();
                }, 8
        );
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService callers = Executors.newFixedThreadPool(6)) {
            for (int index = 0; index < 6; index++) {
                callers.submit(() -> {
                    ready.countDown();
                    assertEquals(true, start.await(1, TimeUnit.SECONDS));
                    reconciler.reconcileAsync(List.of(lease),
                            BondedCompanionProjectionService.RecoveryCause.WORLD_TRANSFER,
                            -500L);
                    return null;
                });
            }
            assertEquals(true, ready.await(1, TimeUnit.SECONDS));
            start.countDown();
            callers.shutdown();
            assertEquals(true, callers.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertEquals(1, calls.get());
        assertEquals(1, reconciler.pendingCount());
    }

    @Test
    void boundsAdmissionsAndExplicitlyDefersWorkWhenFull() {
        var first = lease("profile-first", 43L);
        var second = lease("profile-second", 44L);
        AtomicLong clock = new AtomicLong();
        BondedCompanionAsyncProjectionReconciler reconciler = reconciler(
                (leases, maximum) -> new CompletableFuture<>(), 1, clock);

        assertEquals(true, reconciler.reconcileAsync(List.of(first),
                BondedCompanionProjectionService.RecoveryCause.WORLD_LOAD, -500L));
        assertEquals(false, reconciler.reconcileAsync(List.of(second),
                BondedCompanionProjectionService.RecoveryCause.WORLD_LOAD, -500L));
        assertEquals(1, reconciler.pendingCount());
    }

    @Test
    void doesNotCoalesceDistinctLeaseTokensForTheSameProfile() {
        var first = lease("profile-shared", 46L);
        var second = new BondedCompanionProjectionValidator.LeaseExpectation(
                first.ownerUuid(), first.rosterId(), first.profileId(), "lease-replacement",
                new UUID(0L, 47L), first.worldKey(), first.startedAtMs(), first.expiresAtMs(),
                first.phase());
        AtomicInteger calls = new AtomicInteger();
        BondedCompanionAsyncProjectionReconciler reconciler = new
                BondedCompanionAsyncProjectionReconciler(
                observer(), (leases, maximum) -> {
                    calls.incrementAndGet();
                    return new CompletableFuture<>();
                }, 8
        );

        assertEquals(true, reconciler.reconcileAsync(List.of(first),
                BondedCompanionProjectionService.RecoveryCause.WORLD_LOAD, -500L));
        assertEquals(true, reconciler.reconcileAsync(List.of(second),
                BondedCompanionProjectionService.RecoveryCause.WORLD_LOAD, -500L));

        assertEquals(2, calls.get());
        assertEquals(2, reconciler.pendingCount());
    }

    @Test
    void retriesCancelledStalledScanWithoutMutatingUntilConclusiveEvidenceArrives() {
        var lease = lease("profile-timeout", 45L);
        durability.activate(lease);
        AtomicLong clock = new AtomicLong();
        CompletableFuture<BondedCompanionProjectionRecoverySystem.ScanResult> stalled =
                new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        BondedCompanionAsyncProjectionReconciler reconciler = reconciler((leases, maximum) ->
                calls.incrementAndGet() == 1 ? stalled : CompletableFuture.completedFuture(
                        new BondedCompanionProjectionRecoverySystem.ScanResult(List.of(), leases)),
                8, clock);

        reconciler.reconcileAsync(List.of(lease),
                BondedCompanionProjectionService.RecoveryCause.LOGOUT, -500L);
        assertEquals(0, reconciler.tick());
        clock.set(TimeUnit.SECONDS.toNanos(11));
        assertEquals(0, reconciler.tick());
        assertEquals(true, stalled.isCancelled());
        assertEquals(BondedCompanionState.ACTIVE, durability.states.get("profile-timeout"));

        assertEquals(1, reconciler.tick());
        assertEquals(2, calls.get());
        assertEquals(BondedCompanionState.STORED, durability.states.get("profile-timeout"));
    }

    @Test
    void retriesACompletedNullScanWithoutRetainingItsQueueSlotForever() {
        var lease = lease("profile-null-stage", 48L);
        durability.activate(lease);
        AtomicInteger calls = new AtomicInteger();
        BondedCompanionAsyncProjectionReconciler reconciler = new
                BondedCompanionAsyncProjectionReconciler(
                observer(), (leases, maximum) -> calls.incrementAndGet() == 1 ? null
                        : CompletableFuture.completedFuture(
                                new BondedCompanionProjectionRecoverySystem.ScanResult(
                                        List.of(), leases)), 8
        );

        reconciler.reconcileAsync(List.of(lease),
                BondedCompanionProjectionService.RecoveryCause.WORLD_LOAD, -500L);
        assertEquals(0, reconciler.tick());
        assertEquals(1, reconciler.pendingCount());
        assertEquals(1, reconciler.tick());
        assertEquals(2, calls.get());
        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-null-stage"));
        assertEquals(0, reconciler.pendingCount());
        assertEquals(true, reconciler.reconcileAsync(List.of(lease("profile-after-null", 50L)),
                BondedCompanionProjectionService.RecoveryCause.WORLD_LOAD, -500L));
    }

    @Test
    void retriesASynchronousScanFailureWithoutRetainingItsQueueSlotForever() {
        var lease = lease("profile-sync-failure", 49L);
        durability.activate(lease);
        AtomicInteger calls = new AtomicInteger();
        BondedCompanionAsyncProjectionReconciler reconciler = new
                BondedCompanionAsyncProjectionReconciler(
                observer(), (leases, maximum) -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("world_scan_unavailable");
                    }
                    return CompletableFuture.completedFuture(
                            new BondedCompanionProjectionRecoverySystem.ScanResult(
                                    List.of(), leases));
                }, 8
        );

        reconciler.reconcileAsync(List.of(lease),
                BondedCompanionProjectionService.RecoveryCause.WORLD_LOAD, -500L);
        assertEquals(0, reconciler.tick());
        assertEquals(1, reconciler.pendingCount());
        assertEquals(1, reconciler.tick());
        assertEquals(2, calls.get());
        assertEquals(BondedCompanionState.STORED,
                durability.states.get("profile-sync-failure"));
    }

    private BondedCompanionAsyncProjectionReconciler reconciler(
            BondedCompanionAsyncProjectionReconciler.ScanSource scans,
            int maximumPending,
            AtomicLong clock
    ) {
        return new BondedCompanionAsyncProjectionReconciler(observer(), scans, 8,
                maximumPending, TimeUnit.SECONDS.toNanos(10), clock::get);
    }

    private BondedCompanionWorldLifecycleObserver observer() {
        BondedCompanionProjectionCleanupService cleanup =
                new BondedCompanionProjectionCleanupService(world);
        return new BondedCompanionWorldLifecycleObserver(
                new BondedCompanionProjectionService(
                        durability, durability, world, cleanup,
                        () -> "lease-new", () -> new UUID(0L, 900L)
                ), world
        );
    }

    private static BondedCompanionProjectionValidator.LeaseExpectation lease(
            String profileId, long uuid
    ) {
        return new BondedCompanionProjectionValidator.LeaseExpectation(
                new UUID(0L, 1L), "roster-a", profileId, "lease-" + profileId,
                new UUID(0L, uuid), "world-a", -1_000L, 0L,
                BondedCompanionProjectionValidator.LeasePhase.LIVE
        );
    }
}
