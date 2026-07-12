package com.alechilles.alecstamework.integration.claims;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimOccupancyIndexTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void onlyOwnedActiveAndUnloadedProfilesOccupyPhysicalChunks() {
        ClaimChunkCoordinate chunk = new ClaimChunkCoordinate("world", 2, 3);
        ClaimOccupancyIndex index = new ClaimOccupancyIndex();
        index.replaceCommittedEntries(List.of(
                entry("active", CompanionLifecycleState.ACTIVE, chunk, 1L),
                entry("unloaded", CompanionLifecycleState.UNLOADED, chunk, 1L),
                entry("captured", CompanionLifecycleState.CAPTURED, chunk, 1L),
                entry("coop", CompanionLifecycleState.COOP, chunk, 1L),
                entry("dead", CompanionLifecycleState.DEAD_REVIVABLE, chunk, 1L),
                entry("lost", CompanionLifecycleState.LOST, chunk, 1L),
                new ClaimOccupancyEntry("unowned", null, CompanionLifecycleState.ACTIVE, chunk, 1L)
        ), ClaimOccupancyReadiness.READY);

        ClaimOccupancySnapshot snapshot = index.snapshot();

        assertEquals(2, snapshot.occupiedProfileCount());
        assertEquals(Set.of("active", "unloaded"), snapshot.profilesByChunk().get(chunk));
        assertEquals(ClaimOccupancyReadiness.READY, index.readiness());
    }

    @Test
    void exactFootprintDeduplicatesCanonicalProfiles() {
        ClaimChunkCoordinate first = new ClaimChunkCoordinate("world", 0, 0);
        ClaimChunkCoordinate second = new ClaimChunkCoordinate("world", 1, 0);
        ClaimOccupancyIndex index = new ClaimOccupancyIndex();
        index.replaceCommittedEntries(List.of(
                entry("one", CompanionLifecycleState.ACTIVE, first, 1L),
                entry("two", CompanionLifecycleState.UNLOADED, second, 1L)
        ), ClaimOccupancyReadiness.READY);

        Set<String> profiles = index.snapshot().profilesIn(new ClaimFootprint(List.of(first, second, first)));

        assertEquals(Set.of("one", "two"), profiles);
    }

    @Test
    void naturalMovementIsObservedWithoutAnyCapDecision() {
        ClaimChunkCoordinate source = new ClaimChunkCoordinate("world", 0, 0);
        ClaimChunkCoordinate destination = new ClaimChunkCoordinate("world", 9, 9);
        ClaimOccupancyIndex index = new ClaimOccupancyIndex();
        index.replaceCommittedEntries(
                List.of(entry("walker", CompanionLifecycleState.ACTIVE, source, 1L)),
                ClaimOccupancyReadiness.READY
        );

        boolean observed = index.observeMovement(entry("walker", CompanionLifecycleState.ACTIVE, destination, 2L));

        assertTrue(observed);
        assertEquals(Set.of("walker"), index.snapshot().profilesByChunk().get(destination));
        assertFalse(index.snapshot().profilesByChunk().containsKey(source));
    }

    @Test
    void duplicateCanonicalProfilesAreRejectedDuringBackfill() {
        ClaimChunkCoordinate chunk = new ClaimChunkCoordinate("world", 0, 0);
        ClaimOccupancyIndex index = new ClaimOccupancyIndex();

        assertThrows(IllegalArgumentException.class, () -> index.replaceCommittedEntries(List.of(
                entry("same", CompanionLifecycleState.ACTIVE, chunk, 1L),
                entry("same", CompanionLifecycleState.UNLOADED, chunk, 2L)
        ), ClaimOccupancyReadiness.READY));
    }

    private static ClaimOccupancyEntry entry(String profileId,
                                             CompanionLifecycleState lifecycle,
                                             ClaimChunkCoordinate chunk,
                                             long revision) {
        return new ClaimOccupancyEntry(profileId, OWNER, lifecycle, chunk, revision);
    }
}
