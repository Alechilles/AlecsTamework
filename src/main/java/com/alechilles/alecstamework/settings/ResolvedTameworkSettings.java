package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import javax.annotation.Nonnull;

/**
 * Fully resolved non-null runtime settings loaded from `/tw settings`.
 */
public record ResolvedTameworkSettings(int populationLimitPerPlayerOwnedTotal,
                                       @Nonnull String populationPerPlayerLimitScope,
                                       boolean simpleClaimsEnabled,
                                       int simpleClaimsLimitPerClaimChunk,
                                       int simpleClaimsLimitPerClaimTotal,
                                       boolean simpleClaimsBreedingRequiresClaim,
                                       boolean simpleClaimsProtectTamedFromNonMembers,
                                       boolean blockOwnerDamage,
                                       boolean blockAllPlayerDamageIfOwned,
                                       boolean invulnerableIfOwned,
                                       @Nonnull String captureItemOwnership,
                                       boolean captureRequiresOwner,
                                       boolean spawnRequiresOwner,
                                       boolean interactionRequiresOwner,
                                       boolean linkingRequiresOwner,
                                       boolean needsEnabled,
                                       @Nonnull String needsResourceMode,
                                       @Nonnull String needsTickPolicyMode,
                                       double needsOwnerOfflineGraceHours,
                                       double needsOwnerOfflineDecayMultiplier,
                                       boolean needsDamageEnabled,
                                       @Nonnull String needsDamageModel,
                                       @Nonnull String needsDamageDualNeedRule,
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
                                       boolean telemetryBreadcrumbsEnabled,
                                       @Nonnull String animalAgingMode,
                                       boolean animalOldAgeDeathEnabled,
                                       int commandPanelCardsPerPage,
                                       int populationLimitPerPlayerDeployedTotal) {

    /** Compatibility constructor for integrations compiled before the per-player deployed limit: no deployed limit. */
    public ResolvedTameworkSettings(int populationLimitPerPlayerOwnedTotal,
                                    @Nonnull String populationPerPlayerLimitScope,
                                    boolean simpleClaimsEnabled,
                                    int simpleClaimsLimitPerClaimChunk,
                                    int simpleClaimsLimitPerClaimTotal,
                                    boolean simpleClaimsBreedingRequiresClaim,
                                    boolean simpleClaimsProtectTamedFromNonMembers,
                                    boolean blockOwnerDamage,
                                    boolean blockAllPlayerDamageIfOwned,
                                    boolean invulnerableIfOwned,
                                    @Nonnull String captureItemOwnership,
                                    boolean captureRequiresOwner,
                                    boolean spawnRequiresOwner,
                                    boolean interactionRequiresOwner,
                                    boolean linkingRequiresOwner,
                                    boolean needsEnabled,
                                    @Nonnull String needsResourceMode,
                                    @Nonnull String needsTickPolicyMode,
                                    double needsOwnerOfflineGraceHours,
                                    double needsOwnerOfflineDecayMultiplier,
                                    boolean needsDamageEnabled,
                                    @Nonnull String needsDamageModel,
                                    @Nonnull String needsDamageDualNeedRule,
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
                                    boolean telemetryBreadcrumbsEnabled,
                                    @Nonnull String animalAgingMode,
                                    boolean animalOldAgeDeathEnabled,
                                    int commandPanelCardsPerPage) {
        this(populationLimitPerPlayerOwnedTotal, populationPerPlayerLimitScope, simpleClaimsEnabled,
                simpleClaimsLimitPerClaimChunk, simpleClaimsLimitPerClaimTotal,
                simpleClaimsBreedingRequiresClaim, simpleClaimsProtectTamedFromNonMembers, blockOwnerDamage,
                blockAllPlayerDamageIfOwned, invulnerableIfOwned, captureItemOwnership, captureRequiresOwner,
                spawnRequiresOwner, interactionRequiresOwner, linkingRequiresOwner, needsEnabled,
                needsResourceMode, needsTickPolicyMode, needsOwnerOfflineGraceHours,
                needsOwnerOfflineDecayMultiplier, needsDamageEnabled, needsDamageModel,
                needsDamageDualNeedRule, needsStarvationDamagePerMinute, needsDehydrationDamagePerMinute,
                needsDamageLethal, happinessEnabled, passiveBreedingEnabled, breedingRequiresHappiness,
                breedingGenderEnabled, traitsEnabled, levelingEnabled, talentsEnabled, reviveSystemEnabled,
                recallTeleportingEnabled, telemetryEnabled, telemetryBreadcrumbsEnabled, animalAgingMode,
                animalOldAgeDeathEnabled, commandPanelCardsPerPage, 0);
    }

    public ResolvedTameworkSettings {
        captureItemOwnership = CaptureItemOwnershipMode.fromConfigValue(captureItemOwnership).toConfigValue();
    }

    /** Compatibility constructor for integrations compiled before the capture item ownership mode. */
    public ResolvedTameworkSettings(int populationLimitPerPlayerOwnedTotal,
                                    @Nonnull String populationPerPlayerLimitScope,
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
                                    @Nonnull String needsTickPolicyMode,
                                    double needsOwnerOfflineGraceHours,
                                    double needsOwnerOfflineDecayMultiplier,
                                    boolean needsDamageEnabled,
                                    @Nonnull String needsDamageModel,
                                    @Nonnull String needsDamageDualNeedRule,
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
                                    boolean telemetryBreadcrumbsEnabled,
                                    @Nonnull String animalAgingMode,
                                    boolean animalOldAgeDeathEnabled,
                                    int commandPanelCardsPerPage) {
        this(populationLimitPerPlayerOwnedTotal,
                populationPerPlayerLimitScope,
                simpleClaimsEnabled,
                simpleClaimsLimitPerClaimChunk,
                simpleClaimsLimitPerClaimTotal,
                simpleClaimsBreedingRequiresClaim,
                simpleClaimsProtectTamedFromNonMembers,
                blockOwnerDamage,
                blockAllPlayerDamageIfOwned,
                invulnerableIfOwned,
                CaptureItemOwnershipMode.fromLegacy(captureClearsOwner, spawnSetsOwner).toConfigValue(),
                captureRequiresOwner,
                spawnRequiresOwner,
                interactionRequiresOwner,
                linkingRequiresOwner,
                needsEnabled,
                needsResourceMode,
                needsTickPolicyMode,
                needsOwnerOfflineGraceHours,
                needsOwnerOfflineDecayMultiplier,
                needsDamageEnabled,
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
                telemetryBreadcrumbsEnabled,
                animalAgingMode,
                animalOldAgeDeathEnabled,
                commandPanelCardsPerPage);
    }

    /** Compatibility constructor for integrations compiled before command-panel pagination settings. */
    public ResolvedTameworkSettings(int populationLimitPerPlayerOwnedTotal,
                                    @Nonnull String populationPerPlayerLimitScope,
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
                                    @Nonnull String needsTickPolicyMode,
                                    double needsOwnerOfflineGraceHours,
                                    double needsOwnerOfflineDecayMultiplier,
                                    boolean needsDamageEnabled,
                                    @Nonnull String needsDamageModel,
                                    @Nonnull String needsDamageDualNeedRule,
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
                                    boolean telemetryBreadcrumbsEnabled,
                                    @Nonnull String animalAgingMode,
                                    boolean animalOldAgeDeathEnabled) {
        this(populationLimitPerPlayerOwnedTotal, populationPerPlayerLimitScope, simpleClaimsEnabled,
                simpleClaimsLimitPerClaimChunk, simpleClaimsLimitPerClaimTotal,
                simpleClaimsBreedingRequiresClaim, simpleClaimsProtectTamedFromNonMembers,
                blockOwnerDamage, blockAllPlayerDamageIfOwned, invulnerableIfOwned,
                CaptureItemOwnershipMode.fromLegacy(captureClearsOwner, spawnSetsOwner).toConfigValue(),
                captureRequiresOwner, spawnRequiresOwner,
                interactionRequiresOwner, linkingRequiresOwner, needsEnabled, needsResourceMode,
                needsTickPolicyMode, needsOwnerOfflineGraceHours, needsOwnerOfflineDecayMultiplier,
                needsDamageEnabled, needsDamageModel, needsDamageDualNeedRule,
                needsStarvationDamagePerMinute, needsDehydrationDamagePerMinute, needsDamageLethal,
                happinessEnabled, passiveBreedingEnabled, breedingRequiresHappiness, breedingGenderEnabled,
                traitsEnabled, levelingEnabled, talentsEnabled, reviveSystemEnabled,
                recallTeleportingEnabled, telemetryEnabled, telemetryBreadcrumbsEnabled,
                animalAgingMode, animalOldAgeDeathEnabled, 50);
    }

    /** Compatibility constructor for integrations compiled against settings schema v1. */
    public ResolvedTameworkSettings(int populationLimitPerPlayerOwnedTotal,
                                    @Nonnull String populationPerPlayerLimitScope,
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
                                    @Nonnull String needsTickPolicyMode,
                                    double needsOwnerOfflineGraceHours,
                                    double needsOwnerOfflineDecayMultiplier,
                                    boolean needsDamageEnabled,
                                    @Nonnull String needsDamageModel,
                                    @Nonnull String needsDamageDualNeedRule,
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
        this(populationLimitPerPlayerOwnedTotal, populationPerPlayerLimitScope, simpleClaimsEnabled,
                simpleClaimsLimitPerClaimChunk, simpleClaimsLimitPerClaimTotal,
                simpleClaimsBreedingRequiresClaim, simpleClaimsProtectTamedFromNonMembers,
                blockOwnerDamage, blockAllPlayerDamageIfOwned, invulnerableIfOwned,
                captureClearsOwner, spawnSetsOwner,
                captureRequiresOwner, spawnRequiresOwner,
                interactionRequiresOwner, linkingRequiresOwner, needsEnabled, needsResourceMode,
                needsTickPolicyMode, needsOwnerOfflineGraceHours, needsOwnerOfflineDecayMultiplier,
                needsDamageEnabled, needsDamageModel, needsDamageDualNeedRule,
                needsStarvationDamagePerMinute, needsDehydrationDamagePerMinute, needsDamageLethal,
                happinessEnabled, passiveBreedingEnabled, breedingRequiresHappiness, breedingGenderEnabled,
                traitsEnabled, levelingEnabled, talentsEnabled, reviveSystemEnabled,
                recallTeleportingEnabled, telemetryEnabled, telemetryBreadcrumbsEnabled,
                AnimalAgingMode.FREEZE_AT_PRIME.toConfigValue(), false);
    }

    /** The resolved {@link CaptureItemOwnershipMode}. */
    @Nonnull
    public CaptureItemOwnershipMode captureItemOwnershipMode() {
        return CaptureItemOwnershipMode.fromConfigValue(captureItemOwnership);
    }

    /**
     * Retired with the capture item ownership mode: a capture never clears the owner.
     *
     * @deprecated use {@link #captureItemOwnershipMode()}
     */
    @Deprecated
    public boolean captureClearsOwner() {
        return false;
    }

    /**
     * Retired with the capture item ownership mode. True when a release by a player who is not
     * the owner makes that player the owner (every mode except {@code OWNER_ONLY}).
     *
     * @deprecated use {@link #captureItemOwnershipMode()}
     */
    @Deprecated
    public boolean spawnSetsOwner() {
        return captureItemOwnershipMode() != CaptureItemOwnershipMode.OWNER_ONLY;
    }

    @Nonnull
    public TameworkSettingsStore.GlobalSettingsSnapshot toSnapshot() {
        return new TameworkSettingsStore.GlobalSettingsSnapshot(
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
                captureItemOwnership,
                captureRequiresOwner,
                spawnRequiresOwner,
                interactionRequiresOwner,
                linkingRequiresOwner,
                needsEnabled,
                needsResourceMode,
                needsTickPolicyMode,
                needsOwnerOfflineGraceHours,
                needsOwnerOfflineDecayMultiplier,
                needsDamageEnabled,
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
                telemetryBreadcrumbsEnabled,
                animalAgingMode,
                animalOldAgeDeathEnabled,
                commandPanelCardsPerPage,
                populationLimitPerPlayerDeployedTotal
        );
    }

    /**
     * The owner-offline policy now governs animal progression as well as needs.
     * Kept for existing needs consumers and saved-setting callers.
     */
    @Nonnull
    public String animalProgressionPolicyMode() {
        return needsTickPolicyMode;
    }

    public double animalProgressionOwnerOfflineGraceHours() {
        return needsOwnerOfflineGraceHours;
    }

    public double animalProgressionOwnerOfflineMultiplier() {
        return needsOwnerOfflineDecayMultiplier;
    }
}
