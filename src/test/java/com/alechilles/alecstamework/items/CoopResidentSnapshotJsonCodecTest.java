package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CoopResidentSnapshotJsonCodecTest {
    private final CoopResidentSnapshotJsonCodec codec = new CoopResidentSnapshotJsonCodec();

    @Test
    void roundTripsIdentityAndNormalizesDurableIdentifiers() {
        UUID npcUuid = UUID.randomUUID();
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot = minimalSnapshot(
                npcUuid,
                "  Coop_Chicken  ",
                "  Tamed_Chicken  ",
                -10L
        );

        CoopResidentStateSnapshotService.CoopResidentStateSnapshot decoded =
                codec.deserialize(codec.serialize(snapshot));

        assertNotNull(decoded);
        assertEquals(npcUuid, decoded.npcUuid());
        assertEquals("coop_chicken", decoded.coopId());
        assertEquals("tamed_chicken", decoded.roleId());
        assertEquals(0L, decoded.capturedAtMs());
    }

    @Test
    void acceptsVersionlessLegacySnapshot() {
        UUID npcUuid = UUID.randomUUID();

        CoopResidentStateSnapshotService.CoopResidentStateSnapshot decoded = codec.deserialize(
                "{\"npcUuid\":\"" + npcUuid + "\",\"residentSlot\":3}"
        );

        assertNotNull(decoded);
        assertEquals(npcUuid, decoded.npcUuid());
        assertEquals(3, decoded.residentSlot());
    }

    @Test
    void rejectsMalformedOrUnsupportedSnapshots() {
        assertNull(codec.deserialize(null));
        assertNull(codec.deserialize("not-json"));
        assertNull(codec.deserialize("{\"version\":\"2\",\"npcUuid\":\"" + UUID.randomUUID() + "\"}"));
        assertNull(codec.deserialize("{\"version\":\"1\",\"npcUuid\":\"not-a-uuid\"}"));
    }

    private CoopResidentStateSnapshotService.CoopResidentStateSnapshot minimalSnapshot(UUID npcUuid,
                                                                                         String coopId,
                                                                                         String roleId,
                                                                                         long capturedAtMs) {
        return new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                npcUuid,
                coopId,
                1,
                roleId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                50.0,
                capturedAtMs
        );
    }
}
