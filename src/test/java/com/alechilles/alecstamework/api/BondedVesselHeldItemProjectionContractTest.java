package com.alechilles.alecstamework.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BondedVesselHeldItemProjectionContractTest {
    @Test
    void unavailableFacadeNeverTurnsHeldEvidenceIntoAuthority() {
        BondedVesselHeldItemProjectionRequest request = request(UUID.randomUUID());

        BondedVesselHeldItemProjectionView result =
                BondedVesselsApi.unavailable().resolveHeldItemProjection(request)
                        .toCompletableFuture().join();

        assertEquals(BondedVesselHeldItemProjectionStatus.UNAVAILABLE, result.status());
        assertEquals(request, result.request());
        assertFalse(result.authoritative());
        assertTrue(result.resolvedVessel().isEmpty());
        assertThrows(
                NullPointerException.class,
                () -> BondedVesselsApi.unavailable().resolveHeldItemProjection(null)
        );
    }

    @Test
    void validDamagedProjectionCarriesExactSourceAndCanonicalIdentity() {
        UUID owner = UUID.randomUUID();
        BondedVesselHeldItemProjectionRequest request = request(owner);
        BondedVesselView vessel = new BondedVesselView(
                UUID.randomUUID(),
                "profile-dragon-1",
                owner,
                "HyDragon_Draconic_Stone",
                BondedVesselState.DEAD,
                9L,
                14L,
                null,
                BondedVesselProjectionStatus.PRESENT,
                null,
                100L
        );

        BondedVesselHeldItemProjectionView result = new BondedVesselHeldItemProjectionView(
                BondedVesselHeldItemProjectionStatus.VALID,
                "valid-damaged-projection",
                request,
                vessel,
                true
        );

        assertTrue(result.authoritative());
        assertEquals(vessel.bindingId(), result.resolvedVessel().orElseThrow().bindingId());
        assertEquals("profile-dragon-1", result.resolvedVessel().orElseThrow().profileId());
        assertEquals(9L, result.resolvedVessel().orElseThrow().generation());
        assertEquals(request.sourceEvidence(), result.request().sourceEvidence());
    }

    @Test
    void validStatusRejectsOwnerStateOrProjectionMismatches() {
        UUID owner = UUID.randomUUID();
        BondedVesselHeldItemProjectionRequest request = request(owner);

        assertThrows(IllegalArgumentException.class, () -> valid(request, UUID.randomUUID(),
                BondedVesselState.DEAD, BondedVesselProjectionStatus.PRESENT));
        assertThrows(IllegalArgumentException.class, () -> valid(request, owner,
                BondedVesselState.STORED, BondedVesselProjectionStatus.PRESENT));
        assertThrows(IllegalArgumentException.class, () -> valid(request, owner,
                BondedVesselState.DEAD, BondedVesselProjectionStatus.AMBIGUOUS));
        assertThrows(IllegalArgumentException.class, () -> new BondedVesselHeldItemProjectionView(
                BondedVesselHeldItemProjectionStatus.SOURCE_CHANGED,
                "changed",
                request,
                null,
                true
        ));
    }

    @Test
    void exactSourceEvidenceIsNormalizedAndBounded() {
        BondedVesselSourceItemEvidence evidence = new BondedVesselSourceItemEvidence(
                "  *Draconic_Stone_State_Damaged ",
                " player:owner ",
                " hotbar ",
                3,
                18L,
                " sha256:binding-generation-9 "
        );

        assertEquals("*Draconic_Stone_State_Damaged", evidence.itemId());
        assertEquals("player:owner", evidence.holderEvidenceId());
        assertEquals("hotbar", evidence.containerPath());
        assertEquals("sha256:binding-generation-9", evidence.itemFingerprint());
        assertThrows(IllegalArgumentException.class, () -> new BondedVesselSourceItemEvidence(
                "item", "holder", "path", -1, 0L, "fingerprint"));
        assertThrows(IllegalArgumentException.class, () -> new BondedVesselSourceItemEvidence(
                "item", "holder", "path", 0, -1L, "fingerprint"));
    }

    private static BondedVesselHeldItemProjectionRequest request(UUID owner) {
        return new BondedVesselHeldItemProjectionRequest(
                owner,
                new BondedVesselSourceItemEvidence(
                        "*Draconic_Stone_State_Damaged",
                        "player:" + owner,
                        "hotbar",
                        2,
                        17L,
                        "sha256:binding-generation-9"
                ),
                BondedVesselState.DEAD
        );
    }

    private static BondedVesselHeldItemProjectionView valid(
            BondedVesselHeldItemProjectionRequest request,
            UUID owner,
            BondedVesselState state,
            BondedVesselProjectionStatus projectionStatus
    ) {
        return new BondedVesselHeldItemProjectionView(
                BondedVesselHeldItemProjectionStatus.VALID,
                "valid",
                request,
                new BondedVesselView(
                        UUID.randomUUID(), "profile", owner, "config", state,
                        2L, 3L, null, projectionStatus, null, 4L
                ),
                true
        );
    }
}
