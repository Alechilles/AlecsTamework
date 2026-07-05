package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Non-null runtime view of the server-wide settings owned by `/tw settings`.
 */
public final class TameworkRuntimeSettings {
    private final ResolvedTameworkSettings values;

    private TameworkRuntimeSettings(@Nonnull ResolvedTameworkSettings values) {
        this.values = values;
    }

    @Nonnull
    public static TameworkRuntimeSettings current() {
        return new TameworkRuntimeSettings(TameworkSettingsStore.loadRuntimeGlobalSettings());
    }

    @Nullable
    public static TameworkRuntimeSettings currentOrNull() {
        try {
            if (Tamework.getInstance() == null) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
        return current();
    }

    @Nonnull
    public static TameworkRuntimeSettings from(@Nonnull ResolvedTameworkSettings values) {
        return new TameworkRuntimeSettings(values);
    }

    @Nonnull
    public ResolvedTameworkSettings values() {
        return values;
    }

    public int populationLimitPerPlayerOwnedTotal() {
        return values.populationLimitPerPlayerOwnedTotal();
    }

    @Nonnull
    public String populationPerPlayerLimitScope() {
        return values.populationPerPlayerLimitScope();
    }

    @Nonnull
    public ClaimIntegrationProvider simpleClaimsProvider() {
        return ClaimIntegrationProvider.fromConfigValue(values.simpleClaimsProvider());
    }

    public boolean simpleClaimsEnabled() {
        return values.simpleClaimsEnabled();
    }

    public int simpleClaimsLimitPerClaimChunk() {
        return values.simpleClaimsLimitPerClaimChunk();
    }

    public int simpleClaimsLimitPerClaimTotal() {
        return values.simpleClaimsLimitPerClaimTotal();
    }

    public boolean simpleClaimsBreedingRequiresClaim() {
        return values.simpleClaimsBreedingRequiresClaim();
    }

    public boolean simpleClaimsProtectTamedFromNonMembers() {
        return values.simpleClaimsProtectTamedFromNonMembers();
    }

    public boolean blockOwnerDamage() {
        return values.blockOwnerDamage();
    }

    public boolean blockAllPlayerDamageIfOwned() {
        return values.blockAllPlayerDamageIfOwned();
    }

    public boolean invulnerableIfOwned() {
        return values.invulnerableIfOwned();
    }

    public boolean captureClearsOwner() {
        return values.captureClearsOwner();
    }

    public boolean spawnSetsOwner() {
        return values.spawnSetsOwner();
    }

    public boolean captureRequiresOwner() {
        return values.captureRequiresOwner();
    }

    public boolean spawnRequiresOwner() {
        return values.spawnRequiresOwner();
    }

    public boolean interactionRequiresOwner() {
        return values.interactionRequiresOwner();
    }

    public boolean linkingRequiresOwner() {
        return values.linkingRequiresOwner();
    }

    public boolean needsEnabled() {
        return values.needsEnabled();
    }

    @Nonnull
    public String needsResourceMode() {
        return values.needsResourceMode();
    }

    @Nonnull
    public NeedsResourceMode needsResourceModeValue() {
        return NeedsResourceMode.fromConfigValue(values.needsResourceMode());
    }

    @Nonnull
    public String needsTickPolicyMode() {
        return values.needsTickPolicyMode();
    }

    public double needsOwnerOfflineGraceHours() {
        return values.needsOwnerOfflineGraceHours();
    }

    public double needsOwnerOfflineDecayMultiplier() {
        return values.needsOwnerOfflineDecayMultiplier();
    }

    public boolean needsDamageEnabled() {
        return values.needsDamageEnabled();
    }

    @Nonnull
    public String needsDamageModel() {
        return values.needsDamageModel();
    }

    @Nonnull
    public String needsDamageDualNeedRule() {
        return values.needsDamageDualNeedRule();
    }

    public double needsStarvationDamagePerMinute() {
        return values.needsStarvationDamagePerMinute();
    }

    public double needsDehydrationDamagePerMinute() {
        return values.needsDehydrationDamagePerMinute();
    }

    public boolean needsDamageLethal() {
        return values.needsDamageLethal();
    }

    @Nonnull
    public TwNeedsConfig.TickPolicySettings resolveNeedsTickPolicy(@Nullable TwNeedsConfig.TickPolicySettings ignoredBase) {
        return TwNeedsConfig.TickPolicySettings.of(
                TwNeedsConfig.TickPolicyMode.fromConfigValue(values.needsTickPolicyMode()),
                values.needsOwnerOfflineGraceHours(),
                values.needsOwnerOfflineDecayMultiplier()
        );
    }

    @Nonnull
    public TwNeedsConfig.DamageSettings resolveNeedsDamage(@Nullable TwNeedsConfig.DamageSettings ignoredBase) {
        return TwNeedsConfig.DamageSettings.of(
                values.needsDamageEnabled(),
                TwNeedsConfig.DamageModel.fromConfigValue(values.needsDamageModel()),
                TwNeedsConfig.DualNeedRule.fromConfigValue(values.needsDamageDualNeedRule()),
                values.needsStarvationDamagePerMinute(),
                values.needsDehydrationDamagePerMinute(),
                values.needsDamageLethal()
        );
    }

    public boolean happinessEnabled() {
        return values.happinessEnabled();
    }

    public boolean passiveBreedingEnabled() {
        return values.passiveBreedingEnabled();
    }

    public boolean breedingRequiresHappiness() {
        return values.breedingRequiresHappiness();
    }

    public boolean breedingHappinessRequirementEnabled() {
        return values.happinessEnabled() && values.breedingRequiresHappiness();
    }

    public boolean breedingGenderEnabled() {
        return values.breedingGenderEnabled();
    }

    public boolean breedingGenderEnabledForConfig(boolean configEnabled) {
        return configEnabled && values.breedingGenderEnabled();
    }

    public boolean traitsEnabled() {
        return values.traitsEnabled();
    }

    public boolean levelingEnabled() {
        return values.levelingEnabled();
    }

    public boolean talentsEnabled() {
        return values.talentsEnabled();
    }

    public boolean reviveSystemEnabled() {
        return values.reviveSystemEnabled();
    }

    public boolean recallTeleportingEnabled() {
        return values.recallTeleportingEnabled();
    }

    public boolean telemetryEnabled() {
        return values.telemetryEnabled();
    }

    public boolean telemetryBreadcrumbsEnabled() {
        return values.telemetryBreadcrumbsEnabled();
    }

    public static int populationLimitPerPlayerOwnedTotal(int configLimit) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.populationLimitPerPlayerOwnedTotal() : configLimit;
    }

    @Nonnull
    public static TwGlobalConfig.PerPlayerLimitScope populationPerPlayerLimitScope(
            @Nullable TwGlobalConfig.PerPlayerLimitScope configScope) {
        TameworkRuntimeSettings settings = currentOrNull();
        if (settings != null) {
            return TwGlobalConfig.PerPlayerLimitScope.fromConfigValue(settings.populationPerPlayerLimitScope());
        }
        return configScope != null ? configScope : TwGlobalConfig.PerPlayerLimitScope.PER_WORLD;
    }

    public static boolean simpleClaimsEnabled(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.simpleClaimsEnabled() : configEnabled;
    }

    @Nonnull
    public static ClaimIntegrationProvider simpleClaimsProvider(@Nullable ClaimIntegrationProvider configProvider) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null
                ? settings.simpleClaimsProvider()
                : (configProvider == null ? ClaimIntegrationProvider.AUTO : configProvider);
    }

    public static int simpleClaimsLimitPerClaimChunk(int configLimit) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.simpleClaimsLimitPerClaimChunk() : configLimit;
    }

    public static int simpleClaimsLimitPerClaimTotal(int configLimit) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.simpleClaimsLimitPerClaimTotal() : configLimit;
    }

    public static boolean simpleClaimsBreedingRequiresClaim(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.simpleClaimsBreedingRequiresClaim() : configEnabled;
    }

    public static boolean simpleClaimsProtectTamedFromNonMembers(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.simpleClaimsProtectTamedFromNonMembers() : configEnabled;
    }

    public static boolean blockOwnerDamage(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.blockOwnerDamage() : configEnabled;
    }

    public static boolean blockAllPlayerDamageIfOwned(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.blockAllPlayerDamageIfOwned() : configEnabled;
    }

    public static boolean invulnerableIfOwned(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.invulnerableIfOwned() : configEnabled;
    }

    public static boolean captureRequiresOwner(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.captureRequiresOwner() : configEnabled;
    }

    public static boolean spawnRequiresOwner(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.spawnRequiresOwner() : configEnabled;
    }

    public static boolean interactionRequiresOwner(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.interactionRequiresOwner() : configEnabled;
    }

    public static boolean linkingRequiresOwner(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.linkingRequiresOwner() : configEnabled;
    }

    public static boolean reviveSystemEnabled(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.reviveSystemEnabled() : configEnabled;
    }

    public static boolean needsEnabled(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.needsEnabled() : configEnabled;
    }

    @Nonnull
    public static NeedsResourceMode needsResourceMode(@Nullable String configMode) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.needsResourceModeValue() : NeedsResourceMode.fromConfigValue(configMode);
    }

    public static boolean happinessEnabled(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.happinessEnabled() : configEnabled;
    }

    public static boolean traitsEnabled(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.traitsEnabled() : configEnabled;
    }

    public static boolean levelingEnabled(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.levelingEnabled() : configEnabled;
    }

    public static boolean talentsEnabled(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.talentsEnabled() : configEnabled;
    }

    public static boolean passiveBreedingEnabled(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.passiveBreedingEnabled() : configEnabled;
    }

    public static boolean breedingRequiresHappiness(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.breedingRequiresHappiness() : configEnabled;
    }

    public static boolean breedingHappinessRequirementEnabled(boolean configHappinessEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null
                ? settings.breedingHappinessRequirementEnabled()
                : configHappinessEnabled;
    }

    public static boolean breedingGenderEnabled(boolean configEnabled) {
        TameworkRuntimeSettings settings = currentOrNull();
        return settings != null ? settings.breedingGenderEnabledForConfig(configEnabled) : configEnabled;
    }

    public static double breedingHappinessThreshold(double configThreshold) {
        return breedingHappinessThreshold(configThreshold, true);
    }

    public static double breedingHappinessThreshold(double configThreshold, boolean configHappinessEnabled) {
        return breedingHappinessRequirementEnabled(configHappinessEnabled) ? configThreshold : 0.0;
    }
}
