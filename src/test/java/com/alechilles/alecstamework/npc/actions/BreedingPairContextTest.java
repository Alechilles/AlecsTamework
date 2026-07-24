package com.alechilles.alecstamework.npc.actions;

import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Regression coverage for the small delayed-pair snapshot boundary. */
class BreedingPairContextTest {
    @Test
    void spawnAnchorIsDefensivelyCopiedAcrossDelayedWork() {
        Vector3d anchor = new Vector3d(1.0, 2.0, 3.0);
        BreedingPairContext context = new BreedingPairContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "parent-a",
                "parent-b",
                1,
                2,
                anchor,
                BreedingOffspringProgressionService.OwnerSnapshot.empty(),
                BreedingOffspringProgressionService.OwnerSnapshot.empty(),
                true,
                true,
                "default"
        );

        anchor.x = 99.0;
        Vector3d returned = context.spawnAnchor();
        returned.z = 77.0;

        assertEquals(new Vector3d(1.0, 2.0, 3.0), context.spawnAnchor());
        assertNotEquals(anchor, context.spawnAnchor());
    }
}
