package com.alechilles.alecstamework.api;

/**
 * Bounded outcome modifiers returned by a husbandry outcome provider.
 * For HAPPINESS_CARE, happinessFlatBonus adds 0-100 points independently of
 * condition bands and disposition scaling. Providers must check
 * HUSBANDRY_FLAT_CARE_BONUS before relying on it. Supply zero conditional bonuses
 * when the flat bonus replaces them; nonzero values are additive.
 */
public record HusbandryOutcomeModifiers(
        double needsDecayMultiplier,
        double happinessDispositionMultiplier,
        double bonusOutputChance,
        double tripleOutputChance,
        double breedingCooldownMultiplier,
        double happinessHungerBonus,
        double happinessThirstBonus,
        double happinessPopulationBonus,
        double breedingInheritanceChanceBonus,
        double harmfulMutationRerollChance,
        double happinessFlatBonus,
        double yieldBonus,
        double harvestRecoverySpeedBonus,
        boolean toolAuthorized,
        double toolWearMultiplier
) {
    /** Compatibility constructor for the pre-tool outcome contract. */
    public HusbandryOutcomeModifiers(double needsDecayMultiplier, double happinessDispositionMultiplier,
            double bonusOutputChance, double tripleOutputChance, double breedingCooldownMultiplier,
            double happinessHungerBonus, double happinessThirstBonus, double happinessPopulationBonus,
            double breedingInheritanceChanceBonus, double harmfulMutationRerollChance,
            double happinessFlatBonus) {
        this(needsDecayMultiplier, happinessDispositionMultiplier, bonusOutputChance, tripleOutputChance,
                breedingCooldownMultiplier, happinessHungerBonus, happinessThirstBonus, happinessPopulationBonus,
                breedingInheritanceChanceBonus, harmfulMutationRerollChance, happinessFlatBonus,
                0.0, 0.0, true, 1.0);
    }
    /** Compatibility constructor: existing providers keep their conditional Care bonuses. */
    public HusbandryOutcomeModifiers(double needsDecayMultiplier, double happinessDispositionMultiplier,
            double bonusOutputChance, double tripleOutputChance, double breedingCooldownMultiplier,
            double happinessHungerBonus, double happinessThirstBonus, double happinessPopulationBonus,
            double breedingInheritanceChanceBonus, double harmfulMutationRerollChance) {
        this(needsDecayMultiplier, happinessDispositionMultiplier, bonusOutputChance, tripleOutputChance,
                breedingCooldownMultiplier, happinessHungerBonus, happinessThirstBonus, happinessPopulationBonus,
                breedingInheritanceChanceBonus, harmfulMutationRerollChance, 0.0);
    }
    /** Compatibility constructor for providers compiled against the original five modifiers. */
    public HusbandryOutcomeModifiers(double needsDecayMultiplier,
                                    double happinessDispositionMultiplier,
                                    double bonusOutputChance,
                                    double tripleOutputChance,
                                    double breedingCooldownMultiplier) {
        this(needsDecayMultiplier, happinessDispositionMultiplier, bonusOutputChance, tripleOutputChance,
                breedingCooldownMultiplier, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /** Compatibility constructor for providers compiled before breeding genetics modifiers existed. */
    public HusbandryOutcomeModifiers(double needsDecayMultiplier,
                                    double happinessDispositionMultiplier,
                                    double bonusOutputChance,
                                    double tripleOutputChance,
                                    double breedingCooldownMultiplier,
                                    double happinessHungerBonus,
                                    double happinessThirstBonus,
                                    double happinessPopulationBonus) {
        this(needsDecayMultiplier, happinessDispositionMultiplier, bonusOutputChance, tripleOutputChance,
                breedingCooldownMultiplier, happinessHungerBonus, happinessThirstBonus, happinessPopulationBonus,
                0.0, 0.0);
    }

    /** Returns neutral values that preserve the normal husbandry action. */
    public static HusbandryOutcomeModifiers identity() {
        return new HusbandryOutcomeModifiers(1.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }
}
