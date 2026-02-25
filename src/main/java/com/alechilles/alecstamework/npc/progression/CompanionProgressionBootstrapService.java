package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;

/**
 * Initializes and self-heals progression components (traits, happiness, breeding, life-stage) for companions.
 */
public final class CompanionProgressionBootstrapService {
    private static final double EPSILON = 0.000001;
    private static final double DEFAULT_HAPPINESS_MIN = 0.0;
    private static final double DEFAULT_HAPPINESS_MAX = 100.0;
    private static final double DEFAULT_HAPPINESS_VALUE = 50.0;

    private CompanionProgressionBootstrapService() {
    }

    public static void ensureProgressionComponents(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            return;
        }
        TwBreedingConfig breedingConfig = TwBreedingConfig.resolveForRole(roleId);
        TameworkHappinessComponent happiness = bootstrapHappinessComponent(npcRef, store, roleId);
        bootstrapBreedingComponent(npcRef, store, breedingConfig, happiness);
        ensureTraitComponents(npcRef, store, roleId);
    }

    /**
     * Ensures trait-driven state exists and is actively applied for any configured role.
     */
    public static void ensureTraitComponents(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        if (roleId == null || roleId.isBlank()) {
            return;
        }
        ensureTraitComponents(npcRef, store, roleId);
    }

    private static void ensureTraitComponents(Ref<EntityStore> npcRef,
                                              Store<EntityStore> store,
                                              String roleId) {
        bootstrapTraitsComponent(npcRef, store, roleId);
        CompanionStatModifierService.applyTraitModifiers(npcRef, store);
        CompanionLifeStageService.ensureLifeStageComponent(npcRef, store);
        CompanionLifeStageService.refreshLifeStage(
                npcRef,
                store.getComponent(npcRef, NPCEntity.getComponentType()),
                store
        );
    }

    private static TameworkHappinessComponent bootstrapHappinessComponent(Ref<EntityStore> npcRef,
                                                                          Store<EntityStore> store,
                                                                          String roleId) {
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType == null) {
            return null;
        }
        TwHappinessConfig config = TwHappinessConfig.resolveForRole(roleId);
        TameworkHappinessComponent existing = store.getComponent(npcRef, happinessType);
        long now = System.currentTimeMillis();
        if (existing != null) {
            boolean changed = false;
            if ((existing.getConfigId() == null || existing.getConfigId().isBlank())
                    && config != null
                    && config.getId() != null
                    && !config.getId().isBlank()) {
                existing.setConfigId(config.getId());
                changed = true;
            }
            double fallback = resolveInitialHappinessValue(npcRef, store, config);
            double current = existing.getValue();
            if (!Double.isFinite(current)) {
                current = fallback;
            }
            double clamped = clampHappinessValue(current, config);
            if (!Double.isFinite(clamped)) {
                clamped = clampHappinessValue(fallback, config);
            }
            if (!Double.isFinite(existing.getValue()) || Math.abs(existing.getValue() - clamped) > EPSILON) {
                existing.setValue(clamped);
                changed = true;
            }
            if (existing.getLastUpdateMs() <= 0L) {
                existing.setLastUpdateMs(now);
                changed = true;
            }
            if (changed) {
                store.putComponent(npcRef, happinessType, existing);
            }
            return existing;
        }
        double initial = resolveInitialHappinessValue(npcRef, store, config);
        String configId = config != null && config.getId() != null && !config.getId().isBlank()
                ? config.getId()
                : null;
        TameworkHappinessComponent created = new TameworkHappinessComponent(configId, initial, now);
        store.putComponent(npcRef, happinessType, created);
        return created;
    }

    private static void bootstrapBreedingComponent(Ref<EntityStore> npcRef,
                                                   Store<EntityStore> store,
                                                   TwBreedingConfig config,
                                                   TameworkHappinessComponent happiness) {
        if (config == null || !config.isEnabled()) {
            return;
        }
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return;
        }
        long now = System.currentTimeMillis();
        double happinessValue = happiness != null
                ? happiness.getValue()
                : resolveInitialHappinessValue(npcRef, store, null);
        long lastUpdateMs = happiness != null && happiness.getLastUpdateMs() > 0L
                ? happiness.getLastUpdateMs()
                : now;
        boolean ready = happinessValue >= config.getHappiness().getThreshold();
        TameworkBreedingComponent existing = store.getComponent(npcRef, breedingType);
        if (existing != null) {
            boolean changed = false;
            if ((existing.getConfigId() == null || existing.getConfigId().isBlank()) && config.getId() != null) {
                existing.setConfigId(config.getId());
                changed = true;
            }
            if (!Double.isFinite(existing.getHappiness())
                    || Math.abs(existing.getHappiness() - happinessValue) > EPSILON) {
                existing.setHappiness(happinessValue);
                changed = true;
            }
            if (existing.getLastHappinessUpdateMs() != lastUpdateMs) {
                existing.setLastHappinessUpdateMs(lastUpdateMs);
                changed = true;
            }
            if (existing.isReady() != ready) {
                existing.setReady(ready);
                changed = true;
            }
            if (changed) {
                store.putComponent(npcRef, breedingType, existing);
            }
            return;
        }
        TameworkBreedingComponent created = new TameworkBreedingComponent(
                config.getId(),
                happinessValue,
                lastUpdateMs,
                ready,
                0L,
                null
        );
        store.putComponent(npcRef, breedingType, created);
    }

    private static void bootstrapTraitsComponent(Ref<EntityStore> npcRef,
                                                 Store<EntityStore> store,
                                                 String roleId) {
        TwTraitConfig config = TwTraitConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return;
        }
        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        if (traitsType == null) {
            return;
        }
        TameworkTraitsComponent existing = store.getComponent(npcRef, traitsType);
        if (existing != null) {
            boolean changed = false;
            if ((existing.getConfigId() == null || existing.getConfigId().isBlank()) && config.getId() != null) {
                existing.setConfigId(config.getId());
                changed = true;
            }
            long seed = existing.getRollSeed();
            if (seed == 0L) {
                seed = resolveRollSeed(npcRef, store);
                existing.setRollSeed(seed);
                changed = true;
            }
            if (existing.getTraitValues().length == 0) {
                existing.setTraitValues(TraitRollService.rollTraits(config, seed));
                changed = true;
            }
            if (changed) {
                store.putComponent(npcRef, traitsType, existing);
            }
            return;
        }
        long seed = resolveRollSeed(npcRef, store);
        TameworkTraitsComponent created = new TameworkTraitsComponent(
                config.getId(),
                seed,
                TraitRollService.rollTraits(config, seed)
        );
        store.putComponent(npcRef, traitsType, created);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static long resolveRollSeed(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null && npc.getUuid() != null) {
            UUID uuid = npc.getUuid();
            long seed = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
            if (seed != 0L) {
                return seed;
            }
        }
        long fallback = System.nanoTime();
        return fallback != 0L ? fallback : 1L;
    }

    private static double resolveInitialHappinessValue(Ref<EntityStore> npcRef,
                                                       Store<EntityStore> store,
                                                       TwHappinessConfig happinessConfig) {
        Double legacy = resolveLegacyBreedingHappiness(npcRef, store);
        if (legacy != null && Double.isFinite(legacy)) {
            return clampHappinessValue(legacy, happinessConfig);
        }
        if (happinessConfig != null) {
            TwHappinessConfig.ValueSettings values = happinessConfig.getValues();
            double min = values.getMin();
            double max = values.getMax();
            if (max < min) {
                double swap = min;
                min = max;
                max = swap;
            }
            return clamp(values.getCurrentDefault(), min, max);
        }
        return clamp(DEFAULT_HAPPINESS_VALUE, DEFAULT_HAPPINESS_MIN, DEFAULT_HAPPINESS_MAX);
    }

    private static double clampHappinessValue(double value,
                                              TwHappinessConfig happinessConfig) {
        if (happinessConfig != null) {
            TwHappinessConfig.ValueSettings settings = happinessConfig.getValues();
            double min = settings.getMin();
            double max = settings.getMax();
            if (max < min) {
                double swap = min;
                min = max;
                max = swap;
            }
            return clamp(value, min, max);
        }
        return clamp(value, DEFAULT_HAPPINESS_MIN, DEFAULT_HAPPINESS_MAX);
    }

    private static Double resolveLegacyBreedingHappiness(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return null;
        }
        TameworkBreedingComponent breeding = store.getComponent(npcRef, breedingType);
        if (breeding == null || !Double.isFinite(breeding.getHappiness())) {
            return null;
        }
        return breeding.getHappiness();
    }
}
