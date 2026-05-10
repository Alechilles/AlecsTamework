package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.persistence.TameworkSettingsStore;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Non-null runtime view of the server-wide settings owned by `/tw settings`.
 */
public final class TameworkRuntimeSettings {
    private static final ThreadLocal<Boolean> SUPPRESS_RUNTIME_SETTINGS = ThreadLocal.withInitial(() -> false);

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
        if (Boolean.TRUE.equals(SUPPRESS_RUNTIME_SETTINGS.get())) {
            return null;
        }
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
    public static <T> T withoutRuntimeSettings(@Nonnull Supplier<T> supplier) {
        Boolean previous = SUPPRESS_RUNTIME_SETTINGS.get();
        SUPPRESS_RUNTIME_SETTINGS.set(true);
        try {
            return supplier.get();
        } finally {
            SUPPRESS_RUNTIME_SETTINGS.set(previous);
        }
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

    public boolean happinessEnabled() {
        return values.happinessEnabled();
    }

    public boolean passiveBreedingEnabled() {
        return values.passiveBreedingEnabled();
    }

    public boolean breedingRequiresHappiness() {
        return values.breedingRequiresHappiness();
    }

    public boolean breedingGenderEnabled() {
        return values.breedingGenderEnabled();
    }

    public boolean traitsEnabled() {
        return values.traitsEnabled();
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
}
