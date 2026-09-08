package com.alechilles.alecstamework.api;

/** Bounded outcome modifiers returned by a husbandry outcome provider. */
public record HusbandryOutcomeModifiers(
        double needsDecayMultiplier,
        double happinessDispositionMultiplier,
        double bonusOutputChance,
        double tripleOutputChance,
        double breedingCooldownMultiplier,
        double happinessHungerBonus,
        double happinessThirstBonus,
        double happinessPopulationBonus
) {
    /** Compatibility constructor for providers compiled against the original five modifiers. */
    public HusbandryOutcomeModifiers(double needsDecayMultiplier,
                                    double happinessDispositionMultiplier,
                                    double bonusOutputChance,
                                    double tripleOutputChance,
                                    double breedingCooldownMultiplier) {
        this(needsDecayMultiplier, happinessDispositionMultiplier, bonusOutputChance, tripleOutputChance,
                breedingCooldownMultiplier, 0.0, 0.0, 0.0);
    }

    /** Returns neutral values that preserve the normal husbandry action. */
    public static HusbandryOutcomeModifiers identity() {
        return new HusbandryOutcomeModifiers(1.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0);
    }
}
