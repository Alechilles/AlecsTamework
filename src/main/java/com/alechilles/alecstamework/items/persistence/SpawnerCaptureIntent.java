package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptFormula;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolution;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Complete immutable caller context for one live spawner capture.
 *
 * <p>The intent key must remain stable for retries of the same gameplay action.</p>
 */
public record SpawnerCaptureIntent(
        @Nonnull String intentKey,
        @Nonnull UUID actorUuid,
        @Nonnull String worldKey,
        int sourceSlot,
        @Nonnull ItemStack sourceStack,
        @Nonnull ItemStack filledArtifactStack,
        @Nullable Ref<EntityStore> sourceRef,
        @Nullable Store<EntityStore> sourceStore,
        @Nonnull ProfileId profileId,
        @Nonnull NpcAlias sourceAlias,
        @Nullable OwnerId liveOwnerId,
        @Nullable OwnerId resultingOwnerId,
        @Nullable String resultingOwnerName,
        @Nullable String roleId,
        @Nonnull CaptureAttemptResolution resolution,
        @Nullable SpawnerPublishedEffect publishedEffect
) {
    public SpawnerCaptureIntent {
        if (intentKey == null || intentKey.isBlank()
                || actorUuid == null || worldKey == null
                || worldKey.isBlank() || sourceSlot < 0
                || sourceStack == null || profileId == null
                || sourceAlias == null || resolution == null) {
            throw new IllegalArgumentException(
                    "Complete spawner capture intent is required"
            );
        }
        if (resolution.successful()
                && resolution.successDisposition()
                == CaptureSuccessDisposition.CAPTURED_ITEM
                && filledArtifactStack == null) {
            throw new IllegalArgumentException(
                    "Captured-item success requires a filled artifact"
            );
        }
        if (!resolution.successful()
                && (filledArtifactStack != null
                || resultingOwnerId != null)) {
            throw new IllegalArgumentException(
                    "Failed capture cannot contain success mutations"
            );
        }
        intentKey = intentKey.trim();
        worldKey = worldKey.trim();
        roleId = roleId == null || roleId.isBlank()
                ? null
                : roleId.trim();
        resultingOwnerName = resultingOwnerName == null
                || resultingOwnerName.isBlank()
                ? null
                : resultingOwnerName.trim();
    }

    SpawnerCaptureIntent withProfileId(@Nonnull ProfileId canonicalProfileId) {
        return new SpawnerCaptureIntent(
                intentKey,
                actorUuid,
                worldKey,
                sourceSlot,
                sourceStack,
                filledArtifactStack,
                sourceRef,
                sourceStore,
                canonicalProfileId,
                sourceAlias,
                liveOwnerId,
                resultingOwnerId,
                resultingOwnerName,
                roleId,
                resolution,
                publishedEffect
        );
    }

    SpawnerCaptureContext frozenContext() {
        return new SpawnerCaptureContext(
                intentKey,
                actorUuid,
                worldKey,
                sourceSlot,
                profileId,
                sourceAlias,
                liveOwnerId,
                resultingOwnerId,
                roleId,
                publishedEffect
        );
    }

    /** Source-compatible constructor for pre-resolution captured-item callers. */
    public SpawnerCaptureIntent(
            String intentKey,
            UUID actorUuid,
            String worldKey,
            int sourceSlot,
            ItemStack sourceStack,
            ItemStack filledArtifactStack,
            Ref<EntityStore> sourceRef,
            Store<EntityStore> sourceStore,
            ProfileId profileId,
            NpcAlias sourceAlias,
            OwnerId liveOwnerId,
            OwnerId resultingOwnerId,
            String resultingOwnerName,
            String roleId,
            SpawnerPublishedEffect publishedEffect
    ) {
        this(
                intentKey,
                actorUuid,
                worldKey,
                sourceSlot,
                sourceStack,
                filledArtifactStack,
                sourceRef,
                sourceStore,
                profileId,
                sourceAlias,
                liveOwnerId,
                resultingOwnerId,
                resultingOwnerName,
                roleId,
                legacyResolution(intentKey, roleId),
                publishedEffect
        );
    }

    private static CaptureAttemptResolution legacyResolution(
            String intentKey,
            String roleId
    ) {
        UUID attemptId = UUID.nameUUIDFromBytes(
                ("tamework:legacy-capture-intent:" + intentKey)
                        .getBytes(StandardCharsets.UTF_8)
        );
        return new CaptureAttemptResolution(
                attemptId,
                roleId == null || roleId.isBlank()
                        ? "legacy:unknown"
                        : roleId,
                new CaptureAttemptFormula(
                        "tamework:legacy-capture",
                        0L,
                        CaptureChanceMode.GUARANTEED,
                        0,
                        1.0D,
                        0.0D,
                        0.0D,
                        1.0D,
                        null,
                        0L,
                        0,
                        0.0D,
                        1.0D,
                        0.0D,
                        null,
                        Sha256Hash.ofUtf8("[]"),
                        0L
                ),
                CaptureSourceConsumption.SUCCESS_ONLY,
                CaptureSuccessDisposition.CAPTURED_ITEM,
                CaptureAttemptResolution.Outcome.SUCCESS,
                "capture-guaranteed-item",
                1.0D,
                true,
                0.0D,
                null,
                null
        );
    }
}
