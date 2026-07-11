package com.alechilles.alecstamework.npc.breeding;

import javax.annotation.Nonnull;

/**
 * Immutable result of the single fertility roll performed before a breeding job is admitted.
 *
 * <p>The sampled roll is retained so diagnostics and deterministic tests can explain the resolved
 * litter without invoking randomness again from delayed callbacks.
 */
public record BreedingFertilitySnapshot(double parentAMultiplier,
                                        double parentBMultiplier,
                                        double expectedOffspring,
                                        double sampledRoll,
                                        int rolledChildCount) {
    private static final int MAX_CHILDREN_PER_ROLL = 4;

    public BreedingFertilitySnapshot {
        requireFiniteNonNegative(parentAMultiplier, "parentAMultiplier");
        requireFiniteNonNegative(parentBMultiplier, "parentBMultiplier");
        if (!Double.isFinite(expectedOffspring)
                || expectedOffspring < 0.0
                || expectedOffspring > MAX_CHILDREN_PER_ROLL) {
            throw new IllegalArgumentException("expectedOffspring must be finite and between 0 and 4");
        }
        if (!Double.isFinite(sampledRoll) || sampledRoll < 0.0 || sampledRoll >= 1.0) {
            throw new IllegalArgumentException("sampledRoll must be finite and in [0, 1)");
        }
        if (rolledChildCount < 0 || rolledChildCount > MAX_CHILDREN_PER_ROLL) {
            throw new IllegalArgumentException("rolledChildCount must be between 0 and 4");
        }
        int guaranteed = (int) Math.floor(expectedOffspring);
        double fractional = expectedOffspring - guaranteed;
        int resolvedCount = guaranteed;
        if (resolvedCount < MAX_CHILDREN_PER_ROLL && sampledRoll < fractional) {
            resolvedCount++;
        }
        if (rolledChildCount != resolvedCount) {
            throw new IllegalArgumentException("rolledChildCount does not match expectedOffspring and sampledRoll");
        }
    }

    /**
     * Creates a compatibility snapshot for callers that predate explicit fertility diagnostics.
     */
    @Nonnull
    public static BreedingFertilitySnapshot countOnly(int rolledChildCount) {
        return new BreedingFertilitySnapshot(
                1.0,
                1.0,
                rolledChildCount,
                0.0,
                rolledChildCount
        );
    }

    private static void requireFiniteNonNegative(double value, String label) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(label + " must be finite and nonnegative");
        }
    }
}
