package com.alechilles.alecstamework.integration.claims;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.ArrayList;
import java.util.List;
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

class ClaimAdmissionLifecycleTest {
    @Test
    void unloadedSameLocationRehydrateIsZeroDeltaAtCap() {
        ClaimOccupancyEntry unloaded = entry("subject", CompanionLifecycleState.UNLOADED, DESTINATION, 1L);
        List<ClaimOccupancyEntry> entries = fullClaimEntries();
        entries.set(0, unloaded);
        ClaimOccupancyIndex index = readyIndex(entries);
        ClaimAdmissionTestFixtures.MutableBridge bridge = bridge(2);
        ClaimPolicyContext policy = context(bridge);
        ClaimAdmissionService service = new ClaimAdmissionService(index);
        ClaimOccupancyTransition transition = new ClaimOccupancyTransition(
                unloaded,
                entry("subject", CompanionLifecycleState.ACTIVE, DESTINATION, 2L)
        );

        ClaimAdmissionDecision decision = service.reserve(
                request(List.of(transition), policy, 2, 10),
                new ClaimLookupSession(policy)
        );

        assertTrue(decision.allowed());
        assertTrue(decision.zeroDelta());
        assertEquals(0L, decision.requestedSlots());
        assertEquals(0, bridge.calls.get(), "known zero-delta rehydrate must not probe a provider");
        assertTrue(service.claimForApply(decision.reservation(), new ClaimLookupSession(policy)));
        assertTrue(service.commit(decision.reservation()));
        assertEquals(CompanionLifecycleState.ACTIVE, index.entry("subject").orElseThrow().lifecycleState());
    }

    @Test
    void dormantSameLocationRestoresAlwaysRequireANewPhysicalSlot() {
        for (CompanionLifecycleState lifecycle : List.of(
                CompanionLifecycleState.CAPTURED,
                CompanionLifecycleState.COOP,
                CompanionLifecycleState.DEAD_REVIVABLE,
                CompanionLifecycleState.LOST
        )) {
            List<ClaimOccupancyEntry> entries = fullClaimEntries();
            ClaimOccupancyEntry dormant = entry("subject", lifecycle, DESTINATION, 1L);
            entries.add(dormant);
            ClaimOccupancyIndex index = readyIndex(entries);
            ClaimAdmissionTestFixtures.MutableBridge bridge = bridge(2);
            ClaimPolicyContext policy = context(bridge);
            ClaimAdmissionService service = new ClaimAdmissionService(index);
            ClaimOccupancyTransition transition = new ClaimOccupancyTransition(
                    dormant,
                    entry("subject", CompanionLifecycleState.ACTIVE, DESTINATION, 2L)
            );

            ClaimAdmissionDecision decision = service.reserve(
                    request(List.of(transition), policy, 2, 10),
                    new ClaimLookupSession(policy)
            );

            assertFalse(decision.allowed(), lifecycle.name());
            assertEquals("claim-cap-reached", decision.reason(), lifecycle.name());
            assertEquals(1L, decision.requestedSlots(), lifecycle.name());
        }
    }

    @Test
    void copiedDormantIdentityCannotReplaceAnAlreadyActiveProfile() {
        ClaimOccupancyEntry actual = entry("subject", CompanionLifecycleState.ACTIVE, DESTINATION, 2L);
        ClaimOccupancyIndex index = readyIndex(List.of(actual));
        ClaimAdmissionTestFixtures.MutableBridge bridge = bridge(2);
        ClaimPolicyContext policy = context(bridge);
        ClaimAdmissionService service = new ClaimAdmissionService(index);
        ClaimOccupancyEntry stale = entry("subject", CompanionLifecycleState.CAPTURED, DESTINATION, 1L);
        ClaimOccupancyTransition copiedRestore = new ClaimOccupancyTransition(
                stale,
                entry("subject", CompanionLifecycleState.ACTIVE, DESTINATION, 2L)
        );

        ClaimAdmissionDecision decision = service.reserve(
                request(List.of(copiedRestore), policy, 10, 10),
                new ClaimLookupSession(policy)
        );

        assertFalse(decision.allowed());
        assertEquals("claim-occupancy-state-mismatch", decision.reason());
        assertEquals(actual, index.entry("subject").orElseThrow());
    }

    @Test
    void naturalMovementCanCreateOverCapButBlocksTheNextAdmission() {
        List<ClaimOccupancyEntry> entries = fullClaimEntries();
        ClaimChunkCoordinate outside = new ClaimChunkCoordinate("world", 9, 0);
        entries.add(entry("walker", CompanionLifecycleState.ACTIVE, outside, 1L));
        ClaimOccupancyIndex index = readyIndex(entries);

        boolean observed = index.observeMovement(
                entry("walker", CompanionLifecycleState.ACTIVE, DESTINATION, 2L)
        );

        ClaimAdmissionTestFixtures.MutableBridge bridge = bridge(2);
        ClaimPolicyContext policy = context(bridge);
        ClaimAdmissionDecision next = new ClaimAdmissionService(index).reserve(
                request(List.of(newActive("next")), policy, 2, 10),
                new ClaimLookupSession(policy)
        );
        assertTrue(observed);
        assertEquals(5, index.snapshot().profilesIn(footprint(2)).size());
        assertFalse(next.allowed());
        assertEquals("claim-cap-reached", next.reason());
    }

    @Test
    void incompleteIndexDeniesPositiveAdmissionButAllowsKnownRehydrate() {
        ClaimOccupancyEntry unloaded = entry("subject", CompanionLifecycleState.UNLOADED, DESTINATION, 1L);
        ClaimOccupancyIndex index = new ClaimOccupancyIndex();
        index.replaceCommittedEntries(List.of(unloaded), ClaimOccupancyReadiness.LOADING);
        ClaimAdmissionTestFixtures.MutableBridge bridge = bridge(2);
        ClaimPolicyContext policy = context(bridge);
        ClaimAdmissionService service = new ClaimAdmissionService(index);

        ClaimAdmissionDecision positive = service.reserve(
                request(List.of(newActive("new")), policy, 2, 10),
                new ClaimLookupSession(policy)
        );
        ClaimAdmissionDecision rehydrate = service.reserve(
                request(List.of(new ClaimOccupancyTransition(
                        unloaded,
                        entry("subject", CompanionLifecycleState.ACTIVE, DESTINATION, 2L)
                )), policy, 2, 10),
                new ClaimLookupSession(policy)
        );

        assertFalse(positive.allowed());
        assertEquals("claim-occupancy-not-ready", positive.reason());
        assertTrue(rehydrate.allowed());
    }

    private static ArrayList<ClaimOccupancyEntry> fullClaimEntries() {
        ArrayList<ClaimOccupancyEntry> entries = new ArrayList<>();
        entries.add(entry("existing-0", CompanionLifecycleState.ACTIVE, DESTINATION, 1L));
        entries.add(entry("existing-1", CompanionLifecycleState.ACTIVE, DESTINATION, 1L));
        ClaimChunkCoordinate second = new ClaimChunkCoordinate("world", 1, 0);
        entries.add(entry("existing-2", CompanionLifecycleState.ACTIVE, second, 1L));
        entries.add(entry("existing-3", CompanionLifecycleState.UNLOADED, second, 1L));
        return entries;
    }

    private static ClaimAdmissionTestFixtures.MutableBridge bridge(int chunks) {
        return new ClaimAdmissionTestFixtures.MutableBridge(ClaimResolution.found(key(), footprint(chunks)));
    }
}
