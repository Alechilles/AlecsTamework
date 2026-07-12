package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationBridge;
import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the legacy tame facade against canonical, indexed claim occupancy. */
class LegacyClaimPopulationLookupServiceTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PARTY = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void countsUniqueCanonicalProfilesWithOneLookupPerOccupiedWorldChunk() {
        ClaimPopulationKey target = ClaimPopulationKey.simpleClaims("world-a", PARTY);
        CountingBridge bridge = new CountingBridge(target);
        ClaimOccupancyIndex index = new ClaimOccupancyIndex();
        index.replaceCommittedEntries(List.of(
                occupancy("active-a", "world-a", 1, 1, CompanionLifecycleState.ACTIVE),
                occupancy("active-b", "world-a", 1, 1, CompanionLifecycleState.ACTIVE),
                occupancy("unloaded", "world-a", 2, 1, CompanionLifecycleState.UNLOADED),
                occupancy("captured", "world-a", 1, 1, CompanionLifecycleState.CAPTURED),
                occupancy("other-world", "world-b", 9, 9, CompanionLifecycleState.ACTIVE)
        ), ClaimOccupancyReadiness.READY);
        LegacyClaimPopulationLookupService service =
                new LegacyClaimPopulationLookupService(bridge, index);

        BreedingClaimLimitPolicyService.CountResult result = service.countOwnedPopulationInClaim(
                "world-a",
                BreedingClaimLimitPolicyService.ClaimReservationKey.fromPopulationKey(target)
        );

        assertTrue(result.success());
        assertEquals(3, result.count());
        assertEquals(2, bridge.lookups.get());
    }

    @Test
    void failsClosedWithoutReadyAuthoritativeOccupancy() {
        ClaimPopulationKey target = ClaimPopulationKey.simpleClaims("world-a", PARTY);
        CountingBridge bridge = new CountingBridge(target);
        ClaimOccupancyIndex loading = new ClaimOccupancyIndex();
        LegacyClaimPopulationLookupService service =
                new LegacyClaimPopulationLookupService(bridge, loading);

        BreedingClaimLimitPolicyService.CountResult result = service.countOwnedPopulationInClaim(
                "world-a",
                BreedingClaimLimitPolicyService.ClaimReservationKey.fromPopulationKey(target)
        );

        assertFalse(result.success());
        assertEquals(0, result.count());
        assertTrue(result.message().contains("not ready"));
        assertEquals(0, bridge.lookups.get());
    }

    private static ClaimOccupancyEntry occupancy(String profileId,
                                                  String worldName,
                                                  int chunkX,
                                                  int chunkZ,
                                                  CompanionLifecycleState lifecycle) {
        return new ClaimOccupancyEntry(
                profileId,
                OWNER,
                lifecycle,
                new ClaimChunkCoordinate(worldName, chunkX, chunkZ),
                1L
        );
    }

    private static final class CountingBridge implements ClaimIntegrationBridge {
        private final ClaimPopulationKey target;
        private final AtomicInteger lookups = new AtomicInteger();

        private CountingBridge(ClaimPopulationKey target) {
            this.target = target;
        }

        @Override
        public String providerId() {
            return target.providerId();
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
        public ClaimLookupResult lookupClaim(String worldName, double blockX, double blockZ) {
            if (!target.worldName().equals(worldName)) {
                throw new AssertionError("Legacy tame counting must not probe another world.");
            }
            lookups.incrementAndGet();
            return ClaimLookupResult.found(target, 2);
        }
    }
}
