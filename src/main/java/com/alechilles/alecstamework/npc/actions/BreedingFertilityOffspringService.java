package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.progression.TraitModifierService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;

/**
 * Resolves fertility-driven offspring roll counts for a breeding pair.
 */
final class BreedingFertilityOffspringService {
    private static final String FERTILITY_MULTIPLIER_EFFECT_KEY = "FertilityMultiplier";
    private static final int MAX_OFFSPRING_PER_BREED = 4;

    FertilityRoll rollOffspring(@Nullable Ref<EntityStore> parentARef,
                                @Nullable Ref<EntityStore> parentBRef,
                                @Nullable Store<EntityStore> store) {
        double parentAMultiplier = resolveFertilityMultiplier(parentARef, store);
        double parentBMultiplier = resolveFertilityMultiplier(parentBRef, store);
        double expected = clampExpectedOffspring(parentAMultiplier * parentBMultiplier);
        double roll = ThreadLocalRandom.current().nextDouble();
        int count = resolveOffspringCount(expected, roll);
        return new FertilityRoll(parentAMultiplier, parentBMultiplier, expected, count);
    }

    static int resolveOffspringCount(double expectedOffspring, double roll) {
        double expected = clampExpectedOffspring(expectedOffspring);
        int guaranteed = (int) Math.floor(expected);
        double fractional = expected - guaranteed;
        int count = guaranteed;
        if (count < MAX_OFFSPRING_PER_BREED && roll < fractional) {
            count++;
        }
        return Math.max(0, Math.min(MAX_OFFSPRING_PER_BREED, count));
    }

    private static double resolveFertilityMultiplier(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store) {
        double value = TraitModifierService.resolveMultiplier(
                npcRef,
                store,
                FERTILITY_MULTIPLIER_EFFECT_KEY,
                1.0
        );
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        return Math.max(0.0, value);
    }

    private static double clampExpectedOffspring(double expected) {
        if (!Double.isFinite(expected)) {
            return 1.0;
        }
        if (expected < 0.0) {
            return 0.0;
        }
        return Math.min(MAX_OFFSPRING_PER_BREED, expected);
    }

    record FertilityRoll(double parentAMultiplier,
                         double parentBMultiplier,
                         double expectedOffspring,
                         int offspringCount) {
    }
}
