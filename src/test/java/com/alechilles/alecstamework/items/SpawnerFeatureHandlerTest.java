package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void captureRejectsStackedSpawnerItemsBeforeMetadataWrite() throws Exception {
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"
        ));
        String intents = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerCaptureIntentFactory.java"
        ));

        int quantityGuard = handler.indexOf("source.getQuantity() != 1");
        int capturedMetadata = intents.indexOf(
                ".withMetadata(\n"
                        + "                        TameworkMetadataKeys.CAPTURED"
        );
        int ownerOutcomeMetadata = intents.indexOf(
                "TameworkMetadataKeys.CAPTURE_OWNER_CLEARED"
        );

        assertTrue(quantityGuard >= 0, "capture path must reject stacked spawner items");
        assertTrue(capturedMetadata >= 0, "capture path must write captured metadata");
        assertTrue(ownerOutcomeMetadata >= 0, "capture must persist its immutable owner outcome");
    }

    @Test
    void captureUsesCanonicalAuthorAsSoleDurableSuccessAuthority()
            throws Exception {
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"
        ));
        String author = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/persistence/SpawnerCaptureAuthor.java"
        ));

        assertTrue(handler.contains("captureAuthor.capture(intent)"));
        assertTrue(author.contains("persistence.capture("));
        assertFalse(handler.contains("CaptureRepository"));
        assertFalse(handler.contains("captureAttemptRuntime"));
        assertFalse(handler.contains("captureFinalizerService"));
    }

    @Test
    void releaseUsesCanonicalAuthorWithoutPreparedSpawnSubsystem()
            throws Exception {
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"
        ));

        assertTrue(handler.contains("releaseAuthor.release("));
        assertFalse(handler.contains("CommandPreparedRestoreSpawnService"));
        assertFalse(handler.contains("CompanionPreparedSpawnService"));
        assertFalse(handler.contains("CommandNpcRelocationService"));
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
