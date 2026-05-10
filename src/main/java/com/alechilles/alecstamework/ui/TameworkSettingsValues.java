package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.alechilles.alecstamework.settings.ResolvedTameworkSettings;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import java.nio.file.Files;
import java.nio.file.Path;
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
        seedSettingsFromConfigsIfMissing();
        return fromResolvedSettings(TameworkSettingsStore.loadRuntimeGlobalSettings());
    }

    @Nonnull
    private static TameworkSettingsValues fromResolvedSettings(@Nonnull ResolvedTameworkSettings settings) {
        return new TameworkSettingsValues(
                settings.populationLimitPerPlayerOwnedTotal(),
                TwGlobalConfig.PerPlayerLimitScope.fromConfigValue(settings.populationPerPlayerLimitScope()),
                settings.simpleClaimsEnabled(),
                settings.simpleClaimsLimitPerClaimChunk(),
                settings.simpleClaimsLimitPerClaimTotal(),
                settings.simpleClaimsBreedingRequiresClaim(),
                settings.simpleClaimsProtectTamedFromNonMembers(),
                settings.blockOwnerDamage(),
                settings.blockAllPlayerDamageIfOwned(),
                settings.invulnerableIfOwned(),
                settings.captureClearsOwner(),
                settings.spawnSetsOwner(),
                settings.captureRequiresOwner(),
                settings.spawnRequiresOwner(),
                settings.interactionRequiresOwner(),
                settings.linkingRequiresOwner(),
                settings.needsEnabled(),
                settings.needsDamageEnabled(),
                TwNeedsConfig.TickPolicyMode.fromConfigValue(settings.needsTickPolicyMode()),
                settings.needsOwnerOfflineGraceHours(),
                settings.needsOwnerOfflineDecayMultiplier(),
                TwNeedsConfig.DamageModel.fromConfigValue(settings.needsDamageModel()),
                TwNeedsConfig.DualNeedRule.fromConfigValue(settings.needsDamageDualNeedRule()),
                settings.needsStarvationDamagePerMinute(),
                settings.needsDehydrationDamagePerMinute(),
                settings.needsDamageLethal(),
                settings.happinessEnabled(),
                settings.passiveBreedingEnabled(),
                settings.breedingRequiresHappiness(),
                settings.breedingGenderEnabled(),
                settings.traitsEnabled(),
                settings.reviveSystemEnabled(),
                settings.recallTeleportingEnabled(),
                settings.telemetryEnabled(),
                settings.telemetryBreadcrumbsEnabled()
        );
    }

    private static void seedSettingsFromConfigsIfMissing() {
        Tamework plugin = runtimePlugin();
        if (plugin == null) {
            return;
        }
        Path globalSettingsFile = TameworkSettingsStore.resolveGlobalSettingsFile(plugin);
        if (Files.isRegularFile(globalSettingsFile)) {
            return;
        }
        TameworkSettingsValues values = TameworkRuntimeSettings.withoutRuntimeSettings(TameworkSettingsValues::fromConfigDefaults);
        TameworkSettingsStore.saveGlobalSettingsIfMissing(
                globalSettingsFile,
                values.toGlobalSettingsSnapshot(),
                plugin.getLogger()
        );
    }

    @Nullable
    private static Tamework runtimePlugin() {
        try {
            return Tamework.getInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nonnull
    private static TameworkSettingsValues fromConfigDefaults() {
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

        boolean happinessEnabled = happinessConfig != null && happinessConfig.isEnabled();
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
                true,
                true,
                global.isOwnershipCaptureRequiresOwner(),
                global.isOwnershipSpawnRequiresOwner(),
                global.isOwnershipInteractionRequiresOwner(),
                global.isOwnershipLinkingRequiresOwner(),
                needsConfig != null && needsConfig.isEnabled(),
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
                breedingConfig != null && breedingConfig.resolvePassiveBreeding(null).isEnabled(),
                breedingConfig != null && breedingConfig.isHappinessRequired(null) && happinessEnabled,
                true,
                traitConfig != null && traitConfig.isEnabled(),
                global.isCommandDeadRespawnEnabled(),
                true,
                true,
                true
        );
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
