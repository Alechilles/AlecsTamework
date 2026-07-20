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
    void spawnPreparesPopulationBeforePhysicalSpawnAndFinalizesSourceAfterCommit() throws Exception {
        String release = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerPreparedSpawnService.java"
        ));
        String continuation = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CompanionSpawnCommitContinuation.java"
        ));

        assertTrue(release.indexOf("admission.prepareAsync(request)")
                < release.indexOf("executor.spawnAndCommit("));
        assertTrue(release.contains("source.prepare(finalizedItem)"));
        assertTrue(continuation.indexOf("boolean liveApplied = runLive")
                < continuation.indexOf("runSource(sourceFinalization, live)"));
        assertTrue(continuation.contains("finishSourceDurability("));
    }

    @Test
    void spawnAndPreAddFailureCancelWithoutTouchingTheSourceItem() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CompanionPreparedSpawnService.java"
        ));

        int spawn = source.indexOf("SpawnAttempt attempt = spawn(");
        int ambiguityGuard = source.indexOf("if (attempt.outcomeAmbiguous())", spawn);
        int cancel = source.indexOf("admissionService.cancelAsync", spawn);
        int commit = source.indexOf("admissionService.commitLiveAsync", spawn);

        assertTrue(spawn >= 0 && ambiguityGuard > spawn && cancel > ambiguityGuard);
        assertTrue(cancel < commit, "failed spawn/pre-add must cancel rather than commit live capacity");
        assertFalse(source.contains("SpawnerSourceItemTransaction"));
    }

    @Test
    void captureRejectsStackedSpawnerItemsBeforeMetadataWrite() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"));

        int quantityGuard = source.indexOf("itemStack.getQuantity() != 1");
        int capturedMetadata = source.indexOf(".withMetadata(TameworkMetadataKeys.CAPTURED");
        int canonicalProfileMetadata = source.indexOf("TameworkMetadataKeys.COMPANION_PROFILE_ID");
        int ownerOutcomeMetadata = source.indexOf("TameworkMetadataKeys.CAPTURE_OWNER_CLEARED");

        assertTrue(quantityGuard >= 0, "capture path must reject stacked spawner items");
        assertTrue(capturedMetadata >= 0, "capture path must write captured metadata");
        assertTrue(canonicalProfileMetadata >= 0, "capture path must persist canonical profile identity");
        assertTrue(ownerOutcomeMetadata >= 0, "capture must persist its immutable owner outcome");
        assertTrue(quantityGuard < capturedMetadata, "stack guard must run before captured metadata is stamped");
    }

    @Test
    void captureFreezesLinkedPanelSnapshotBeforeOwnershipMutationAndPublishesAfterApply() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"
        ));

        int prepare = source.indexOf("prepareCapturedLinkedNpcSnapshot(");
        int finalizeCapture = source.indexOf("captureFinalizerService.finalizeCapture(", prepare);
        int applied = source.indexOf("public void onApplied(", finalizeCapture);
        int publish = source.indexOf("publishPreparedCapturedLinkedNpcSnapshot(", applied);

        assertTrue(prepare >= 0, "capture must freeze command links while the live component still exists");
        assertTrue(prepare < finalizeCapture, "link snapshot must precede owner mutation scheduling");
        assertTrue(applied > finalizeCapture, "capture publication must remain an applied-mutation continuation");
        assertTrue(publish > applied, "prepared links must publish only after capture applies");
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
