package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class PositionTargetRejectCacheTest {
    @Test
    void rejectedPositionIsNpcLabelSpecificAndExpires() {
        PositionTargetRejectCache.clearForTests();
        UUID npc = new UUID(0L, 12L);
        Vector3d target = new Vector3d(4.25, 64.05, -8.75);

        assertTrue(PositionTargetRejectCache.reject(npc, "HayBlock", target, 5.0, 1_000L));
        assertTrue(PositionTargetRejectCache.isRejected(
                npc,
                "hayblock",
                new Vector3d(4.75, 64.95, -8.25),
                5_999L
        ));
        assertFalse(PositionTargetRejectCache.isRejected(npc, "Water", target, 5_999L));
        assertFalse(PositionTargetRejectCache.isRejected(npc, "HayBlock", target, 6_000L));
    }

    @Test
    void rejectedPositionCacheStaysBounded() {
        PositionTargetRejectCache.clearForTests();
        UUID npc = new UUID(0L, 24L);

        for (int i = 0; i < PositionTargetRejectCache.MAX_ENTRIES + 25; i++) {
            assertTrue(PositionTargetRejectCache.reject(
                    npc,
                    "Block",
                    new Vector3d(i, 64.0, 0.0),
                    120.0,
                    1_000L + i
            ));
        }

        assertTrue(PositionTargetRejectCache.countForTests() <= PositionTargetRejectCache.MAX_ENTRIES);
    }

    @Test
    void blankLabelsNormalizeToGeneric() {
        assertEquals("generic", PositionTargetRejectCache.normalizeLabel(null));
        assertEquals("generic", PositionTargetRejectCache.normalizeLabel(" "));
        assertEquals("hayblock", PositionTargetRejectCache.normalizeLabel(" HayBlock "));
    }
}
