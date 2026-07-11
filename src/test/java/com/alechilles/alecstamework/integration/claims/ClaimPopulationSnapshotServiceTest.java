package com.alechilles.alecstamework.integration.claims;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimPopulationSnapshotServiceTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PARTY = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void exactFootprintSumsUniquePhysicalProfilesWithoutProviderScans() {
        ClaimChunkCoordinate first = new ClaimChunkCoordinate("world", 0, 0);
        ClaimChunkCoordinate second = new ClaimChunkCoordinate("world", 1, 0);
        ClaimChunkCoordinate outside = new ClaimChunkCoordinate("world", 2, 0);
        ClaimPopulationKey key = ClaimPopulationKey.simpleClaims("world", PARTY);
        ClaimResolution target = ClaimResolution.found(key, new ClaimFootprint(List.of(first, second)));
        ConstantBridge bridge = new ConstantBridge(target);
        ClaimLookupSession session = new ClaimLookupSession(context(bridge));
        ClaimOccupancyIndex index = readyIndex(List.of(
                active("first", first),
                unloaded("second", second),
                active("outside", outside),
                dormant("stored", first)
        ));

        ClaimPopulationSnapshot snapshot = new ClaimPopulationSnapshotService().snapshot(index, target, session);

        assertEquals(ClaimPopulationSnapshot.Status.READY, snapshot.status());
        assertEquals(Set.of("first", "second"), snapshot.profileIds());
        assertEquals(2, snapshot.population());
        assertEquals(0L, session.providerCallCount());
    }

    @Test
    void lookupOnlyPopulationCallsProviderOncePerUniqueChunkAtScale() {
        int[][] scenarios = {
                {100, 7},
                {1_000, 37},
                {5_000, 113}
        };
        for (int[] scenario : scenarios) {
            assertLookupScaling(scenario[0], scenario[1]);
        }
    }

    @Test
    void lookupOnlyPopulationSkipsOccupiedChunksFromOtherWorlds() {
        ClaimPopulationKey key = ClaimPopulationKey.simpleClaims("world", PARTY);
        ClaimResolution lookupOnly = ClaimResolution.foundWithoutFootprint(key, 0);
        ConstantBridge bridge = new ConstantBridge(lookupOnly);
        ClaimLookupSession session = new ClaimLookupSession(context(bridge));
        ClaimOccupancyIndex index = readyIndex(List.of(
                active("target-world", new ClaimChunkCoordinate("world", 1, 1)),
                active("other-world", new ClaimChunkCoordinate("other", 1, 1))
        ));

        ClaimPopulationSnapshot snapshot = new ClaimPopulationSnapshotService().snapshot(
                index,
                lookupOnly,
                session
        );

        assertEquals(Set.of("target-world"), snapshot.profileIds());
        assertEquals(1L, session.providerCallCount());
    }

    private void assertLookupScaling(int profileCount, int uniqueChunks) {
        ClaimPopulationKey key = ClaimPopulationKey.simpleClaims("world", PARTY);
        ClaimResolution lookupOnly = ClaimResolution.foundWithoutFootprint(key, 0);
        ConstantBridge bridge = new ConstantBridge(lookupOnly);
        ClaimLookupSession session = new ClaimLookupSession(context(bridge));
        List<ClaimOccupancyEntry> entries = new ArrayList<>(profileCount);
        for (int profile = 0; profile < profileCount; profile++) {
            ClaimChunkCoordinate chunk = new ClaimChunkCoordinate("world", profile % uniqueChunks, 0);
            entries.add(active("profile-" + profile, chunk));
        }
        ClaimOccupancyIndex index = readyIndex(entries);

        ClaimPopulationSnapshot snapshot = new ClaimPopulationSnapshotService().snapshot(
                index,
                lookupOnly,
                session
        );

        assertEquals(profileCount, snapshot.population(), "profile count " + profileCount);
        assertEquals(uniqueChunks, session.uniqueChunkCount(), "profile count " + profileCount);
        assertEquals(uniqueChunks, session.providerCallCount(), "profile count " + profileCount);
        assertEquals(uniqueChunks, session.requestCount(), "profile count " + profileCount);
    }

    private static ClaimOccupancyIndex readyIndex(List<ClaimOccupancyEntry> entries) {
        ClaimOccupancyIndex index = new ClaimOccupancyIndex();
        index.replaceCommittedEntries(entries, ClaimOccupancyReadiness.READY);
        return index;
    }

    private static ClaimOccupancyEntry active(String id, ClaimChunkCoordinate chunk) {
        return new ClaimOccupancyEntry(id, OWNER, CompanionLifecycleState.ACTIVE, chunk, 1L);
    }

    private static ClaimOccupancyEntry unloaded(String id, ClaimChunkCoordinate chunk) {
        return new ClaimOccupancyEntry(id, OWNER, CompanionLifecycleState.UNLOADED, chunk, 1L);
    }

    private static ClaimOccupancyEntry dormant(String id, ClaimChunkCoordinate chunk) {
        return new ClaimOccupancyEntry(id, OWNER, CompanionLifecycleState.CAPTURED, chunk, 1L);
    }

    private static ClaimPolicyContext context(ClaimIntegrationBridge bridge) {
        return new ClaimPolicyContext(
                "SimpleClaims",
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                bridge.providerId(),
                ClaimProviderState.READY,
                Set.of(ClaimProviderCapability.STABLE_CLAIM_IDENTITY),
                "test",
                null,
                new ClaimProviderGeneration("instance", "loader", 1L),
                1L,
                bridge
        );
    }

    private record ConstantBridge(ClaimResolution resolution) implements ClaimIntegrationBridge {
        @Override
        public String providerId() {
            return "simpleclaims";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getUnavailableReason() {
            return null;
        }

        @Override
        public ClaimResolution resolveClaim(String worldName, double blockX, double blockZ) {
            return resolution;
        }

        @Override
        public ClaimLookupResult lookupClaim(String worldName, double blockX, double blockZ) {
            return resolution.toLookupResult();
        }
    }
}
