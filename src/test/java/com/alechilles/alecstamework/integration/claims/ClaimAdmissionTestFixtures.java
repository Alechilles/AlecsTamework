package com.alechilles.alecstamework.integration.claims;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class ClaimAdmissionTestFixtures {
    static final String WORLD = "world";
    static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID PARTY = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final ClaimChunkCoordinate DESTINATION = new ClaimChunkCoordinate(WORLD, 0, 0);

    private ClaimAdmissionTestFixtures() {
    }

    static ClaimOccupancyIndex readyIndex(List<ClaimOccupancyEntry> entries) {
        ClaimOccupancyIndex index = new ClaimOccupancyIndex();
        index.replaceCommittedEntries(entries, ClaimOccupancyReadiness.READY);
        return index;
    }

    static ClaimOccupancyEntry entry(String profileId,
                                     CompanionLifecycleState lifecycle,
                                     ClaimChunkCoordinate chunk,
                                     long revision) {
        return new ClaimOccupancyEntry(profileId, OWNER, lifecycle, chunk, revision);
    }

    static ClaimOccupancyTransition newActive(String profileId) {
        return new ClaimOccupancyTransition(
                null,
                entry(profileId, CompanionLifecycleState.ACTIVE, DESTINATION, 1L)
        );
    }

    static ClaimAdmissionRequest request(List<ClaimOccupancyTransition> transitions,
                                         ClaimPolicyContext context,
                                         int perChunkLimit,
                                         int totalLimit) {
        return request(transitions, context, perChunkLimit, totalLimit, false, 60_000_000_000L);
    }

    static ClaimAdmissionRequest request(List<ClaimOccupancyTransition> transitions,
                                         ClaimPolicyContext context,
                                         int perChunkLimit,
                                         int totalLimit,
                                         boolean force,
                                         long leaseNanos) {
        return new ClaimAdmissionRequest(
                ClaimAdmissionOperation.EXTERNAL,
                transitions,
                DESTINATION,
                context,
                perChunkLimit,
                totalLimit,
                force,
                leaseNanos
        );
    }

    static ClaimPopulationKey key() {
        return ClaimPopulationKey.simpleClaims(WORLD, PARTY);
    }

    static ClaimFootprint footprint(int chunks) {
        java.util.ArrayList<ClaimChunkCoordinate> coordinates = new java.util.ArrayList<>();
        for (int chunk = 0; chunk < chunks; chunk++) {
            coordinates.add(new ClaimChunkCoordinate(WORLD, chunk, 0));
        }
        return new ClaimFootprint(coordinates);
    }

    static ClaimPolicyContext context(MutableBridge bridge) {
        return context(bridge, "instance", 1L);
    }

    static ClaimPolicyContext context(MutableBridge bridge, String token, long settingsRevision) {
        return new ClaimPolicyContext(
                "SimpleClaims",
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                bridge.providerId(),
                ClaimProviderState.READY,
                Set.of(
                        ClaimProviderCapability.STABLE_CLAIM_IDENTITY,
                        ClaimProviderCapability.WORLD_SCOPED_EXTENT
                ),
                "test",
                null,
                new ClaimProviderGeneration(token, "loader-" + token, 1L),
                settingsRevision,
                bridge
        );
    }

    static final class MutableBridge implements ClaimIntegrationBridge {
        final AtomicReference<ClaimResolution> resolution;
        final AtomicInteger calls = new AtomicInteger();

        MutableBridge(ClaimResolution resolution) {
            this.resolution = new AtomicReference<>(resolution);
        }

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
            calls.incrementAndGet();
            return resolution.get();
        }

        @Override
        public ClaimLookupResult lookupClaim(String worldName, double blockX, double blockZ) {
            return resolution.get().toLookupResult();
        }
    }
}
