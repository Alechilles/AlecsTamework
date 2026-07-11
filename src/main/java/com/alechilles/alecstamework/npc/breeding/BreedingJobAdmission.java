package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable outstanding child admission and its exact matching reservation. */
public record BreedingJobAdmission(@Nonnull List<PlannedChild> children,
                                   @Nonnull BreedingBirthReservation reservation) {
    public BreedingJobAdmission {
        Objects.requireNonNull(children, "children");
        children = List.copyOf(children);
        Objects.requireNonNull(reservation, "reservation");
        if (!countByPopulationType(children).equals(reservation.countsByPopulationType())) {
            throw new IllegalArgumentException("Reservation counts must exactly match admitted children");
        }
    }

    /** Builds exact counts from the supplied ordered child admission. */
    @Nonnull
    public static BreedingJobAdmission of(@Nonnull List<PlannedChild> children,
                                          @Nonnull BreedingReservationScope scope) {
        Objects.requireNonNull(children, "children");
        return new BreedingJobAdmission(
                children,
                new BreedingBirthReservation(scope, countByPopulationType(children))
        );
    }

    /** Empty admission that preserves the same reservation scopes. */
    @Nonnull
    public BreedingJobAdmission emptyCopy() {
        return new BreedingJobAdmission(List.of(), reservation.emptyCopy());
    }

    /**
     * Returns a shrink-only admission. Expansion and reordering are rejected.
     */
    @Nonnull
    BreedingJobAdmission shrinkTo(@Nonnull List<PlannedChild> retainedChildren) {
        Objects.requireNonNull(retainedChildren, "retainedChildren");
        List<PlannedChild> retained = List.copyOf(retainedChildren);
        if (!isOrderedSubsequence(children, retained)) {
            throw new IllegalArgumentException("Retained children must be an ordered subsequence");
        }
        return of(retained, reservation.scope());
    }

    /** Releases the first outstanding slot matching the exact planned child. */
    @Nonnull
    BreedingJobAdmission release(@Nonnull PlannedChild child) {
        Objects.requireNonNull(child, "child");
        ArrayList<PlannedChild> remaining = new ArrayList<>(children);
        int index = remaining.indexOf(child);
        if (index < 0) {
            throw new IllegalArgumentException("Planned child is not reserved by this admission");
        }
        remaining.remove(index);
        return of(remaining, reservation.scope());
    }

    static boolean isOrderedSubsequence(List<PlannedChild> source, List<PlannedChild> candidate) {
        int candidateIndex = 0;
        for (PlannedChild child : source) {
            if (candidateIndex < candidate.size() && child.equals(candidate.get(candidateIndex))) {
                candidateIndex++;
            }
        }
        return candidateIndex == candidate.size();
    }

    private static Map<String, Integer> countByPopulationType(List<PlannedChild> children) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PlannedChild child : children) {
            PlannedChild plannedChild = Objects.requireNonNull(child, "plannedChild");
            counts.merge(plannedChild.populationType(), 1, Math::addExact);
        }
        return counts;
    }
}
