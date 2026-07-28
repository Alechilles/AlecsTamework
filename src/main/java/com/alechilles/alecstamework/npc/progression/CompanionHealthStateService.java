package com.alechilles.alecstamework.npc.progression;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Captures and reapplies live companion health for storage-style persistence flows.
 */
public final class CompanionHealthStateService {
    private static final String HEALTH_STAT_ID = "Health";
    private static final double EPSILON = 0.000001;

    private CompanionHealthStateService() {
    }

    @Nullable
    public static Double captureHealthPercent(@Nullable Ref<EntityStore> npcRef,
                                              @Nullable Store<EntityStore> store) {
        HealthSnapshot snapshot = captureHealth(npcRef, store);
        return snapshot == null ? null : snapshot.healthPercent();
    }

    /** Captures the exact live health pair and its compatibility percentage. */
    @Nullable
    public static HealthSnapshot captureHealth(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store) {
        HealthContext context = resolveHealthContext(npcRef, store);
        if (context == null) {
            return null;
        }
        double current = context.value().get();
        double maximum = context.value().getMax();
        Double percent = resolveStoredHealthPercent(current, maximum);
        return percent == null ? null : new HealthSnapshot(
                clamp(current, 0.0, maximum), maximum, percent
        );
    }

    public static boolean applyStoredHealthPercent(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store,
                                                   @Nullable Double healthPercent) {
        return applyStoredHealth(npcRef, store, null, null, healthPercent);
    }

    /**
     * Restores an exact stored health value when the rebuilt stat maximum still
     * matches; otherwise the durable percentage remains the safe fallback.
     */
    public static boolean applyStoredHealth(@Nullable Ref<EntityStore> npcRef,
                                            @Nullable Store<EntityStore> store,
                                            @Nullable Double currentHealth,
                                            @Nullable Double maximumHealth,
                                            @Nullable Double healthPercent) {
        HealthContext context = resolveHealthContext(npcRef, store);
        if (context == null) {
            return false;
        }
        double rebuiltMaximum = context.value().getMax();
        Double exact = exactValue(currentHealth, maximumHealth, rebuiltMaximum);
        if (exact == null && healthPercent == null) {
            return false;
        }
        double targetHealth = exact != null ? exact
                : resolveRestoredHealthValue(healthPercent, rebuiltMaximum);
        double existingHealth = context.value().get();
        if (Double.isFinite(existingHealth)
                && Math.abs(existingHealth - targetHealth) <= EPSILON) {
            return false;
        }
        context.statMap().setStatValue(context.healthIndex(), (float) targetHealth);
        return true;
    }

    @Nullable
    static Double resolveStoredHealthPercent(double currentHealth, double maxHealth) {
        if (!Double.isFinite(currentHealth) || !Double.isFinite(maxHealth) || maxHealth <= 0.0) {
            return null;
        }
        double clampedCurrentHealth = clamp(currentHealth, 0.0, maxHealth);
        return (clampedCurrentHealth / maxHealth) * 100.0;
    }

    static double resolveRestoredHealthValue(double healthPercent, double maxHealth) {
        if (!Double.isFinite(healthPercent) || !Double.isFinite(maxHealth) || maxHealth <= 0.0) {
            return 0.0;
        }
        double clampedPercent = clamp(healthPercent, 0.0, 100.0);
        return clamp(maxHealth * (clampedPercent / 100.0), 0.0, maxHealth);
    }

    @Nullable
    private static Double exactValue(@Nullable Double currentHealth,
                                     @Nullable Double maximumHealth,
                                     double rebuiltMaximum) {
        if (currentHealth == null || maximumHealth == null
                || !Double.isFinite(currentHealth)
                || !Double.isFinite(maximumHealth)
                || maximumHealth <= 0.0
                || currentHealth < 0.0
                || currentHealth > maximumHealth
                || !Double.isFinite(rebuiltMaximum)
                || rebuiltMaximum <= 0.0
                || Math.abs(rebuiltMaximum - maximumHealth) > EPSILON) {
            return null;
        }
        return currentHealth;
    }

    @Nullable
    private static HealthContext resolveHealthContext(@Nullable Ref<EntityStore> npcRef,
                                                      @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();
        if (statType == null) {
            return null;
        }
        EntityStatMap statMap = store.getComponent(npcRef, statType);
        if (statMap == null) {
            return null;
        }
        int healthIndex = EntityStatType.getAssetMap().getIndex(HEALTH_STAT_ID);
        if (healthIndex < 0) {
            return null;
        }
        EntityStatValue healthValue = statMap.get(healthIndex);
        if (healthValue == null) {
            return null;
        }
        return new HealthContext(statMap, healthIndex, healthValue);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private record HealthContext(EntityStatMap statMap, int healthIndex, EntityStatValue value) {
    }

    /** Exact source values plus the legacy percentage restoration fallback. */
    public record HealthSnapshot(double currentHealth, double maximumHealth,
                                 double healthPercent) {
        public HealthSnapshot {
            if (!Double.isFinite(currentHealth) || !Double.isFinite(maximumHealth)
                    || !Double.isFinite(healthPercent) || maximumHealth <= 0.0
                    || currentHealth < 0.0 || currentHealth > maximumHealth) {
                throw new IllegalArgumentException("invalid health snapshot");
            }
        }
    }
}
