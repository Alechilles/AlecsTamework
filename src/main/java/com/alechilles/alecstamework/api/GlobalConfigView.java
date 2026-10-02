package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record GlobalConfigView(@Nullable String id,
                               @Nullable String parentId,
                               boolean enabled,
                               int priority,
                               @Nonnull OwnershipProtectionView ownershipProtection,
                               @Nonnull OwnershipRequirementsView ownershipRequirements,
                               @Nonnull InteractionDefaultsView interactionDefaults,
                               @Nonnull CommandView command,
                               @Nonnull AssetSetsView assetSets,
                               @Nonnull PopulationView population,
                               @Nonnull SimpleClaimsView simpleClaims) {
    public record OwnershipProtectionView(boolean blockOwnerDamage,
                                          boolean blockAllPlayerDamageIfOwned,
                                          boolean invulnerableIfOwned) {
    }

    public record OwnershipRequirementsView(boolean captureRequiresOwner,
                                            boolean spawnRequiresOwner,
                                            boolean interactionRequiresOwner,
                                            boolean linkingRequiresOwner) {
    }

    public record InteractionDefaultsView(@Nonnull String interactionConfigParam,
                                          @Nonnull String lovedItemsParam,
                                          @Nonnull String isHarvestableParam,
                                          @Nonnull String isMountableParam,
                                          @Nonnull String harvestContextParam,
                                          @Nonnull String harvestAlarmName,
                                          @Nonnull String interactionCooldownAlarmPrefix) {
    }

    public record CommandView(double returnHomeTeleportDistance,
                              double returnHomePathDistanceBeforeTeleport,
                              int returnHomeTeleportDelayMs,
                              double recallSafeSpawnDistance,
                              double recallForceRelocateDistance,
                              int relocationRetryIntervalMs,
                              int relocationMaxWaitMs,
                              int relocationMaxRetryAttempts,
                              boolean deadRespawnEnabled,
                              int deadRespawnCooldownMs,
                              int deadRespawnFollowRetryDelayMs,
                              double deadRespawnDistanceClose,
                              double deadRespawnDistanceNear,
                              double deadRespawnDistanceMid,
                              double deadRespawnDistanceFar,
                              double placementMinRelativeY,
                              double placementMaxRelativeY,
                              boolean linkedPanelRequireUnlinkConfirm) {
    }

    public record AssetSetsView(boolean tranquilizerShortbow,
                                boolean tranquilizerArrow,
                                boolean tranquilizerPotion,
                                boolean feedTrough,
                                boolean herbivoreFeed,
                                boolean carnivoreFeed) {
    }

    /**
     * The per-player limits; 0 means no limit, and both use {@code perPlayerLimitScope}.
     *
     * @param limitPerPlayerOwnedTotal    every companion the player owns: out in the world, stored,
     *                                    in items, in coops, dead and lost
     * @param perPlayerLimitScope         {@code PerWorld} or {@code Global}
     * @param limitPerPlayerDeployedTotal the player's companions out in the world, loaded or not
     */
    public record PopulationView(int limitPerPlayerOwnedTotal,
                                 @Nonnull String perPlayerLimitScope,
                                 int limitPerPlayerDeployedTotal) {
        /** Compatibility constructor for callers compiled before the deployed limit: no deployed limit. */
        public PopulationView(int limitPerPlayerOwnedTotal, @Nonnull String perPlayerLimitScope) {
            this(limitPerPlayerOwnedTotal, perPlayerLimitScope, 0);
        }
    }

    public record SimpleClaimsView(boolean enabled,
                                   @Nonnull BreedingView breeding,
                                   @Nonnull DamageView damage) {
        public record BreedingView(int limitPerClaimChunk,
                                   int limitPerClaimTotal,
                                   boolean requiresClaim) {
        }

        public record DamageView(boolean protectTamedFromNonMembers,
                                 @Nonnull String allowDamagePermissionKey) {
        }
    }
}

