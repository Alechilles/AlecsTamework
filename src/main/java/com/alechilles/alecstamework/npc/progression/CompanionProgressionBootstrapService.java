package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;

/**
 * Initializes breeding and trait components for companions when they first become tamed.
 */
public final class CompanionProgressionBootstrapService {
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
        bootstrapBreedingComponent(npcRef, store, roleId);
        bootstrapTraitsComponent(npcRef, store, roleId);
    }

    private static void bootstrapBreedingComponent(Ref<EntityStore> npcRef,
                                                   Store<EntityStore> store,
                                                   String roleId) {
        TwBreedingConfig config = TwBreedingConfig.resolveForRole(roleId);
        if (config == null || !config.isEnabled()) {
            return;
        }
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return;
        }
        TameworkBreedingComponent existing = store.getComponent(npcRef, breedingType);
        if (existing != null) {
            if ((existing.getConfigId() == null || existing.getConfigId().isBlank()) && config.getId() != null) {
                existing.setConfigId(config.getId());
                store.putComponent(npcRef, breedingType, existing);
            }
            return;
        }
        TwBreedingConfig.HappinessSettings happinessSettings = config.getHappiness();
        double min = happinessSettings.getMin();
        double max = happinessSettings.getMax();
        if (max < min) {
            double swap = max;
            max = min;
            min = swap;
        }
        double value = clamp(happinessSettings.getCurrentDefault(), min, max);
        boolean ready = value >= happinessSettings.getThreshold();
        long now = System.currentTimeMillis();
        TameworkBreedingComponent created = new TameworkBreedingComponent(
                config.getId(),
                value,
                now,
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
}
