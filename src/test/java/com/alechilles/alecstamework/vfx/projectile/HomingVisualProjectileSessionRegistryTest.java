package com.alechilles.alecstamework.vfx.projectile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HomingVisualProjectileSessionRegistryTest {
    @AfterEach
    void clear() {
        HomingVisualProjectileSessionRegistry.clearForTests();
    }

    @Test
    void generationKeysDoNotCrossPlayersWorldsOrReplacementSessions() {
        String owner = UUID.randomUUID().toString();
        HomingVisualProjectileSessionRegistry.activate("world-a", owner, 7L);

        assertTrue(HomingVisualProjectileSessionRegistry.isActive("world-a", owner, 7L));
        assertFalse(HomingVisualProjectileSessionRegistry.isActive("world-a", owner, 8L));
        assertFalse(HomingVisualProjectileSessionRegistry.isActive("world-b", owner, 7L));

        HomingVisualProjectileSessionRegistry.deactivate("world-a", owner, 7L);
        assertFalse(HomingVisualProjectileSessionRegistry.isActive("world-a", owner, 7L));
    }
}
