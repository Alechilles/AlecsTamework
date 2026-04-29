package com.alechilles.alecstamework.npc.progression;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nullable;

/**
 * Applies trait-driven stat modifiers to companion stat maps.
 *
 * <p>Currently this service wires `MaxHealthMultiplier` into `Health` max-stat modifiers
 * and delegates non-stat trait effects (for example move-speed effects).
 */
public final class CompanionStatModifierService {
    private static final String HEALTH_STAT_ID = "Health";
    private static final String MAX_HEALTH_MULTIPLIER_EFFECT_KEY = "MaxHealthMultiplier";
    private static final String TRAIT_MAX_HEALTH_MODIFIER_KEY = "Tamework_Trait_MaxHealth";
    private static final double EPSILON = 0.000001;

    private CompanionStatModifierService() {
    }

    public static void applyTraitModifiers(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        applyMaxHealthModifier(npcRef, store);
        CompanionTraitEffectService.applyTraitEffects(npcRef, store);
    }

    private static void applyMaxHealthModifier(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();
        if (statType == null) {
            return;
        }
        EntityStatMap statMap = store.getComponent(npcRef, statType);
        if (statMap == null) {
            return;
        }
        int healthIndex = EntityStatType.getAssetMap().getIndex(HEALTH_STAT_ID);
        if (healthIndex < 0) {
            return;
        }
        EntityStatValue healthValue = statMap.get(healthIndex);
        if (healthValue == null) {
            return;
        }

        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        Role role = npc != null ? npc.getRole() : null;
        double baseMax = role != null ? role.getInitialMaxHealth() : 0.0;
        if (baseMax <= 0.0 || !Double.isFinite(baseMax)) {
            baseMax = Math.max(1.0, healthValue.getMax());
        }

        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                MAX_HEALTH_MULTIPLIER_EFFECT_KEY,
                1.0
        );
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = 1.0;
        }
        float bonus = (float) (baseMax * (multiplier - 1.0));

        if (Math.abs(bonus) <= EPSILON) {
            statMap.removeModifier(healthIndex, TRAIT_MAX_HEALTH_MODIFIER_KEY);
        } else {
            statMap.putModifier(
                    healthIndex,
                    TRAIT_MAX_HEALTH_MODIFIER_KEY,
                    new StaticModifier(
                            Modifier.ModifierTarget.MAX,
                            StaticModifier.CalculationType.ADDITIVE,
                            bonus
                    )
            );
        }

        EntityStatValue updated = statMap.get(healthIndex);
        if (updated != null && updated.get() > updated.getMax()) {
            statMap.setStatValue(healthIndex, updated.getMax());
        }
    }
}
