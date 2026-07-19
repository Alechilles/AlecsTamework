package com.alechilles.alecstamework.vfx.projectile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class HomingVisualProjectileComponentTest {
    @Test
    void clonePreservesIndependentRuntimeState() {
        UUID destination = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        HomingVisualProjectileSpec spec = new HomingVisualProjectileSpec(
                "Capture_Mote", HomingVisualProjectileAnchor.HELD_ITEM,
                8.0D, 0.0D, 0.18D, 2.0D
        );
        HomingVisualProjectileComponent original = new HomingVisualProjectileComponent(
                destination.toString(), spec, owner.toString(), source.toString(), "default", 7L
        );
        original.setLastDirection(new Vector3d(1.0D, 2.0D, 3.0D));

        HomingVisualProjectileComponent copy = original.clone();

        assertNotSame(original, copy);
        assertEquals(destination.toString(), copy.getDestinationUuid());
        assertEquals(HomingVisualProjectileAnchor.HELD_ITEM, copy.getDestinationAnchor());
        assertEquals(owner.toString(), copy.getOwnerUuid());
        assertEquals(source.toString(), copy.getSourceUuid());
        assertEquals(7L, copy.getSessionGeneration());
        assertEquals(new Vector3d(1.0D, 2.0D, 3.0D), copy.getLastDirection());
        assertTrue(copy.isSessionBound());
    }
}
