package com.alechilles.alecstamework.npc.systems;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static regression guard for the recovery projection ECS boundary. */
class RecoveryProjectionReconciliationSystemArchitectureTest {
    @Test
    void addSystemSnapshotsThroughCommandBufferAndPerformsNoDirectEcsWrites() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/"
                        + "RecoveryProjectionReconciliationSystem.java"));

        assertTrue(source.contains("commandBuffer.getComponent(reference, projectionType)"));
        assertTrue(source.contains("commandBuffer.getComponent(reference, npcType)"));
        assertTrue(source.contains("commandBuffer.getComponent(reference, uuidType)"));
        assertTrue(source.contains("commandLinks.getToolIds().clone()"));
        assertTrue(source.contains("reconciliationService.reconcile(observation)"));
        assertTrue(source.contains("Query.and(npcType, uuidType, projectionType)"));
        assertFalse(source.contains("store.getComponent("));
        assertFalse(source.contains("store.putComponent("));
        assertFalse(source.contains("store.removeComponent("));
        assertFalse(source.contains("commandBuffer.putComponent("));
        assertFalse(source.contains("commandBuffer.removeComponent("));
    }
}
