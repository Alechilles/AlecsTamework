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
public record BreedingBirthPlan(int rolledChildCount, @Nonnull List<PlannedChild> children) {
    private static final int MAX_CHILDREN_PER_ROLL = 4;

    public BreedingBirthPlan {
        Objects.requireNonNull(children, "children");
        if (rolledChildCount < 0 || rolledChildCount > MAX_CHILDREN_PER_ROLL) {
            throw new IllegalArgumentException("rolledChildCount must be between 0 and 4");
        }
        children = List.copyOf(children);
        if (rolledChildCount != children.size()) {
            throw new IllegalArgumentException("rolledChildCount must equal the resolved child count");
        }
    }

    /** Creates a plan whose pre-rolled result is the supplied resolved child list size. */
    @Nonnull
    public static BreedingBirthPlan of(@Nonnull List<PlannedChild> children) {
        Objects.requireNonNull(children, "children");
        return new BreedingBirthPlan(children.size(), children);
    }

    /** Returns whether the pre-rolled fertility result naturally produced no children. */
    public boolean isNaturallyEmpty() {
        return rolledChildCount == 0;
    }
}
