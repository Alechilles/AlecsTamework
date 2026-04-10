package com.alechilles.alecstamework.npc.progression;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Aggregates trait, leveling, and passive talent multipliers behind one shared effect-key lookup.
 */
public final class CompanionProgressionModifierService {
    private static final double EPSILON = 0.000001;

    private CompanionProgressionModifierService() {
    }

    public static double resolveMultiplier(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store,
                                           @Nullable String effectKey,
                                           double defaultMultiplier) {
        if (npcRef == null || !npcRef.isValid() || store == null || effectKey == null || effectKey.isBlank()) {
            return defaultMultiplier;
        }
        double trait = TraitModifierService.resolveMultiplier(npcRef, store, effectKey, 1.0);
        double leveling = CompanionLevelingService.resolveLevelGrowthMultiplier(npcRef, store, effectKey, 1.0);
        double talents = CompanionTalentService.resolvePurchasedEffectMultiplier(npcRef, store, effectKey, 1.0);
        boolean matched = Math.abs(trait - 1.0) > EPSILON
                || Math.abs(leveling - 1.0) > EPSILON
                || Math.abs(talents - 1.0) > EPSILON;
        double combined = defaultMultiplier * trait * leveling * talents;
        return matched ? combined : defaultMultiplier;
    }
}
