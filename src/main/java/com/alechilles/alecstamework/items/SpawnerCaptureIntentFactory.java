package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.persistence.SpawnerCaptureIntent;
import com.alechilles.alecstamework.items.persistence.SpawnerPublishedEffect;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkEvidenceSource;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentFactory;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nullable;

/** Translates one accepted live roll into the exact canonical capture-author intent. */
final class SpawnerCaptureIntentFactory {
    private final SpawnerCaptureMetadataService captureMetadata;
    private final SpawnerNpcProgressionMetadataService progression;
    private final SpawnerItemStackMetadataService itemMetadata;
    private final SpawnerItemDisplayMetadataService displayMetadata;
    private final SpawnerNpcStateService npcState;
    private final SpawnerNpcIdentityService npcIdentity;
    private final SpawnerTameAndLinkIntentFactory tameAndLinkIntents;

    SpawnerCaptureIntentFactory(
            SpawnerCaptureMetadataService captureMetadata,
            SpawnerNpcProgressionMetadataService progression,
            SpawnerItemStackMetadataService itemMetadata,
            SpawnerItemDisplayMetadataService displayMetadata,
            SpawnerNpcStateService npcState,
            SpawnerNpcIdentityService npcIdentity
    ) {
        this(
                captureMetadata,
                progression,
                itemMetadata,
                displayMetadata,
                npcState,
                npcIdentity,
                SpawnerTameAndLinkEvidenceSource.unavailable()
        );
    }

    SpawnerCaptureIntentFactory(
            SpawnerCaptureMetadataService captureMetadata,
            SpawnerNpcProgressionMetadataService progression,
            SpawnerItemStackMetadataService itemMetadata,
            SpawnerItemDisplayMetadataService displayMetadata,
            SpawnerNpcStateService npcState,
            SpawnerNpcIdentityService npcIdentity,
            SpawnerTameAndLinkEvidenceSource tameAndLinkEvidence
    ) {
        this.captureMetadata = captureMetadata;
        this.progression = progression;
        this.itemMetadata = itemMetadata;
        this.displayMetadata = displayMetadata;
        this.npcState = npcState;
        this.npcIdentity = npcIdentity;
        this.tameAndLinkIntents = new SpawnerTameAndLinkIntentFactory(
                tameAndLinkEvidence
        );
    }

    @Nullable
    SpawnerCaptureIntent create(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source,
            ItemFeatureConfig config,
            CaptureAttemptHandle attempt,
            SpawnerCaptureRollService.Resolution roll,
            @Nullable String particleSystemOverride
    ) {
        World world = player == null ? null : player.getWorld();
        Store<EntityStore> store = world == null
                || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        if (player == null || targetRef == null || source == null
                || config == null || attempt == null || roll == null
                || roll.terminal() == null
                || world == null || store == null) {
            return null;
        }
        UUID existingOwner = npcState.resolveOwnerFromComponent(
                targetRef, world
        );
        if (!roll.terminal().successful()) {
            return intent(
                    player, targetRef, store, source, attempt, roll,
                    existingOwner, null, null, null,
                    publishedEffect(
                            targetRef,
                            store,
                            config.getCaptureMechanics()
                                    .failureParticleSystem(),
                            config.getCaptureMechanics()
                                    .failureSoundEvent()
                    )
            );
        }
        if (roll.terminal().successDisposition()
                == CaptureSuccessDisposition.TAME_AND_COMMAND_LINK) {
            return tameAndLinkIntent(
                    player,
                    targetRef,
                    store,
                    source,
                    config,
                    attempt,
                    roll,
                    existingOwner,
                    particleSystemOverride
            );
        }
        if (roll.terminal().successDisposition()
                == CaptureSuccessDisposition.CAPTURED_ITEM) {
            return capturedItemIntent(
                    player, targetRef, store, source, config, attempt, roll,
                    existingOwner, particleSystemOverride
            );
        }
        return null;
    }

    /** Returns the bounded tame/link evidence diagnostic for the latest capture attempt. */
    @Nullable
    String lastEvidenceFailureReason() {
        return tameAndLinkIntents.lastEvidenceFailureReason();
    }

    private SpawnerCaptureIntent tameAndLinkIntent(
            Player player,
            Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            ItemStack source,
            ItemFeatureConfig config,
            CaptureAttemptHandle attempt,
            SpawnerCaptureRollService.Resolution roll,
            @Nullable UUID existingOwner,
            @Nullable String particleSystemOverride
    ) {
        String particleSystem =
                particleSystemOverride == null
                        || particleSystemOverride.isBlank()
                        ? config.getCaptureParticleSystem()
                        : particleSystemOverride;
        return tameAndLinkIntents.create(
                new SpawnerTameAndLinkIntentFactory.Input(
                        attempt.attemptId().toString(),
                        player.getUuid(),
                        OwnerNameUtil.resolve(player),
                        player.getWorld().getName(),
                        attempt.hotbarSlot(),
                        source,
                        targetRef,
                        store,
                        new ProfileId(roll.targetUuid()),
                        new NpcAlias(roll.targetUuid()),
                        existingOwner == null
                                ? null
                                : new OwnerId(existingOwner),
                        roll.roleId(),
                        roll.terminal(),
                        publishedEffect(
                                targetRef,
                                store,
                                particleSystem,
                                config.getCaptureSoundEvent()
                        )
                )
        );
    }

    private SpawnerCaptureIntent capturedItemIntent(
            Player player,
            Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            ItemStack source,
            ItemFeatureConfig config,
            CaptureAttemptHandle attempt,
            SpawnerCaptureRollService.Resolution roll,
            @Nullable UUID existingOwner,
            @Nullable String particleSystemOverride
    ) {
        SpawnerCaptureMetadataService.CaptureInfo info =
                captureMetadata.buildCaptureInfo(
                        player,
                        targetRef,
                        npcIdentity::resolveDisplayName
                );
        UUID resultingOwner = resolveCapturedOwnerMetadata(
                existingOwner, config.isCaptureClearsOwner()
        );
        if (resultingOwner == null && !config.isCaptureClearsOwner()
                && config.isCaptureTamesTarget()) {
            resultingOwner = player.getUuid();
        }
        String resultingOwnerName = resultingOwner == null
                ? null
                : resultingOwner.equals(existingOwner)
                ? npcState.resolveOwnerNameFromComponent(
                        targetRef, player.getWorld()
                )
                : resultingOwner.equals(player.getUuid())
                ? OwnerNameUtil.resolve(player)
                : null;
        ItemStack artifact = capturedArtifact(
                targetRef,
                store,
                source,
                config,
                roll,
                info,
                existingOwner,
                resultingOwner,
                player.getWorld()
        );
        String particleSystem =
                particleSystemOverride == null
                        || particleSystemOverride.isBlank()
                        ? config.getCaptureParticleSystem()
                        : particleSystemOverride;
        return intent(
                player, targetRef, store, source, attempt, roll,
                existingOwner, resultingOwner, resultingOwnerName,
                artifact,
                publishedEffect(
                        targetRef,
                        store,
                        particleSystem,
                        config.getCaptureSoundEvent()
                )
        );
    }

    private ItemStack capturedArtifact(
            Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            ItemStack source,
            ItemFeatureConfig config,
            SpawnerCaptureRollService.Resolution roll,
            SpawnerCaptureMetadataService.CaptureInfo info,
            @Nullable UUID existingOwner,
            @Nullable UUID resultingOwner,
            World world
    ) {
        String fullItemIcon = captureMetadata.resolveFullItemIcon(
                config,
                info.attachmentsJson(),
                source.getItemId(),
                info.npcNameKey()
        );
        ItemStack artifact = itemMetadata.swapItemId(
                        source, config.getSpawnerFilledItemId()
                )
                .withMetadata(
                        TameworkMetadataKeys.CAPTURED,
                        Codec.BOOLEAN,
                        true
                )
                .withMetadata(
                        TameworkMetadataKeys.TARGET_UUID,
                        Codec.UUID_STRING,
                        roll.targetUuid()
                )
                .withMetadata(
                        TameworkMetadataKeys.CAPTURE_ROLE_ID,
                        Codec.STRING,
                        roll.roleId()
                )
                .withMetadata(
                        TameworkMetadataKeys.CAPTURE_OWNER_CLEARED,
                        Codec.BOOLEAN,
                        config.isCaptureClearsOwner()
                );
        if (info.attachmentsJson() != null) {
            artifact = artifact.withMetadata(
                    TameworkMetadataKeys.ATTACHMENTS,
                    Codec.STRING,
                    info.attachmentsJson()
            );
        }
        if (npcState.resolveTamedState(targetRef, world)
                || config.isCaptureTamesTarget()) {
            artifact = artifact.withMetadata(
                    TameworkMetadataKeys.TAMED,
                    Codec.BOOLEAN,
                    true
            );
        }
        artifact = itemMetadata.applyOwnerMetadata(
                artifact, resultingOwner
        );
        artifact = existingOwner == null
                ? itemMetadata.clearMetadataKey(
                        artifact,
                        TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID
                )
                : artifact.withMetadata(
                        TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID,
                        Codec.UUID_STRING,
                        existingOwner
                );
        artifact = captureMetadata.applyCaptureNameKeyMetadata(
                artifact, info
        );
        artifact = captureMetadata.applyCapturedMetadata(
                artifact, info, fullItemIcon
        );
        artifact = captureMetadata.applyCapturedModelMetadata(
                artifact, info
        );
        artifact = captureMetadata.applyCapturedNameMetadata(
                artifact, info
        );
        artifact = captureMetadata.applyTooltipDisplayNameMetadata(
                artifact, info
        );
        artifact = progression.applyNpcProgressionMetadata(
                artifact, targetRef, store
        );
        artifact = displayMetadata.applyCapturedDisplayMetadata(
                artifact, config
        );
        return artifact;
    }

    private SpawnerCaptureIntent intent(
            Player player,
            Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            ItemStack source,
            CaptureAttemptHandle attempt,
            SpawnerCaptureRollService.Resolution roll,
            @Nullable UUID existingOwner,
            @Nullable UUID resultingOwner,
            @Nullable String resultingOwnerName,
            @Nullable ItemStack artifact,
            @Nullable SpawnerPublishedEffect publishedEffect
    ) {
        return new SpawnerCaptureIntent(
                attempt.attemptId().toString(),
                player.getUuid(),
                player.getWorld().getName(),
                attempt.hotbarSlot(),
                source,
                artifact,
                targetRef,
                store,
                new ProfileId(roll.targetUuid()),
                new NpcAlias(roll.targetUuid()),
                existingOwner == null ? null : new OwnerId(existingOwner),
                resultingOwner == null ? null : new OwnerId(resultingOwner),
                resultingOwnerName,
                roll.roleId(),
                roll.terminal(),
                publishedEffect
        );
    }

    @Nullable
    private SpawnerPublishedEffect publishedEffect(
            Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            @Nullable String particleSystem,
            @Nullable String soundEvent
    ) {
        TransformComponent transform = store.getComponent(
                targetRef, TransformComponent.getComponentType()
        );
        if (transform == null || transform.getPosition() == null) {
            return null;
        }
        org.joml.Vector3d position = transform.getPosition();
        return new SpawnerPublishedEffect(
                position.x,
                position.y,
                position.z,
                particleSystem,
                soundEvent
        );
    }

    @Nullable
    private UUID resolveCapturedOwnerMetadata(
            @Nullable UUID existingOwner,
            boolean captureClearsOwner
    ) {
        return captureClearsOwner ? null : existingOwner;
    }
}
