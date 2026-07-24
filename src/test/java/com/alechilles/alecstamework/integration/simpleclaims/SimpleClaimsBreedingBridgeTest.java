package com.alechilles.alecstamework.integration.simpleclaims;

import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** Regression coverage for lifecycle-safe SimpleClaims bridge initialization. */
class SimpleClaimsBreedingBridgeTest {
    @Test
    void initializeDoesNotRetainGenerationBlindStaticBridge() {
        SimpleClaimsBreedingBridge first = SimpleClaimsBreedingBridge.initialize();
        SimpleClaimsBreedingBridge second = SimpleClaimsBreedingBridge.initialize();

        assertNotSame(first, second);
        assertEquals(first.providerId(), second.providerId());
    }

    @Test
    void unavailableSimpleClaimsBridgeReturnsProviderNeutralUnavailableLookup() {
        SimpleClaimsBreedingBridge bridge = SimpleClaimsBreedingBridge.initialize();

        if (bridge.isAvailable()) {
            return;
        }

        assertEquals("simpleclaims", bridge.providerId());
        assertEquals(
                SimpleClaimsBreedingBridge.LookupStatus.UNAVAILABLE,
                bridge.lookupClaim("world", 0, 0).status()
        );
    }

    @Test
    void releasedBreedingLookupReturnsProviderClaimCount() {
        UUID partyId = UUID.randomUUID();
        FixtureManager.instance = new FixtureManager(partyId, 7);
        SimpleClaimsBreedingBridge bridge = SimpleClaimsBreedingBridge.forTypesForTests(
                FixtureManager.class,
                FixtureChunk.class,
                FixtureParty.class
        );

        SimpleClaimsBreedingBridge.LookupResult result =
                bridge.lookupSimpleClaimsClaim("world", 12.0, 34.0);

        assertEquals(SimpleClaimsBreedingBridge.LookupStatus.CLAIM_FOUND, result.status());
        assertEquals(partyId, result.claimInfo().partyId());
        assertEquals(7, result.claimInfo().claimChunkCount());
    }

    public static final class FixtureManager {
        private static FixtureManager instance;
        private final FixtureParty party;
        private final int claimCount;

        private FixtureManager(UUID partyId, int claimCount) {
            this.party = new FixtureParty(partyId);
            this.claimCount = claimCount;
        }

        public static FixtureManager getInstance() {
            return instance;
        }

        public FixtureChunk getChunkRawCoords(String worldName, int blockX, int blockZ) {
            return new FixtureChunk(party.id());
        }

        public FixtureParty getPartyById(UUID partyId) {
            return party.id().equals(partyId) ? party : null;
        }

        public int getAmountOfClaims(FixtureParty ignored) {
            return claimCount;
        }
    }

    public record FixtureChunk(UUID partyOwner) {
        public UUID getPartyOwner() {
            return partyOwner;
        }
    }

    public record FixtureParty(UUID id) {
    }
}
