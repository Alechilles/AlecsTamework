package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable form-state snapshot for the curated `/tw settings` page.
 */
public record TameworkSettingsValues(int populationLimitPerPlayerOwnedTotal,
                                     @Nonnull TwGlobalConfig.PerPlayerLimitScope populationPerPlayerLimitScope,
                                     boolean simpleClaimsEnabled,
                                     int simpleClaimsLimitPerClaimChunk,
                                     int simpleClaimsLimitPerClaimTotal,
                                     boolean simpleClaimsBreedingRequiresClaim,
                                     boolean simpleClaimsProtectTamedFromNonMembers,
                                     boolean blockOwnerDamage,
                                     boolean blockAllPlayerDamageIfOwned,
                                     boolean invulnerableIfOwned,
                                     boolean captureClearsOwner,
                                     boolean spawnSetsOwner,
                                     boolean captureRequiresOwner,
                                     boolean spawnRequiresOwner,
                                     boolean interactionRequiresOwner,
                                     boolean linkingRequiresOwner,
                                     boolean needsEnabled,
                                     boolean needsDamageEnabled,
                                     @Nonnull TwNeedsConfig.TickPolicyMode needsTickPolicyMode,
                                     double needsOwnerOfflineGraceHours,
                                     double needsOwnerOfflineDecayMultiplier,
                                     @Nonnull TwNeedsConfig.DamageModel needsDamageModel,
                                     @Nonnull TwNeedsConfig.DualNeedRule needsDamageDualNeedRule,
                                     double needsStarvationDamagePerMinute,
                                     double needsDehydrationDamagePerMinute,
                                     boolean needsDamageLethal,
                                     boolean happinessEnabled,
                                     boolean passiveBreedingEnabled,
                                     boolean breedingRequiresHappiness,
                                     boolean breedingGenderEnabled,
                                     boolean traitsEnabled,
                                     boolean reviveSystemEnabled,
                                     boolean recallTeleportingEnabled,
                                     boolean telemetryEnabled,
                                     boolean telemetryBreadcrumbsEnabled) {

    @Nonnull
    TameworkSettingsStore.GlobalSettingsSnapshot toGlobalSettingsSnapshot() {
        return new TameworkSettingsStore.GlobalSettingsSnapshot(
                populationLimitPerPlayerOwnedTotal,
                populationPerPlayerLimitScope.configValue(),
                simpleClaimsEnabled,
                simpleClaimsLimitPerClaimChunk,
                simpleClaimsLimitPerClaimTotal,
                simpleClaimsBreedingRequiresClaim,
                simpleClaimsProtectTamedFromNonMembers,
                blockOwnerDamage,
                blockAllPlayerDamageIfOwned,
                invulnerableIfOwned,
                captureClearsOwner,
                spawnSetsOwner,
                captureRequiresOwner,
                spawnRequiresOwner,
                interactionRequiresOwner,
                linkingRequiresOwner,
                needsEnabled,
                needsTickPolicyMode.toConfigValue(),
                needsOwnerOfflineGraceHours,
                needsOwnerOfflineDecayMultiplier,
                needsDamageEnabled,
                needsDamageModel.toConfigValue(),
                needsDamageDualNeedRule.toConfigValue(),
                needsStarvationDamagePerMinute,
                needsDehydrationDamagePerMinute,
                needsDamageLethal,
                happinessEnabled,
                passiveBreedingEnabled,
                breedingRequiresHappiness,
                breedingGenderEnabled,
                traitsEnabled,
                reviveSystemEnabled,
                recallTeleportingEnabled,
                telemetryEnabled,
                telemetryBreadcrumbsEnabled
        );
    }

    @Nonnull
    TameworkSettingsValues withExperienceSettings(boolean needsEnabled,
                                                  boolean needsDamageEnabled,
                                                  boolean needsDamageLethal,
                                                  boolean happinessEnabled,
                                                  boolean passiveBreedingEnabled,
                                                  boolean breedingRequiresHappiness,
                                                  boolean traitsEnabled) {
        return new TameworkSettingsValues(
                populationLimitPerPlayerOwnedTotal,
                populationPerPlayerLimitScope,
                simpleClaimsEnabled,
                simpleClaimsLimitPerClaimChunk,
                simpleClaimsLimitPerClaimTotal,
                simpleClaimsBreedingRequiresClaim,
                simpleClaimsProtectTamedFromNonMembers,
                blockOwnerDamage,
                blockAllPlayerDamageIfOwned,
                invulnerableIfOwned,
                captureClearsOwner,
                spawnSetsOwner,
                captureRequiresOwner,
                spawnRequiresOwner,
                interactionRequiresOwner,
                linkingRequiresOwner,
                needsEnabled,
                needsDamageEnabled,
                needsTickPolicyMode,
                needsOwnerOfflineGraceHours,
                needsOwnerOfflineDecayMultiplier,
                needsDamageModel,
                needsDamageDualNeedRule,
                needsStarvationDamagePerMinute,
                needsDehydrationDamagePerMinute,
                needsDamageLethal,
                happinessEnabled,
                passiveBreedingEnabled,
                breedingRequiresHappiness,
                breedingGenderEnabled,
                traitsEnabled,
                reviveSystemEnabled,
                recallTeleportingEnabled,
                telemetryEnabled,
                telemetryBreadcrumbsEnabled
        );
    }

    @Nonnull
    static TameworkSettingsValues fromRuntime() {
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        if (global == null) {
            global = TwGlobalConfig.defaultConfig();
        }
        TwNeedsConfig needsConfig = resolvePreferredNeedsConfig();
        TwNeedsConfig.TickPolicySettings tickPolicy = needsConfig != null
                ? needsConfig.getTickPolicy()
                : new TwNeedsConfig.TickPolicySettings();
        TwNeedsConfig.DamageSettings needsDamage = needsConfig != null
                ? needsConfig.getDamage()
                : new TwNeedsConfig.DamageSettings();
        TwHappinessConfig happinessConfig = resolvePreferredHappinessConfig();
        TwBreedingConfig breedingConfig = resolvePreferredBreedingConfig();
        TwTraitConfig traitConfig = resolvePreferredTraitConfig();
        TameworkSettingsStore.GlobalOverrides overrides = TameworkSettingsStore.loadRuntimeGlobalOverrides();

        boolean needsEnabled = overrides != null && overrides.needsEnabled() != null
                ? overrides.needsEnabled()
                : needsConfig != null && needsConfig.isEnabled();
        boolean happinessEnabled = overrides != null && overrides.happinessEnabled() != null
                ? overrides.happinessEnabled()
                : happinessConfig != null && happinessConfig.isEnabled();
        boolean passiveBreedingEnabled = overrides != null && overrides.passiveBreedingEnabled() != null
                ? overrides.passiveBreedingEnabled()
                : breedingConfig != null && breedingConfig.resolvePassiveBreeding(null).isEnabled();
        boolean breedingRequiresHappiness = overrides != null && overrides.breedingRequiresHappiness() != null
                ? overrides.breedingRequiresHappiness() && needsHappinessSystemEnabled(overrides, happinessConfig)
                : breedingConfig != null && breedingConfig.isHappinessRequired(null);
        boolean breedingGenderEnabled = overrides == null
                || overrides.breedingGenderEnabled() == null
                || overrides.breedingGenderEnabled();
        boolean traitsEnabled = overrides != null && overrides.traitsEnabled() != null
                ? overrides.traitsEnabled()
                : traitConfig != null && traitConfig.isEnabled();
        boolean recallTeleportingEnabled = overrides == null
                || overrides.recallTeleportingEnabled() == null
                || overrides.recallTeleportingEnabled();
        boolean telemetryEnabled = overrides == null
                || overrides.telemetryEnabled() == null
                || overrides.telemetryEnabled();
        boolean telemetryBreadcrumbsEnabled = overrides == null
                || overrides.telemetryBreadcrumbsEnabled() == null
                || overrides.telemetryBreadcrumbsEnabled();

        return new TameworkSettingsValues(
                global.getPopulationLimitPerPlayerOwnedTotal(),
                global.getPopulationPerPlayerLimitScope(),
                global.isSimpleClaimsEnabled(),
                global.getSimpleClaimsBreedingLimitPerClaimChunk(),
                global.getSimpleClaimsBreedingLimitPerClaimTotal(),
                global.isSimpleClaimsBreedingRequiresClaim(),
                global.isSimpleClaimsDamageProtectTamedFromNonMembers(),
                global.isBlockOwnerDamage(),
                global.isBlockAllPlayerDamageIfOwned(),
                global.isInvulnerableIfOwned(),
                overrides != null && overrides.captureClearsOwner() != null
                        ? overrides.captureClearsOwner()
                        : true,
                overrides != null && overrides.spawnSetsOwner() != null
                        ? overrides.spawnSetsOwner()
                        : true,
                global.isOwnershipCaptureRequiresOwner(),
                global.isOwnershipSpawnRequiresOwner(),
                global.isOwnershipInteractionRequiresOwner(),
                global.isOwnershipLinkingRequiresOwner(),
                needsEnabled,
                needsDamage.isEnabled(),
                tickPolicy.getMode(),
                tickPolicy.getOwnerOfflineGraceHours(),
                tickPolicy.getOwnerOfflineDecayMultiplier(),
                needsDamage.getModel(),
                needsDamage.getDualNeedRule(),
                needsDamage.getStarvationDamagePerMinute(),
                needsDamage.getDehydrationDamagePerMinute(),
                needsDamage.isLethal(),
                happinessEnabled,
                passiveBreedingEnabled,
                breedingRequiresHappiness,
                breedingGenderEnabled,
                traitsEnabled,
                global.isCommandDeadRespawnEnabled(),
                recallTeleportingEnabled,
                telemetryEnabled,
                telemetryBreadcrumbsEnabled
        );
    }

    private static boolean needsHappinessSystemEnabled(@Nullable TameworkSettingsStore.GlobalOverrides overrides,
                                                       @Nullable TwHappinessConfig happinessConfig) {
        if (overrides != null && overrides.happinessEnabled() != null) {
            return overrides.happinessEnabled();
        }
        return happinessConfig != null && happinessConfig.isEnabled();
    }

    @Nullable
    private static TwNeedsConfig resolvePreferredNeedsConfig() {
        var assetMap = TwNeedsConfig.getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        TwNeedsConfig bestRoleless = null;
        TwNeedsConfig bestAny = null;
        for (TwNeedsConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isConfiguredEnabled()) {
                continue;
            }
            if (bestAny == null || candidate.getPriority() > bestAny.getPriority()) {
                bestAny = candidate;
            }
            String[] roleIds = candidate.getRoleIds();
            if (roleIds != null && roleIds.length > 0) {
                continue;
            }
            if (bestRoleless == null || candidate.getPriority() > bestRoleless.getPriority()) {
                bestRoleless = candidate;
            }
        }
        return bestRoleless != null ? bestRoleless : bestAny;
    }

    @Nullable
    private static TwHappinessConfig resolvePreferredHappinessConfig() {
        var assetMap = TwHappinessConfig.getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        TwHappinessConfig bestRoleless = null;
        TwHappinessConfig bestAny = null;
        for (TwHappinessConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isConfiguredEnabled()) {
                continue;
            }
            if (bestAny == null || candidate.getPriority() > bestAny.getPriority()) {
                bestAny = candidate;
            }
            String[] roleIds = candidate.getRoleIds();
            if (roleIds != null && roleIds.length > 0) {
                continue;
            }
            if (bestRoleless == null || candidate.getPriority() > bestRoleless.getPriority()) {
                bestRoleless = candidate;
            }
        }
        return bestRoleless != null ? bestRoleless : bestAny;
    }

    @Nullable
    private static TwBreedingConfig resolvePreferredBreedingConfig() {
        var assetMap = TwBreedingConfig.getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        TwBreedingConfig bestRoleless = null;
        TwBreedingConfig bestAny = null;
        for (TwBreedingConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            if (bestAny == null || candidate.getPriority() > bestAny.getPriority()) {
                bestAny = candidate;
            }
            String[] roleIds = candidate.getRoleIds();
            if (roleIds != null && roleIds.length > 0) {
                continue;
            }
            if (bestRoleless == null || candidate.getPriority() > bestRoleless.getPriority()) {
                bestRoleless = candidate;
            }
        }
        return bestRoleless != null ? bestRoleless : bestAny;
    }

    @Nullable
    private static TwTraitConfig resolvePreferredTraitConfig() {
        var assetMap = TwTraitConfig.getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        TwTraitConfig bestRoleless = null;
        TwTraitConfig bestAny = null;
        for (TwTraitConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isConfiguredEnabled()) {
                continue;
            }
            if (bestAny == null || candidate.getPriority() > bestAny.getPriority()) {
                bestAny = candidate;
            }
            String[] roleIds = candidate.getRoleIds();
            if (roleIds != null && roleIds.length > 0) {
                continue;
            }
            if (bestRoleless == null || candidate.getPriority() > bestRoleless.getPriority()) {
                bestRoleless = candidate;
            }
        }
        return bestRoleless != null ? bestRoleless : bestAny;
    }
}
