package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import com.alechilles.alecstamework.settings.ResolvedTameworkSettings;
import javax.annotation.Nonnull;

/**
 * Immutable form-state snapshot for the curated `/tw settings` page.
 */
public record TameworkSettingsValues(int populationLimitPerPlayerOwnedTotal,
                                     @Nonnull TwGlobalConfig.PerPlayerLimitScope populationPerPlayerLimitScope,
                                     @Nonnull ClaimIntegrationProvider simpleClaimsProvider,
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
                                     @Nonnull String needsResourceMode,
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
                                     boolean levelingEnabled,
                                     boolean talentsEnabled,
                                     boolean reviveSystemEnabled,
                                     boolean recallTeleportingEnabled,
                                     boolean telemetryEnabled,
                                     boolean telemetryBreadcrumbsEnabled) {

    @Nonnull
    TameworkSettingsStore.GlobalSettingsSnapshot toGlobalSettingsSnapshot() {
        return new TameworkSettingsStore.GlobalSettingsSnapshot(
                populationLimitPerPlayerOwnedTotal,
                populationPerPlayerLimitScope.configValue(),
                simpleClaimsProvider.configValue(),
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
                needsResourceMode,
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
                levelingEnabled,
                talentsEnabled,
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
                                                  boolean breedingGenderEnabled,
                                                  boolean traitsEnabled,
                                                  boolean levelingEnabled,
                                                  boolean talentsEnabled) {
        return new TameworkSettingsValues(
                populationLimitPerPlayerOwnedTotal,
                populationPerPlayerLimitScope,
                simpleClaimsProvider,
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
                needsResourceMode,
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
                levelingEnabled,
                talentsEnabled,
                reviveSystemEnabled,
                recallTeleportingEnabled,
                telemetryEnabled,
                telemetryBreadcrumbsEnabled
        );
    }

    @Nonnull
    static TameworkSettingsValues fromRuntime() {
        return fromResolvedSettings(TameworkSettingsStore.loadRuntimeGlobalSettings());
    }

    @Nonnull
    private static TameworkSettingsValues fromResolvedSettings(@Nonnull ResolvedTameworkSettings settings) {
        return new TameworkSettingsValues(
                settings.populationLimitPerPlayerOwnedTotal(),
                TwGlobalConfig.PerPlayerLimitScope.fromConfigValue(settings.populationPerPlayerLimitScope()),
                ClaimIntegrationProvider.fromConfigValue(settings.simpleClaimsProvider()),
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
                settings.needsResourceMode(),
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
                settings.levelingEnabled(),
                settings.talentsEnabled(),
                settings.reviveSystemEnabled(),
                settings.recallTeleportingEnabled(),
                settings.telemetryEnabled(),
                settings.telemetryBreadcrumbsEnabled()
        );
    }

}
