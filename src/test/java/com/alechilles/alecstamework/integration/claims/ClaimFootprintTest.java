package com.alechilles.alecstamework.integration.claims;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClaimFootprintTest {
    @Test
    void footprintCopiesDeduplicatesAndCanonicalizesInput() {
        ArrayList<ClaimChunkCoordinate> source = new ArrayList<>(List.of(
                new ClaimChunkCoordinate("world", 4, 3),
                new ClaimChunkCoordinate("world", -2, 8),
                new ClaimChunkCoordinate("world", 4, 3)
        ));

        ClaimFootprint footprint = new ClaimFootprint(source);
        source.clear();

        assertEquals(
                List.of(
                        new ClaimChunkCoordinate("world", -2, 8),
                        new ClaimChunkCoordinate("world", 4, 3)
                ),
                footprint.chunks()
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> footprint.chunks().add(new ClaimChunkCoordinate("world", 10, 10))
        );
    }

    @Test
    void digestIsIndependentOfInputOrderAndDuplicates() {
        ClaimChunkCoordinate first = new ClaimChunkCoordinate("world", -1, -2);
        ClaimChunkCoordinate second = new ClaimChunkCoordinate("world", 3, 4);

        ClaimFootprint forward = new ClaimFootprint(List.of(first, second, first));
        ClaimFootprint reversed = new ClaimFootprint(List.of(second, first));

        assertEquals(forward.digest(), reversed.digest());
    }

    @Test
    void legacyBridgeReceivesAdditiveScalarResolutionByDefault() {
        ClaimPopulationKey key = ClaimPopulationKey.simpleClaims("world", UUID.randomUUID());
        ClaimIntegrationBridge legacyBridge = new ClaimIntegrationBridge() {
            @Override
            public String providerId() {
                return "legacy";
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
                return ClaimLookupResult.found(key, 7);
            }
        };

        ClaimResolution resolution = legacyBridge.resolveClaim("world", 0, 0);

        assertEquals(ClaimLookupResult.Status.CLAIM_FOUND, resolution.status());
        assertEquals(7, resolution.claimChunkCount());
        assertEquals(key, resolution.key());
        assertNull(resolution.footprint());
    }
}
