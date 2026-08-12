package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Recovery-time correction for paid revival projections from older releases. */
class HytalePaidRevivalProjectionGatewayTest {

    @Test
    void oldPaidRevivalProjectionStartsAliveWithoutLethalNeedsState() {
        TameworkNeedsComponent needs = new TameworkNeedsComponent(
                "needs", 0.0D, 0.0D, 25.0D, 40.0D,
                1_000L, 2_000L
        );
        CoopResidentStateSnapshot dead = new CoopResidentStateSnapshot(
                UUID.randomUUID(), null, -1, "tamework_test",
                null, null, null, null, null, needs,
                null, null, null, null, null, null,
                0.0D, 40.0D, 0.0D, 3_000L
        );

        SnapshotDecodeResult.Decoded<CoopResidentStateSnapshot> decoded =
                (SnapshotDecodeResult.Decoded<CoopResidentStateSnapshot>)
                        HytalePaidRevivalProjectionGateway
                                .normalizeProjection(
                                        new SnapshotDecodeResult.Decoded<>(dead)
                                );

        assertEquals(40.0D, decoded.value().currentHealth());
        assertEquals(40.0D, decoded.value().maximumHealth());
        assertEquals(100.0D, decoded.value().healthPercent());
        assertNull(decoded.value().needs());
    }
}
