package com.alechilles.alecstamework.ownership;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic regression coverage for atomic owner-population reservations and transitions. */
class OwnerPopulationIndexTest {
    private static final UUID OWNER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OWNER_C = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void concurrentGlobalReservationsNeverExceedExactLimit() throws Exception {
        int limit = 7;
        OwnerPopulationIndex index = readyIndex(new AtomicLong(100L));
        List<OwnerPopulationTransitionRequest> requests = new ArrayList<>();
        for (int contender = 0; contender < 32; contender++) {
            requests.add(acquire("race-" + contender, OWNER_A, "alpha",
                    OwnerPopulationLimitScope.GLOBAL, limit, false));
        }

        List<OwnerPopulationDecision> decisions = reserveTogether(index, requests);
        List<OwnerPopulationDecision> allowed = decisions.stream()
                .filter(OwnerPopulationDecision::allowed)
                .toList();

        assertEquals(limit, allowed.size());
        assertEquals(32 - limit, decisions.stream()
                .filter(decision -> "owner-cap-reached".equals(decision.reason()))
                .count());
        assertCounts(index, OWNER_A, "alpha", 0L, limit, 0L, limit);
        allowed.forEach(decision -> claimAndCommit(index, decision.reservation()));
        assertCounts(index, OWNER_A, "alpha", limit, 0L, limit, 0L);
    }

    @Test
    void simultaneousPerWorldAdmissionsUseIndependentBuckets() throws Exception {
        OwnerPopulationIndex index = readyIndex(new AtomicLong(200L));
        List<OwnerPopulationDecision> decisions = reserveTogether(index, List.of(
                acquire("earth-profile", OWNER_A, "earth",
                        OwnerPopulationLimitScope.PER_WORLD, 1, false),
                acquire("mars-profile", OWNER_A, "mars",
                        OwnerPopulationLimitScope.PER_WORLD, 1, false)
        ));

        assertTrue(decisions.stream().allMatch(OwnerPopulationDecision::allowed));
        assertCounts(index, OWNER_A, "earth", 0L, 2L, 0L, 1L);
        assertCounts(index, OWNER_A, "mars", 0L, 2L, 0L, 1L);
        assertTrue(index.cancel(decisions.getFirst().reservation()));
        assertTrue(index.cancel(decisions.getFirst().reservation()));
        assertTrue(index.cancel(decisions.getLast().reservation()));
        assertEquals(0, index.pendingReservationCount());
    }

    @Test
    void deniedTransferLeavesSourceCommitted() {
        OwnerPopulationEntry source = entry("transfer", OWNER_A, "alpha", 4L);
        OwnerPopulationEntry targetOccupant = entry("target-full", OWNER_B, "alpha", 0L);
        OwnerPopulationIndex index = indexWith(List.of(source, targetOccupant), OwnerPopulationReadiness.READY);

        OwnerPopulationDecision decision = index.reserve(change(
                source, OWNER_B, "alpha", CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.OWNER_TRANSFER, OwnerPopulationLimitScope.GLOBAL, 1, false
        ));

        assertFalse(decision.allowed());
        assertEquals("owner-cap-reached", decision.reason());
        assertEquals(source, index.entry(source.profileId()).orElseThrow());
        assertCounts(index, OWNER_A, "alpha", 1L, 0L, 1L, 0L);
        assertCounts(index, OWNER_B, "alpha", 1L, 0L, 1L, 0L);
    }

    @Test
    void canceledTransferPreservesSourceAndCommitMovesSlotAtomically() {
        OwnerPopulationEntry source = entry("transfer", OWNER_A, "alpha", 7L);
        OwnerPopulationIndex index = indexWith(List.of(source), OwnerPopulationReadiness.READY);
        OwnerPopulationTransitionRequest transfer = change(
                source, OWNER_C, "alpha", CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.OWNER_TRANSFER, OwnerPopulationLimitScope.GLOBAL, 1, false
        );

        OwnerPopulationDecision canceled = index.reserve(transfer);
        assertTrue(canceled.allowed());
        assertCounts(index, OWNER_A, "alpha", 1L, 0L, 1L, 0L);
        assertCounts(index, OWNER_C, "alpha", 0L, 1L, 0L, 1L);
        assertTrue(index.cancel(canceled.reservation()));
        assertEquals(source, index.entry(source.profileId()).orElseThrow());

        OwnerPopulationDecision committed = index.reserve(transfer);
        assertTrue(committed.allowed());
        claimAndCommit(index, committed.reservation());
        assertCounts(index, OWNER_A, "alpha", 0L, 0L, 0L, 0L);
        assertCounts(index, OWNER_C, "alpha", 1L, 0L, 1L, 0L);
        assertEquals(OWNER_C, index.entry(source.profileId()).orElseThrow().ownerId());
    }

    @Test
    void perWorldRehomeMovesOnlyWorldBucket() {
        OwnerPopulationEntry source = entry("rehome", OWNER_A, "alpha", 2L);
        OwnerPopulationIndex index = indexWith(List.of(source), OwnerPopulationReadiness.READY);

        OwnerPopulationDecision decision = index.reserve(change(
                source, OWNER_A, "beta", CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.REHOME, OwnerPopulationLimitScope.PER_WORLD, 1, false
        ));

        assertTrue(decision.allowed());
        assertTrue(decision.positiveDelta());
        assertCounts(index, OWNER_A, "alpha", 1L, 0L, 1L, 0L);
        assertCounts(index, OWNER_A, "beta", 1L, 0L, 0L, 1L);
        claimAndCommit(index, decision.reservation());
        assertCounts(index, OWNER_A, "alpha", 1L, 0L, 0L, 0L);
        assertCounts(index, OWNER_A, "beta", 1L, 0L, 1L, 0L);
    }

    @Test
    void sameOwnerRestoreIsZeroDeltaEvenWhenIndexIsDegraded() {
        OwnerPopulationEntry dormant = new OwnerPopulationEntry(
                "restore", OWNER_A, "alpha", CompanionLifecycleState.UNLOADED, 5L
        );
        OwnerPopulationIndex index = indexWith(List.of(dormant), OwnerPopulationReadiness.DEGRADED);

        OwnerPopulationDecision decision = index.reserve(change(
                dormant, OWNER_A, "alpha", CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.RESTORE, OwnerPopulationLimitScope.GLOBAL, 1, false
        ));

        assertTrue(decision.allowed());
        assertFalse(decision.positiveDelta());
        assertEquals("owner-population-zero-delta", decision.reason());
        assertCounts(index, OWNER_A, "alpha", 1L, 0L, 1L, 0L);
        assertTrue(index.claimForApply(decision.reservation()));
        assertTrue(index.commit(decision.reservation()));
        OwnerPopulationEntry restored = index.entry(dormant.profileId()).orElseThrow();
        assertEquals(CompanionLifecycleState.ACTIVE, restored.lifecycleState());
        assertEquals(6L, restored.revision());
    }

    @Test
    void disabledLimitTracksPopulationWithoutReadinessDenial() {
        OwnerPopulationIndex index = new OwnerPopulationIndex(new AtomicLong(300L)::get);

        OwnerPopulationDecision decision = index.reserve(acquire(
                "disabled", OWNER_A, "alpha", OwnerPopulationLimitScope.GLOBAL, -1, false
        ));

        assertTrue(decision.allowed());
        assertTrue(decision.positiveDelta());
        assertEquals("owner-population-reserved-disabled", decision.reason());
        assertCounts(index, OWNER_A, "alpha", 0L, 1L, 0L, 1L);
        claimAndCommit(index, decision.reservation());
        assertCounts(index, OWNER_A, "alpha", 1L, 0L, 1L, 0L);
    }

    @Test
    void explicitForceCanCommitOverCapButCannotBypassRevisionCheck() {
        OwnerPopulationEntry existing = entry("existing", OWNER_A, "alpha", 3L);
        OwnerPopulationIndex index = indexWith(List.of(existing), OwnerPopulationReadiness.DEGRADED);

        OwnerPopulationDecision forced = index.reserve(acquire(
                "forced", OWNER_A, "alpha", OwnerPopulationLimitScope.GLOBAL, 1, true
        ));

        assertTrue(forced.allowed());
        assertTrue(forced.forced());
        assertEquals("owner-population-reserved-force", forced.reason());
        claimAndCommit(index, forced.reservation());
        assertCounts(index, OWNER_A, "alpha", 2L, 0L, 2L, 0L);

        OwnerPopulationTransitionRequest stale = new OwnerPopulationTransitionRequest(
                existing.profileId(), existing.revision() - 1L, OWNER_A, "alpha", OWNER_B, "alpha",
                CompanionLifecycleState.ACTIVE, OwnerPopulationOperation.ADMIN_FORCE,
                OwnerPopulationLimitScope.GLOBAL, 1, true
        );
        OwnerPopulationDecision staleDecision = index.reserve(stale);
        assertFalse(staleDecision.allowed());
        assertEquals("owner-population-revision-mismatch", staleDecision.reason());
    }

    @Test
    void cappedPerWorldAdmissionRequiresDestinationButDisabledAndForcedDoNot() {
        OwnerPopulationIndex index = readyIndex(new AtomicLong(400L));

        OwnerPopulationDecision capped = index.reserve(acquire(
                "missing-world", OWNER_A, null, OwnerPopulationLimitScope.PER_WORLD, 1, false
        ));
        assertFalse(capped.allowed());
        assertEquals("owner-cap-world-context-required", capped.reason());

        OwnerPopulationDecision disabled = index.reserve(acquire(
                "missing-world", OWNER_A, null, OwnerPopulationLimitScope.PER_WORLD, 0, false
        ));
        assertTrue(disabled.allowed());
        assertCounts(index, OWNER_A, null, 0L, 1L, 0L, 0L);
        assertTrue(index.cancel(disabled.reservation()));

        OwnerPopulationDecision forced = index.reserve(acquire(
                "missing-world", OWNER_A, null, OwnerPopulationLimitScope.PER_WORLD, 1, true
        ));
        assertTrue(forced.allowed());
        assertTrue(index.cancel(forced.reservation()));
    }

    @Test
    void expiryRejectsLateCallbackWhileClaimForApplyMakesLeaseNonExpiring() {
        AtomicLong clock = new AtomicLong(500L);
        OwnerPopulationIndex index = readyIndex(clock);
        OwnerPopulationDecision expiring = index.reserve(acquireWithLease(
                "expires", OWNER_A, "alpha", OwnerPopulationLimitScope.GLOBAL, 1, false, 10L
        ));

        clock.set(510L);
        assertEquals(1, index.expireReservations());
        assertFalse(index.claimForApply(expiring.reservation()));
        assertFalse(index.commit(expiring.reservation()));
        assertTrue(index.cancel(expiring.reservation()));
        assertCounts(index, OWNER_A, "alpha", 0L, 0L, 0L, 0L);

        OwnerPopulationDecision applying = index.reserve(acquireWithLease(
                "applies", OWNER_A, "alpha", OwnerPopulationLimitScope.GLOBAL, 1, false, 10L
        ));
        assertFalse(index.commit(applying.reservation()));
        assertTrue(index.claimForApply(applying.reservation()));
        assertFalse(index.claimForApply(applying.reservation()));
        clock.set(10_000L);
        assertEquals(0, index.expireReservations());
        assertTrue(index.commit(applying.reservation()));
        assertTrue(index.commit(applying.reservation()));
        assertFalse(index.cancel(applying.reservation()));
        assertCounts(index, OWNER_A, "alpha", 1L, 0L, 1L, 0L);
    }

    @Test
    void reservationCapabilityCannotBeReplayedAgainstAnotherIndex() {
        OwnerPopulationIndex source = readyIndex(new AtomicLong(600L));
        OwnerPopulationIndex foreign = readyIndex(new AtomicLong(600L));
        OwnerPopulationDecision decision = source.reserve(acquire(
                "authority", OWNER_A, "alpha", OwnerPopulationLimitScope.GLOBAL, 1, false
        ));

        assertTrue(decision.allowed());
        assertFalse(foreign.claimForApply(decision.reservation()));
        assertFalse(foreign.commit(decision.reservation()));
        assertFalse(foreign.cancel(decision.reservation()));
        claimAndCommit(source, decision.reservation());
        assertFalse(foreign.commit(decision.reservation()));
        assertCounts(foreign, OWNER_A, "alpha", 0L, 0L, 0L, 0L);
    }

    @Test
    void invalidReplacementDoesNotPartiallyReplaceCommittedState() {
        OwnerPopulationEntry original = entry("original", OWNER_A, "alpha", 0L);
        OwnerPopulationIndex index = indexWith(List.of(original), OwnerPopulationReadiness.READY);
        OwnerPopulationEntry duplicateA = entry("duplicate", OWNER_B, "beta", 0L);
        OwnerPopulationEntry duplicateB = entry("duplicate", OWNER_C, "gamma", 1L);

        assertThrows(IllegalArgumentException.class, () -> index.replaceCommittedEntries(
                List.of(duplicateA, duplicateB), OwnerPopulationReadiness.DEGRADED
        ));

        assertEquals(original, index.entry(original.profileId()).orElseThrow());
        assertEquals(OwnerPopulationReadiness.READY, index.readiness());
        assertCounts(index, OWNER_A, "alpha", 1L, 0L, 1L, 0L);
    }

    @Test
    void everyOwnedLifecycleStateConsumesOneCanonicalSlot() {
        List<OwnerPopulationEntry> entries = new ArrayList<>();
        int revision = 0;
        for (CompanionLifecycleState lifecycle : CompanionLifecycleState.values()) {
            entries.add(new OwnerPopulationEntry(
                    "lifecycle-" + lifecycle, OWNER_A, "alpha", lifecycle, revision++
            ));
        }
        OwnerPopulationIndex index = indexWith(entries, OwnerPopulationReadiness.READY);
        long expected = CompanionLifecycleState.values().length;

        assertCounts(index, OWNER_A, "alpha", expected, 0L, expected, 0L);
    }

    private static OwnerPopulationIndex readyIndex(AtomicLong clock) {
        return indexWith(clock, List.of(), OwnerPopulationReadiness.READY);
    }

    private static void claimAndCommit(OwnerPopulationIndex index,
                                       OwnerPopulationReservation reservation) {
        assertTrue(index.claimForApply(reservation));
        assertTrue(index.commit(reservation));
    }

    private static OwnerPopulationIndex indexWith(List<OwnerPopulationEntry> entries,
                                                  OwnerPopulationReadiness readiness) {
        return indexWith(new AtomicLong(0L), entries, readiness);
    }

    private static OwnerPopulationIndex indexWith(AtomicLong clock,
                                                  List<OwnerPopulationEntry> entries,
                                                  OwnerPopulationReadiness readiness) {
        OwnerPopulationIndex index = new OwnerPopulationIndex(clock::get);
        index.replaceCommittedEntries(entries, readiness);
        return index;
    }

    private static OwnerPopulationEntry entry(String profileId,
                                              UUID ownerId,
                                              String worldName,
                                              long revision) {
        return new OwnerPopulationEntry(
                profileId, ownerId, worldName, CompanionLifecycleState.ACTIVE, revision
        );
    }

    private static OwnerPopulationTransitionRequest acquire(String profileId,
                                                            UUID ownerId,
                                                            String worldName,
                                                            OwnerPopulationLimitScope scope,
                                                            int limit,
                                                            boolean force) {
        return acquireWithLease(
                profileId,
                ownerId,
                worldName,
                scope,
                limit,
                force,
                OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos()
        );
    }

    private static OwnerPopulationTransitionRequest acquireWithLease(String profileId,
                                                                     UUID ownerId,
                                                                     String worldName,
                                                                     OwnerPopulationLimitScope scope,
                                                                     int limit,
                                                                     boolean force,
                                                                     long leaseNanos) {
        return new OwnerPopulationTransitionRequest(
                profileId,
                OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION,
                null,
                null,
                ownerId,
                worldName,
                CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.NEW_OWNERSHIP,
                scope,
                limit,
                force,
                leaseNanos
        );
    }

    private static OwnerPopulationTransitionRequest change(OwnerPopulationEntry current,
                                                           UUID newOwnerId,
                                                           String destinationWorld,
                                                           CompanionLifecycleState lifecycleState,
                                                           OwnerPopulationOperation operation,
                                                           OwnerPopulationLimitScope scope,
                                                           int limit,
                                                           boolean force) {
        return new OwnerPopulationTransitionRequest(
                current.profileId(),
                current.revision(),
                current.ownerId(),
                current.ownershipWorldName(),
                newOwnerId,
                destinationWorld,
                lifecycleState,
                operation,
                scope,
                limit,
                force
        );
    }

    private static List<OwnerPopulationDecision> reserveTogether(
            OwnerPopulationIndex index,
            List<OwnerPopulationTransitionRequest> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<OwnerPopulationDecision>> futures = new ArrayList<>();
            for (OwnerPopulationTransitionRequest request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5L, TimeUnit.SECONDS));
                    return index.reserve(request);
                }));
            }
            assertTrue(ready.await(5L, TimeUnit.SECONDS));
            start.countDown();
            List<OwnerPopulationDecision> decisions = new ArrayList<>();
            for (Future<OwnerPopulationDecision> future : futures) {
                decisions.add(future.get(5L, TimeUnit.SECONDS));
            }
            return decisions;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
        }
    }

    private static void assertCounts(OwnerPopulationIndex index,
                                     UUID ownerId,
                                     String worldName,
                                     long globalCommitted,
                                     long globalPending,
                                     long worldCommitted,
                                     long worldPending) {
        assertEquals(
                new OwnerPopulationCounts(
                        globalCommitted,
                        globalPending,
                        worldCommitted,
                        worldPending
                ),
                index.counts(ownerId, worldName)
        );
    }
}
