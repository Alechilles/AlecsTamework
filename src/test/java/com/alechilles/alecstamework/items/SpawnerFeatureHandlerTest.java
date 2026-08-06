package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void interactionResolverPreservesWildCaptureContract() throws Exception {
        ItemFeatureConfig baseConfig = ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .captureRequireTamed(false)
                .captureTamesTarget(true)
                .captureMaxHealthPercent(20.0d)
                .captureRequiredEffectId("Required")
                .captureChannelAuraEffectId("Aura")
                .captureTamedRoleOverrides(Map.of("Wild", "Tamed"))
                .build();

        ItemFeatureConfig resolved = buildSpawnerConfigForInteraction(baseConfig, null);

        assertTrue(resolved.isCaptureTamesTarget());
        assertEquals(20.0d, resolved.getCaptureMaxHealthPercent());
        assertEquals("Required", resolved.getCaptureRequiredEffectId());
        assertEquals("Aura", resolved.getCaptureChannelAuraEffectId());
        assertEquals("Tamed", resolved.resolveCaptureTamedRole("Wild"));
    }

    @Test
    void wildCaptureDoesNotInventAnOwnerWhenPreservingOwnership() {
        assertNull(SpawnerFeatureHandler.resolveCapturedOwnerMetadata(null, false));
    }

    @Test
    void captureOwnerMetadataPreservesOrClearsTheExistingOwnerExactly() {
        UUID owner = UUID.randomUUID();

        assertEquals(owner, SpawnerFeatureHandler.resolveCapturedOwnerMetadata(owner, false));
        assertNull(SpawnerFeatureHandler.resolveCapturedOwnerMetadata(owner, true));
    }

    @Test
    void captureClearAndSpawnAssignmentMatrixProducesTheExactOwnerTransition() {
        UUID currentOwner = UUID.randomUUID();
        UUID spawningPlayer = UUID.randomUUID();

        for (boolean captureClearsOwner : new boolean[]{false, true}) {
            for (boolean spawnAssignsOwner : new boolean[]{false, true}) {
                ItemFeatureConfig config = ItemFeatureConfig.builder()
                        .spawnerEnabled(true)
                        .captureClearsOwner(captureClearsOwner)
                        .spawnAssignsOwner(spawnAssignsOwner)
                        .build();
                UUID itemOwner = SpawnerFeatureHandler.resolveCapturedOwnerMetadata(
                        currentOwner, captureClearsOwner
                );
                UUID resolvedOwner = SpawnerOwnershipPolicyService.resolveSpawnOwner(
                        itemOwner, spawningPlayer, config
                );
                UUID expectedOwner = captureClearsOwner
                        ? (spawnAssignsOwner ? spawningPlayer : null)
                        : currentOwner;

                assertEquals(
                        expectedOwner,
                        resolvedOwner,
                        "captureClearsOwner=" + captureClearsOwner
                                + ", spawnAssignsOwner=" + spawnAssignsOwner
                );
            }
        }
    }

    private static ItemFeatureConfig buildSpawnerConfigForInteraction(ItemFeatureConfig baseConfig,
                                                                      Boolean spawnAssignsOwnerOverride)
            throws Exception {
        SpawnerFeatureHandler handler = new SpawnerFeatureHandler(
                null, null, null
        );
        Method method = SpawnerFeatureHandler.class.getDeclaredMethod(
                "buildSpawnerConfigForInteraction",
                ItemFeatureConfig.class,
                Boolean.class
        );
        method.setAccessible(true);
        return (ItemFeatureConfig) method.invoke(handler, baseConfig, spawnAssignsOwnerOverride);
    }
}
