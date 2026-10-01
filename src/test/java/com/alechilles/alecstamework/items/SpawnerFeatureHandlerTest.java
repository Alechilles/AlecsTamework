package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerFeatureHandlerTest {

    @Test
    void interactionSpawnAssignsOwnerOverrideWinsOverGlobalDefault() {
        ItemFeatureConfig baseConfig = ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnAssignsOwner(true)
                .build();

        ItemFeatureConfig resolved = SpawnerInteractionConfigResolver.resolve(baseConfig, false);

        assertFalse(resolved.isSpawnAssignsOwner());
    }

    @Test
    void missingInteractionSpawnAssignsOwnerOverrideUsesRuntimeDefault() {
        ItemFeatureConfig baseConfig = ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .spawnAssignsOwner(false)
                .build();

        ItemFeatureConfig resolved = SpawnerInteractionConfigResolver.resolve(baseConfig, null);

        assertTrue(resolved.isSpawnAssignsOwner());
    }

    @Test
    void interactionResolverPreservesWildCaptureContract() {
        ItemFeatureConfig baseConfig = ItemFeatureConfig.builder()
                .spawnerEnabled(true)
                .captureRequireTamed(false)
                .captureTamesTarget(true)
                .captureMaxHealthPercent(20.0d)
                .captureRequiredEffectId("Required")
                .captureChannelAuraEffectId("Aura")
                .captureTamedRoleOverrides(Map.of("Wild", "Tamed"))
                .build();

        ItemFeatureConfig resolved = SpawnerInteractionConfigResolver.resolve(baseConfig, null);

        assertTrue(resolved.isCaptureTamesTarget());
        assertEquals(20.0d, resolved.getCaptureMaxHealthPercent());
        assertEquals("Required", resolved.getCaptureRequiredEffectId());
        assertEquals("Aura", resolved.getCaptureChannelAuraEffectId());
        assertEquals("Tamed", resolved.resolveCaptureTamedRole("Wild"));
    }

    @Test
    void captureOwnerFollowsClearsOwnerThenTheBodyThenTamesTarget() {
        UUID bodyOwner = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        assertNull(SpawnerFeatureHandler.captureOwner(bodyOwner, true, true, player));
        assertNull(SpawnerFeatureHandler.captureOwner(null, true, true, player));
        assertEquals(bodyOwner, SpawnerFeatureHandler.captureOwner(bodyOwner, false, true, player));
        assertEquals(player, SpawnerFeatureHandler.captureOwner(null, false, true, player));
        assertNull(SpawnerFeatureHandler.captureOwner(null, false, false, player));
    }

    @Test
    void releaseOfAnUnownedCompanionAssignsTheReleaserOrStaysUnowned() {
        UUID releaser = UUID.randomUUID();

        assertEquals(new RestoreFlow.Owner(releaser, "Releaser"),
                SpawnerFeatureHandler.releaseOwner(null, true, releaser, "Releaser"));
        assertEquals(new RestoreFlow.Owner(null, null),
                SpawnerFeatureHandler.releaseOwner(null, false, releaser, "Releaser"));
    }

    @Test
    void captureClearAndSpawnAssignmentMatrixProducesTheExactOwnerTransition() {
        UUID currentOwner = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        for (boolean clearsOwner : new boolean[]{false, true}) {
            for (boolean assignsOwner : new boolean[]{false, true}) {
                UUID recordOwner = SpawnerFeatureHandler.captureOwner(currentOwner, clearsOwner, false, player);
                RestoreFlow.Owner requested =
                        SpawnerFeatureHandler.releaseOwner(recordOwner, assignsOwner, player, "Player");
                UUID releasedOwner = requested == null ? recordOwner : requested.uuid();
                UUID expected = clearsOwner ? (assignsOwner ? player : null) : currentOwner;

                assertEquals(expected, releasedOwner,
                        "clearsOwner=" + clearsOwner + ", assignsOwner=" + assignsOwner);
            }
        }
    }
}
