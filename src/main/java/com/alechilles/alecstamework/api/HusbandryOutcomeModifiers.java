package com.alechilles.alecstamework.api;

/** Bounded outcome modifiers returned by a husbandry outcome provider. */
public record HusbandryOutcomeModifiers(
        double needsDecayMultiplier,
        double happinessDispositionMultiplier,
        double bonusOutputChance,
        double tripleOutputChance,
        double breedingCooldownMultiplier
) {
    /** Returns neutral values that preserve the normal husbandry action. */
    public static HusbandryOutcomeModifiers identity() {
        return new HusbandryOutcomeModifiers(1.0, 1.0, 0.0, 0.0, 1.0);
    }
}
