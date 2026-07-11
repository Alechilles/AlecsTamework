package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.ManagedCoopCapturedItemEnvelopeCodec.DecodeStatus;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent.NameSource;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict captured-item envelope and portable-snapshot regression coverage. */
class ManagedCoopCapturedItemEnvelopeCodecTest {
    private static final UUID SOURCE = new UUID(0L, 101L);
    private static final UUID OWNER = new UUID(0L, 102L);

    @Test
    void roundTripsCompletePortableSnapshotAndDerivesStableIdentity() {
        ManagedCoopCapturedItemEnvelopeCodec codec = new ManagedCoopCapturedItemEnvelopeCodec();
        String encoded = codec.encode("profile-a", portableSnapshot());

        ManagedCoopCapturedItemEnvelopeCodec.DecodeOutcome decoded =
                codec.decode("Tool_Capture_Crate", encoded);

        assertTrue(decoded.found());
        assertNotNull(decoded.envelope());
        assertEquals("profile-a", decoded.envelope().profileId());
        assertEquals(SOURCE, decoded.envelope().sourceNpcUuid());
        assertEquals("mob_chicken", decoded.envelope().roleId());
        assertEquals(OWNER, decoded.envelope().ownerUuid());
        assertEquals("Henrietta", decoded.envelope().displayName());
        assertArrayEquals(new String[]{"tool-a", "tool-b"}, decoded.envelope().toolIds());
        assertTrue(decoded.envelope().fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void tamperedSnapshotHashFailsClosed() {
        ManagedCoopCapturedItemEnvelopeCodec codec = new ManagedCoopCapturedItemEnvelopeCodec();
        JsonObject envelope = JsonParser.parseString(
                codec.encode("profile-a", portableSnapshot())).getAsJsonObject();
        envelope.addProperty("snapshotJson", envelope.get("snapshotJson").getAsString() + " ");

        ManagedCoopCapturedItemEnvelopeCodec.DecodeOutcome decoded =
                codec.decode("Tool_Capture_Crate", envelope.toString());

        assertEquals(DecodeStatus.FAILED, decoded.status());
        assertEquals("managed_coop_item_snapshot_hash_mismatch", decoded.detail());
    }

    @Test
    void missingEnvelopeAndAlreadyHousedSnapshotAreDistinguished() {
        ManagedCoopCapturedItemEnvelopeCodec codec = new ManagedCoopCapturedItemEnvelopeCodec();
        assertEquals(DecodeStatus.NOT_FOUND,
                codec.decode("Tool_Capture_Crate", null).status());

        CoopResidentStateSnapshot portable = portableSnapshot();
        CoopResidentStateSnapshot housed = new CoopResidentStateSnapshot(
                portable.npcUuid(), "coop_chicken", 0, portable.roleId(),
                portable.commandLinks(), portable.owner(), portable.tamed(), portable.npcName(),
                portable.happiness(), portable.needs(), portable.breeding(), portable.leveling(),
                portable.traits(), portable.talents(), portable.lifeStage(), portable.attachments(),
                portable.healthPercent(), portable.capturedAtMs());
        assertThrows(IllegalArgumentException.class, () -> codec.encode("profile-a", housed));
    }

    private CoopResidentStateSnapshot portableSnapshot() {
        return new CoopResidentStateSnapshot(
                SOURCE,
                null,
                -1,
                "Mob_Chicken",
                new TameworkCommandLinksComponent(OWNER, new String[]{"tool-a", "tool-b", "tool-a"}),
                new TameworkOwnerComponent(OWNER, "Owner"),
                null,
                new TameworkNpcNameComponent("Henrietta", OWNER, -20L, NameSource.Player),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0.75,
                -100L
        );
    }
}
