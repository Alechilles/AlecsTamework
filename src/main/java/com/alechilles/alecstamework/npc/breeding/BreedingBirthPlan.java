package com.alechilles.alecstamework.npc.breeding;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Immutable result of the one fertility and child-resolution pass performed at pairing admission.
 *
 * <p>The ordered child list is defensively copied. Delayed callbacks consume this plan and never
 * reroll fertility, role, adult role, gender, lifecycle family, or population type.
 */
public record BreedingBirthPlan(@Nonnull BreedingFertilitySnapshot fertilitySnapshot,
                                @Nonnull List<PlannedChild> children) {

    public BreedingBirthPlan {
        Objects.requireNonNull(fertilitySnapshot, "fertilitySnapshot");
        Objects.requireNonNull(children, "children");
        children = List.copyOf(children);
        if (fertilitySnapshot.rolledChildCount() != children.size()) {
            throw new IllegalArgumentException("rolledChildCount must equal the resolved child count");
        }
    }

    /** Backward-compatible constructor for count-only callers. */
    public BreedingBirthPlan(int rolledChildCount, @Nonnull List<PlannedChild> children) {
        this(BreedingFertilitySnapshot.countOnly(rolledChildCount), children);
    }

    /** Creates a plan whose pre-rolled result is the supplied resolved child list size. */
    @Nonnull
    public static BreedingBirthPlan of(@Nonnull List<PlannedChild> children) {
        Objects.requireNonNull(children, "children");
        return new BreedingBirthPlan(children.size(), children);
    }

    /** Returns the immutable result of the one fertility roll. */
    public int rolledChildCount() {
        return fertilitySnapshot.rolledChildCount();
    }

    /** Returns whether the pre-rolled fertility result naturally produced no children. */
    public boolean isNaturallyEmpty() {
        return fertilitySnapshot.rolledChildCount() == 0;
    }
}
