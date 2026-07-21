package com.alechilles.alecstamework.vessels.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.api.BondedVesselState;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BondedVesselItemFingerprintCodecTest {
    @Test
    void canonicalPayloadAndSha256AreStable() {
        BondedVesselItemFingerprintCodec codec = new BondedVesselItemFingerprintCodec();
        var metadata = new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                "dragon-stone-dead",
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                "profile-1", 4L, "dragon-vessel", BondedVesselState.DEAD);

        assertEquals("""
                TW_BONDED_VESSEL_ITEM_V1
                itemId=ZHJhZ29uLXN0b25lLWRlYWQ
                bindingId=20000000-0000-0000-0000-000000000002
                profileId=cHJvZmlsZS0x
                generation=4
                configId=ZHJhZ29uLXZlc3NlbA
                state=DEAD
                """, codec.canonicalPayload(metadata));
        assertEquals("sha256:6bbc2d3bd25327d4281d6a18cc4504304a93c4daab5ebaf83ec92b82c138c288",
                codec.fingerprint(metadata));
    }

    @Test
    void evidenceFactoryOwnsPlayerHolderHotbarAndFingerprintConvention() {
        UUID actor = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var metadata = new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                "dragon-stone-dead",
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                "profile-1", 4L, "dragon-vessel", BondedVesselState.DEAD);
        var factory = new BondedVesselHeldSlotEvidenceFactory(
                new BondedVesselItemFingerprintCodec());

        var evidence = factory.create(actor, 2, 19L, metadata);

        assertEquals("player:10000000-0000-0000-0000-000000000001",
                evidence.holderEvidenceId());
        assertEquals("hotbar", evidence.containerPath());
        assertEquals(2, evidence.inventorySlot());
        assertEquals(19L, evidence.inventoryRevision());
        assertEquals("sha256:6bbc2d3bd25327d4281d6a18cc4504304a93c4daab5ebaf83ec92b82c138c288",
                evidence.itemFingerprint());
    }
}
