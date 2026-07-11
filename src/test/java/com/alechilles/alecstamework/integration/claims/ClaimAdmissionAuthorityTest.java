package com.alechilles.alecstamework.integration.claims;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.integration.claims.ClaimAdmissionTestFixtures.DESTINATION;
import static com.alechilles.alecstamework.integration.claims.ClaimAdmissionTestFixtures.context;
import static com.alechilles.alecstamework.integration.claims.ClaimAdmissionTestFixtures.entry;
import static com.alechilles.alecstamework.integration.claims.ClaimAdmissionTestFixtures.footprint;
import static com.alechilles.alecstamework.integration.claims.ClaimAdmissionTestFixtures.key;
import static com.alechilles.alecstamework.integration.claims.ClaimAdmissionTestFixtures.newActive;
import static com.alechilles.alecstamework.integration.claims.ClaimAdmissionTestFixtures.readyIndex;
import static com.alechilles.alecstamework.integration.claims.ClaimAdmissionTestFixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimAdmissionAuthorityTest {
    @Test
    void concurrentAdmissionsCannotOverReserveLastSlot() throws Exception {
        ClaimOccupancyIndex index = readyIndex(existingProfiles(3, DESTINATION));
        ClaimAdmissionTestFixtures.MutableBridge bridge = bridge(key(), footprint(2));
        ClaimPolicyContext policy = context(bridge);
        ClaimAdmissionService service = new ClaimAdmissionService(index);
        int contenders = 12;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClaimAdmissionDecision>> futures = new ArrayList<>();
        try {
            for (int contender = 0; contender < contenders; contender++) {
                String profileId = "new-" + contender;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.reserve(
                            request(List.of(newActive(profileId)), policy, 2, 100),
                            new ClaimLookupSession(policy)
                    );
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int allowed = 0;
            for (Future<ClaimAdmissionDecision> future : futures) {
                if (future.get(5, TimeUnit.SECONDS).allowed()) {
                    allowed++;
                }
            }
            assertEquals(1, allowed);
            assertEquals(1, service.pendingReservationCount());
            assertEquals(1L, service.pendingForClaim(key()));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void decisionExposesSmallestPerChunkAndTotalHeadroom() {
        ClaimOccupancyIndex index = readyIndex(existingProfiles(6, DESTINATION));
        ClaimAdmissionTestFixtures.MutableBridge bridge = bridge(key(), footprint(5));
        ClaimPolicyContext policy = context(bridge);
        ClaimAdmissionService service = new ClaimAdmissionService(index);

        ClaimAdmissionDecision first = service.reserve(
                request(List.of(newActive("first")), policy, 2, 7),
                new ClaimLookupSession(policy)
        );
        ClaimAdmissionDecision second = service.reserve(
                request(List.of(newActive("second")), policy, 2, 7),
                new ClaimLookupSession(policy)
        );

        assertTrue(first.allowed());
        assertEquals(10L, first.perChunkCapacity());
        assertEquals(7L, first.totalCapacity());
        assertEquals(7L, first.effectiveCapacity());
        assertEquals(1L, first.headroomBeforeReservation());
        assertEquals(0L, first.headroomAfterReservation());
        assertEquals(ClaimCapEvaluator.LimitingConstraint.TOTAL, first.limitingConstraint());
        assertFalse(second.allowed());
        assertEquals("claim-cap-reached", second.reason());
    }

    @Test
    void sameGenerationFootprintChangeInvalidatesDelayedReservation() {
        ClaimFootprint original = footprint(2);
        ClaimAdmissionTestFixtures.MutableBridge bridge = bridge(key(), original);
        ClaimPolicyContext policy = context(bridge);
        ClaimAdmissionService service = new ClaimAdmissionService(readyIndex(List.of()));
        ClaimAdmissionDecision decision = service.reserve(
                request(List.of(newActive("new")), policy, 10, 100),
                new ClaimLookupSession(policy)
        );
        ClaimFootprint resized = new ClaimFootprint(List.of(
                DESTINATION,
                new ClaimChunkCoordinate("world", 2, 0)
        ));
        bridge.resolution.set(ClaimResolution.found(key(), resized));

        boolean claimed = service.claimForApply(decision.reservation(), new ClaimLookupSession(policy));

        assertFalse(claimed);
        assertEquals(ClaimAdmissionReservation.State.INVALIDATED, decision.reservation().state());
        assertEquals(0, service.pendingReservationCount());
    }

    @Test
    void providerGenerationAndClaimOwnerChangesInvalidateReservations() {
        ClaimAdmissionTestFixtures.MutableBridge generationBridge = bridge(key(), footprint(2));
        ClaimPolicyContext firstGeneration = context(generationBridge, "instance-a", 1L);
        ClaimAdmissionService generationService = new ClaimAdmissionService(readyIndex(List.of()));
        ClaimAdmissionDecision generationDecision = generationService.reserve(
                request(List.of(newActive("generation")), firstGeneration, 10, 100),
                new ClaimLookupSession(firstGeneration)
        );
        ClaimPolicyContext nextGeneration = context(generationBridge, "instance-b", 1L);

        assertFalse(generationService.claimForApply(
                generationDecision.reservation(),
                new ClaimLookupSession(nextGeneration)
        ));
        assertEquals(ClaimAdmissionReservation.State.INVALIDATED, generationDecision.reservation().state());

        ClaimAdmissionTestFixtures.MutableBridge ownerBridge = bridge(key(), footprint(2));
        ClaimPolicyContext ownerPolicy = context(ownerBridge);
        ClaimAdmissionService ownerService = new ClaimAdmissionService(readyIndex(List.of()));
        ClaimAdmissionDecision ownerDecision = ownerService.reserve(
                request(List.of(newActive("owner")), ownerPolicy, 10, 100),
                new ClaimLookupSession(ownerPolicy)
        );
        ClaimPopulationKey changedOwner = ClaimPopulationKey.simpleClaims("world", UUID.randomUUID());
        ownerBridge.resolution.set(ClaimResolution.found(changedOwner, footprint(2)));

        assertFalse(ownerService.claimForApply(
                ownerDecision.reservation(),
                new ClaimLookupSession(ownerPolicy)
        ));
        assertEquals(ClaimAdmissionReservation.State.INVALIDATED, ownerDecision.reservation().state());
    }

    @Test
    void noClaimBecomingClaimInvalidatesOutsideClaimReservation() {
        ClaimAdmissionTestFixtures.MutableBridge bridge = new ClaimAdmissionTestFixtures.MutableBridge(
                ClaimResolution.noClaim()
        );
        ClaimPolicyContext policy = context(bridge);
        ClaimAdmissionService service = new ClaimAdmissionService(readyIndex(List.of()));
        ClaimAdmissionDecision decision = service.reserve(
                request(List.of(newActive("outside")), policy, 10, 100),
                new ClaimLookupSession(policy)
        );
        bridge.resolution.set(ClaimResolution.found(key(), footprint(2)));

        assertTrue(decision.allowed());
        assertFalse(service.claimForApply(decision.reservation(), new ClaimLookupSession(policy)));
        assertEquals(ClaimAdmissionReservation.State.INVALIDATED, decision.reservation().state());
    }

    @Test
    void expirationReleasesPendingClaimCapacity() {
        AtomicLong clock = new AtomicLong(10L);
        ClaimOccupancyIndex index = readyIndex(List.of());
        ClaimAdmissionTestFixtures.MutableBridge bridge = bridge(key(), footprint(2));
        ClaimPolicyContext policy = context(bridge);
        ClaimAdmissionService service = new ClaimAdmissionService(
                index,
                new ClaimPopulationSnapshotService(),
                clock::get
        );
        ClaimAdmissionDecision decision = service.reserve(
                request(List.of(newActive("expiring")), policy, 10, 100, false, 5L),
                new ClaimLookupSession(policy)
        );

        clock.set(15L);

        assertEquals(1, service.expireReservations());
        assertEquals(ClaimAdmissionReservation.State.EXPIRED, decision.reservation().state());
        assertEquals(0L, service.pendingForClaim(key()));
    }

    @Test
    void forceAdmissionIsStillCountedWhenClaimIsAlreadyOverCap() {
        ClaimOccupancyIndex index = readyIndex(existingProfiles(3, DESTINATION));
        ClaimAdmissionTestFixtures.MutableBridge bridge = bridge(key(), footprint(1));
        ClaimPolicyContext policy = context(bridge);
        ClaimAdmissionService service = new ClaimAdmissionService(index);

        ClaimAdmissionDecision forced = service.reserve(
                request(List.of(newActive("forced")), policy, 1, 1, true, 1_000L),
                new ClaimLookupSession(policy)
        );

        assertTrue(forced.allowed());
        assertTrue(forced.forced());
        assertEquals(1L, forced.requestedSlots());
        assertEquals(1L, service.pendingForClaim(key()));
    }

    private static List<ClaimOccupancyEntry> existingProfiles(int count, ClaimChunkCoordinate chunk) {
        ArrayList<ClaimOccupancyEntry> entries = new ArrayList<>();
        for (int profile = 0; profile < count; profile++) {
            entries.add(entry("existing-" + profile, CompanionLifecycleState.ACTIVE, chunk, 1L));
        }
        return entries;
    }

    private static ClaimAdmissionTestFixtures.MutableBridge bridge(ClaimPopulationKey key,
                                                                   ClaimFootprint footprint) {
        return new ClaimAdmissionTestFixtures.MutableBridge(ClaimResolution.found(key, footprint));
    }
}
