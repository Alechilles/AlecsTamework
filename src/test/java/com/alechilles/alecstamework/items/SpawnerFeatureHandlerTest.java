package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerFeatureHandlerTest {

    @Test
    void interactionSpawnAssignsOwnerOverrideWinsOverGlobalDefault() throws Exception {
        ItemFeatureConfig baseConfig = ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnAssignsOwner(true)
                .build();

        ItemFeatureConfig resolved = buildSpawnerConfigForInteraction(baseConfig, false);

        assertFalse(resolved.isSpawnAssignsOwner());
    }

    @Test
    void missingInteractionSpawnAssignsOwnerOverrideUsesRuntimeDefault() throws Exception {
        ItemFeatureConfig baseConfig = ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnAssignsOwner(false)
                .build();

        ItemFeatureConfig resolved = buildSpawnerConfigForInteraction(baseConfig, null);

        assertTrue(resolved.isSpawnAssignsOwner());
    }

    @Test
    void spawnUpdatesHeldItemBeforeWorldMutationAndSnapshotClear() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"));

        int updateHeldItem = source.indexOf("playerInventoryService.updateHeldItem(player, updated)");
        int spawnEntity = source.indexOf("npcPlugin.spawnEntity(");
        int clearCapturedSnapshot = source.indexOf("linkedNpcSyncService.clearCapturedSnapshotIfPresent(capturedNpcUuid)");

        assertTrue(updateHeldItem >= 0, "spawn path must update held item");
        assertTrue(spawnEntity >= 0, "spawn path must spawn an entity");
        assertTrue(clearCapturedSnapshot >= 0, "spawn path must clear captured snapshot");
        assertTrue(updateHeldItem < spawnEntity, "item consumption must happen before world mutation");
        assertTrue(updateHeldItem < clearCapturedSnapshot, "item consumption must happen before snapshot clear");
    }

    @Test
    void spawnRollsBackHeldItemWhenWorldMutationFailsAfterConsume() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"));

        int spawnFailure = source.indexOf("spawn denied reason=spawn-entity-failed");
        int rollbackHeldItem = source.indexOf("playerInventoryService.updateHeldItem(player, itemStack)");

        assertTrue(spawnFailure >= 0, "spawn failure path must still be present");
        assertTrue(rollbackHeldItem >= 0, "spawn failure after consumption must roll the held item back");
        assertTrue(spawnFailure < rollbackHeldItem, "rollback should be tied to spawn failure handling");
    }

    @Test
    void captureRejectsStackedSpawnerItemsBeforeMetadataWrite() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"));

        int quantityGuard = source.indexOf("itemStack.getQuantity() != 1");
        int capturedMetadata = source.indexOf(".withMetadata(TameworkMetadataKeys.CAPTURED");

        assertTrue(quantityGuard >= 0, "capture path must reject stacked spawner items");
        assertTrue(capturedMetadata >= 0, "capture path must write captured metadata");
        assertTrue(quantityGuard < capturedMetadata, "stack guard must run before captured metadata is stamped");
    }

    private static ItemFeatureConfig buildSpawnerConfigForInteraction(ItemFeatureConfig baseConfig,
                                                                      Boolean spawnAssignsOwnerOverride)
            throws Exception {
        SpawnerFeatureHandler handler = new SpawnerFeatureHandler(null, null, null, null, null, null, null);
        Method method = SpawnerFeatureHandler.class.getDeclaredMethod(
                "buildSpawnerConfigForInteraction",
                ItemFeatureConfig.class,
                Boolean.class
        );
        method.setAccessible(true);
        return (ItemFeatureConfig) method.invoke(handler, baseConfig, spawnAssignsOwnerOverride);
    }
}
