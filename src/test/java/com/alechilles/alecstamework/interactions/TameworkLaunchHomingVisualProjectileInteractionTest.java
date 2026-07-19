package com.alechilles.alecstamework.interactions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TameworkLaunchHomingVisualProjectileInteractionTest {
    @Test
    void interactionUsesSharedVisualSpawnerWithoutCombatProjectilePath() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/interactions/"
                        + "TameworkLaunchHomingVisualProjectileInteraction.java"
        ));

        assertTrue(source.contains("new KeyedCodec<>(\"Source\""));
        assertTrue(source.contains("new KeyedCodec<>(\"Target\""));
        assertTrue(source.contains("HomingVisualProjectileSpawner.spawn("));
        assertTrue(source.contains("targetAnchor"));
        assertFalse(source.contains("server.core.entity.entities.ProjectileComponent"));
        assertFalse(source.contains("DamageSystems"));
    }
}
