package com.alechilles.alecstamework.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BondedVesselHeldItemLocatorContractTest {
    @Test
    void unavailableFacadeNeverFabricatesExactInventoryEvidence() {
        BondedVesselHeldItemLocatorRequest request = request(UUID.randomUUID());

        BondedVesselHeldItemLocatorResult result = BondedVesselsApi.unavailable()
                .resolveHeldItemLocator(request).toCompletableFuture().join();

        assertEquals(BondedVesselHeldItemProjectionStatus.UNAVAILABLE, result.status());
        assertFalse(result.authoritative());
        assertTrue(result.resolvedSourceEvidence().isEmpty());
        assertTrue(result.resolvedVessel().isEmpty());
        assertThrows(NullPointerException.class,
                () -> BondedVesselsApi.unavailable().resolveHeldItemLocator(null));
    }

    @Test
    void validResultRequiresExactRequestedLocationAndCanonicalVessel() {
        UUID owner = UUID.randomUUID();
        BondedVesselHeldItemLocatorRequest request = request(owner);
        BondedVesselSourceItemEvidence evidence = evidence(owner, 2, "Damaged_Draconic_Stone");
        BondedVesselView vessel = vessel(owner, BondedVesselState.DEAD,
                BondedVesselProjectionStatus.PRESENT);

        BondedVesselHeldItemLocatorResult result = new BondedVesselHeldItemLocatorResult(
                BondedVesselHeldItemProjectionStatus.VALID,
                "held-item-authoritative",
                request,
                evidence,
                vessel,
                true);

        assertTrue(result.authoritative());
        assertEquals(evidence, result.resolvedSourceEvidence().orElseThrow());
        assertEquals(vessel, result.resolvedVessel().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new BondedVesselHeldItemLocatorResult(
                BondedVesselHeldItemProjectionStatus.VALID,
                "wrong-slot",
                request,
                evidence(owner, 3, "Damaged_Draconic_Stone"),
                vessel,
                true));
        assertThrows(IllegalArgumentException.class, () -> new BondedVesselHeldItemLocatorResult(
                BondedVesselHeldItemProjectionStatus.VALID,
                "wrong-item",
                request,
                evidence(owner, 2, "Filled_Draconic_Stone"),
                vessel,
                true));
    }

    private static BondedVesselHeldItemLocatorRequest request(UUID owner) {
        return new BondedVesselHeldItemLocatorRequest(
                owner,
                "player:" + owner,
                "hotbar",
                2,
                "Damaged_Draconic_Stone",
                BondedVesselState.DEAD);
    }

    private static BondedVesselSourceItemEvidence evidence(UUID owner, int slot, String itemId) {
        return new BondedVesselSourceItemEvidence(
                itemId,
                "player:" + owner,
                "hotbar",
                slot,
                18L,
                "sha256:binding-generation-9");
    }

    private static BondedVesselView vessel(
            UUID owner,
            BondedVesselState state,
            BondedVesselProjectionStatus projectionStatus) {
        return new BondedVesselView(
                UUID.randomUUID(),
                "profile-dragon-1",
                owner,
                "Hydragon_Draconic_Stone",
                state,
                9L,
                14L,
                null,
                projectionStatus,
                null,
                100L);
    }
}
