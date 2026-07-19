package com.alechilles.alecstamework.vfx.projectile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HomingVisualProjectileArchitectureTest {
    @Test
    void visualProjectilePathHasNoCombatProjectileOrDamageDependencies() throws Exception {
        String spawner = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/vfx/projectile/HomingVisualProjectileSpawner.java"
        ));
        String system = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/vfx/projectile/HomingVisualProjectileSystem.java"
        ));

        assertTrue(spawner.contains("NonSerialized.get()"));
        assertTrue(spawner.contains("new ModelComponent(model)"));
        assertFalse(spawner.contains("server.core.entity.entities.ProjectileComponent"));
        assertFalse(spawner.contains("DamageSystems"));
        assertFalse(system.contains("server.core.entity.entities.ProjectileComponent"));
        assertFalse(system.contains("DamageSystems"));
        assertTrue(system.contains("world.getEntityRef(UUID.fromString"));
        assertTrue(system.contains("HomingVisualProjectileSessionRegistry.isActive("));
        assertTrue(spawner.contains("DespawnComponent.despawnInSeconds"));
        assertTrue(system.contains("new DespawnComponent(time.getNow().minusNanos(1L))"));
        assertFalse(system.contains("commandBuffer.removeEntity"));
    }
}
