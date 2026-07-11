package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import javax.annotation.Nonnull;

/**
 * Resolves one immutable litter plan while both parents and their role context are live.
 *
 * <p>The fertility source is sampled exactly once. The child resolver is then invoked exactly
 * once for each resolved child, so delayed callbacks only consume captured values and never reroll
 * fertility, roles, gender, lifecycle family, or population type.
 */
public final class BreedingBirthPlanService {
    private static final int MAX_CHILDREN = 4;

    private final DoubleSupplier fertilityRollSource;

    /** Uses thread-local randomness for production fertility rolls. */
    public BreedingBirthPlanService() {
        this(() -> ThreadLocalRandom.current().nextDouble());
    }

    /** Uses the supplied deterministic source for the single fertility roll per plan. */
    public BreedingBirthPlanService(@Nonnull DoubleSupplier fertilityRollSource) {
        this.fertilityRollSource = Objects.requireNonNull(fertilityRollSource, "fertilityRollSource");
    }

    /** Creates a plan from already-resolved, finite, nonnegative parent fertility multipliers. */
    @Nonnull
    public BreedingBirthPlan createPlan(double parentAMultiplier,
                                        double parentBMultiplier,
                                        @Nonnull PlannedChildResolver childResolver) {
        requireMultiplier(parentAMultiplier, "parentAMultiplier");
        requireMultiplier(parentBMultiplier, "parentBMultiplier");
        Objects.requireNonNull(childResolver, "childResolver");

        double expected = clampExpected(parentAMultiplier * parentBMultiplier);
        double sampledRoll = fertilityRollSource.getAsDouble();
        if (!Double.isFinite(sampledRoll) || sampledRoll < 0.0 || sampledRoll >= 1.0) {
            throw new IllegalStateException("Fertility roll source must return a value in [0, 1)");
        }
        int childCount = resolveChildCount(expected, sampledRoll);
        ArrayList<PlannedChild> children = new ArrayList<>(childCount);
        for (int childIndex = 0; childIndex < childCount; childIndex++) {
            children.add(Objects.requireNonNull(
                    childResolver.resolve(childIndex),
                    "childResolver result at index " + childIndex
            ));
        }
        return new BreedingBirthPlan(
                new BreedingFertilitySnapshot(
                        parentAMultiplier,
                        parentBMultiplier,
                        expected,
                        sampledRoll,
                        childCount
                ),
                List.copyOf(children)
        );
    }

    static int resolveChildCount(double expected, double sampledRoll) {
        int guaranteed = (int) Math.floor(expected);
        double fractional = expected - guaranteed;
        int resolved = guaranteed;
        if (resolved < MAX_CHILDREN && sampledRoll < fractional) {
            resolved++;
        }
        return Math.max(0, Math.min(MAX_CHILDREN, resolved));
    }

    private static double clampExpected(double expected) {
        if (!Double.isFinite(expected)) {
            return MAX_CHILDREN;
        }
        return Math.min(MAX_CHILDREN, expected);
    }

    private static void requireMultiplier(double multiplier, String label) {
        if (!Double.isFinite(multiplier) || multiplier < 0.0) {
            throw new IllegalArgumentException(label + " must be finite and nonnegative");
        }
    }

    /** Resolves a fully specified child for the supplied zero-based litter index. */
    @FunctionalInterface
    public interface PlannedChildResolver {
        @Nonnull
        PlannedChild resolve(int childIndex);
    }
}
