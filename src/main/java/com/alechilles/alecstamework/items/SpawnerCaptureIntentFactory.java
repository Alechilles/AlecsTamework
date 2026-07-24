package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.persistence.SpawnerCaptureIntent;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
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

    SpawnerCaptureIntentFactory(
            SpawnerCaptureMetadataService captureMetadata,
            SpawnerNpcProgressionMetadataService progression,
            SpawnerItemStackMetadataService itemMetadata,
            SpawnerItemDisplayMetadataService displayMetadata,
            SpawnerNpcStateService npcState,
            SpawnerNpcIdentityService npcIdentity
    ) {
        this.captureMetadata = captureMetadata;
        this.progression = progression;
        this.itemMetadata = itemMetadata;
        this.displayMetadata = displayMetadata;
        this.npcState = npcState;
        this.npcIdentity = npcIdentity;
    }

    @Nullable
    SpawnerCaptureIntent create(
            Player player,
            Ref<EntityStore> targetRef,
            ItemStack source,
            ItemFeatureConfig config,
            CaptureAttemptHandle attempt,
            SpawnerCaptureRollService.Resolution roll
    ) {
        World world = player == null ? null : player.getWorld();
        Store<EntityStore> store = world == null
                || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        if (player == null || targetRef == null || source == null
                || config == null || attempt == null || roll == null
                || world == null || store == null) {
            return null;
        }

        SpawnerCaptureMetadataService.CaptureInfo info =
                captureMetadata.buildCaptureInfo(
                        player,
                        targetRef,
                        npcIdentity::resolveDisplayName
                );
        String fullItemIcon = captureMetadata.resolveFullItemIcon(
                config,
                info.attachmentsJson(),
                source.getItemId(),
                info.npcNameKey()
        );
        UUID existingOwner = npcState.resolveOwnerFromComponent(
                targetRef, world
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
                ? npcState.resolveOwnerNameFromComponent(targetRef, world)
                : resultingOwner.equals(player.getUuid())
                ? OwnerNameUtil.resolve(player)
                : null;

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

        ProfileId profileId = new ProfileId(roll.targetUuid());
        return new SpawnerCaptureIntent(
                attempt.attemptId().toString(),
                player.getUuid(),
                world.getName(),
                attempt.hotbarSlot(),
                source,
                artifact,
                targetRef,
                store,
                profileId,
                new NpcAlias(roll.targetUuid()),
                existingOwner == null ? null : new OwnerId(existingOwner),
                resultingOwner == null ? null : new OwnerId(resultingOwner),
                resultingOwnerName,
                roll.roleId()
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
