package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves optional JSON setting values into the non-null runtime settings contract.
 */
public final class TameworkSettingsResolver {
    private TameworkSettingsResolver() {
    }

    @Nonnull
    public static ResolvedTameworkSettings defaultSettings() {
        TameworkSettingsStore.GlobalOverrides defaults = TameworkSettingsStore.defaultGlobalOverrides();
        return resolve(defaults, defaults);
    }

    @Nonnull
    public static ResolvedTameworkSettings resolve(@Nullable TameworkSettingsStore.GlobalOverrides overrides) {
        return resolve(overrides, TameworkSettingsStore.defaultGlobalOverrides());
    }

    @Nonnull
    private static ResolvedTameworkSettings resolve(@Nullable TameworkSettingsStore.GlobalOverrides overrides,
                                                    @Nonnull TameworkSettingsStore.GlobalOverrides defaults) {
        TameworkSettingsStore.GlobalOverrides values = overrides == null ? defaults : overrides;
        return new ResolvedTameworkSettings(
                resolveNonNegativeInt(values.populationLimitPerPlayerOwnedTotal(), defaults.populationLimitPerPlayerOwnedTotal()),
                normalizeScope(resolveString(values.populationPerPlayerLimitScope(), defaults.populationPerPlayerLimitScope())),
                resolveBoolean(values.simpleClaimsEnabled(), defaults.simpleClaimsEnabled()),
                resolveNonNegativeInt(values.simpleClaimsLimitPerClaimChunk(), defaults.simpleClaimsLimitPerClaimChunk()),
                resolveNonNegativeInt(values.simpleClaimsLimitPerClaimTotal(), defaults.simpleClaimsLimitPerClaimTotal()),
                resolveBoolean(values.simpleClaimsBreedingRequiresClaim(), defaults.simpleClaimsBreedingRequiresClaim()),
                resolveBoolean(values.simpleClaimsProtectTamedFromNonMembers(), defaults.simpleClaimsProtectTamedFromNonMembers()),
                resolveBoolean(values.blockOwnerDamage(), defaults.blockOwnerDamage()),
                resolveBoolean(values.blockAllPlayerDamageIfOwned(), defaults.blockAllPlayerDamageIfOwned()),
                resolveBoolean(values.invulnerableIfOwned(), defaults.invulnerableIfOwned()),
                resolveBoolean(values.captureClearsOwner(), defaults.captureClearsOwner()),
                resolveBoolean(values.spawnSetsOwner(), defaults.spawnSetsOwner()),
                resolveBoolean(values.captureRequiresOwner(), defaults.captureRequiresOwner()),
                resolveBoolean(values.spawnRequiresOwner(), defaults.spawnRequiresOwner()),
                resolveBoolean(values.interactionRequiresOwner(), defaults.interactionRequiresOwner()),
                resolveBoolean(values.linkingRequiresOwner(), defaults.linkingRequiresOwner()),
                resolveBoolean(values.needsEnabled(), defaults.needsEnabled()),
                resolveString(values.needsTickPolicyMode(), defaults.needsTickPolicyMode()),
                resolveNonNegativeDouble(values.needsOwnerOfflineGraceHours(), defaults.needsOwnerOfflineGraceHours()),
                resolveNonNegativeDouble(values.needsOwnerOfflineDecayMultiplier(), defaults.needsOwnerOfflineDecayMultiplier()),
                resolveBoolean(values.needsDamageEnabled(), defaults.needsDamageEnabled()),
                resolveString(values.needsDamageModel(), defaults.needsDamageModel()),
                resolveString(values.needsDamageDualNeedRule(), defaults.needsDamageDualNeedRule()),
                resolveNonNegativeDouble(values.needsStarvationDamagePerMinute(), defaults.needsStarvationDamagePerMinute()),
                resolveNonNegativeDouble(values.needsDehydrationDamagePerMinute(), defaults.needsDehydrationDamagePerMinute()),
                resolveBoolean(values.needsDamageLethal(), defaults.needsDamageLethal()),
                resolveBoolean(values.happinessEnabled(), defaults.happinessEnabled()),
                resolveBoolean(values.passiveBreedingEnabled(), defaults.passiveBreedingEnabled()),
                resolveBoolean(values.breedingRequiresHappiness(), defaults.breedingRequiresHappiness()),
                resolveBoolean(values.breedingGenderEnabled(), defaults.breedingGenderEnabled()),
                resolveBoolean(values.traitsEnabled(), defaults.traitsEnabled()),
                resolveBoolean(values.reviveSystemEnabled(), defaults.reviveSystemEnabled()),
                resolveBoolean(values.recallTeleportingEnabled(), defaults.recallTeleportingEnabled()),
                resolveBoolean(values.telemetryEnabled(), defaults.telemetryEnabled()),
                resolveBoolean(values.telemetryBreadcrumbsEnabled(), defaults.telemetryBreadcrumbsEnabled())
        );
    }

    @Nonnull
    private static String resolveString(@Nullable String value, @Nonnull String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private static boolean resolveBoolean(@Nullable Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }

    private static int resolveNonNegativeInt(@Nullable Integer value, int fallback) {
        return Math.max(0, value != null ? value : fallback);
    }

    private static double resolveNonNegativeDouble(@Nullable Double value, double fallback) {
        double resolved = value != null ? value : fallback;
        if (!Double.isFinite(resolved) || resolved < 0.0) {
            return fallback;
        }
        return resolved;
    }

    @Nonnull
    private static String normalizeScope(@Nullable String scope) {
        if (scope == null || scope.isBlank()) {
            return "PerWorld";
        }
        String normalized = scope.trim();
        if ("global".equalsIgnoreCase(normalized)) {
            return "Global";
        }
        return "PerWorld";
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
