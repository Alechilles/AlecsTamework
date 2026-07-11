package com.alechilles.alecstamework.npc.breeding;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.annotation.Nonnull;

/** Immutable exact child-slot reservation owned by one breeding birth job. */
public record BreedingBirthReservation(@Nonnull BreedingReservationScope scope,
                                       @Nonnull Map<String, Integer> countsByPopulationType) {
    public BreedingBirthReservation {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(countsByPopulationType, "countsByPopulationType");
        TreeMap<String, Integer> normalized = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : countsByPopulationType.entrySet()) {
            String populationType = PlannedChild.canonicalPopulationType(entry.getKey());
            Integer count = Objects.requireNonNull(entry.getValue(), "reservation count");
            if (count <= 0) {
                throw new IllegalArgumentException("Reservation counts must be positive");
            }
            normalized.merge(populationType, count, BreedingBirthReservation::saturatingAdd);
        }
        countsByPopulationType = Collections.unmodifiableMap(normalized);
    }

    /** Returns the exact number of outstanding child slots. */
    public int totalChildren() {
        int total = 0;
        for (int count : countsByPopulationType.values()) {
            total = saturatingAdd(total, count);
        }
        return total;
    }

    public boolean isEmpty() {
        return countsByPopulationType.isEmpty();
    }

    @Nonnull
    public BreedingBirthReservation emptyCopy() {
        return new BreedingBirthReservation(scope, Map.of());
    }

    private static int saturatingAdd(int left, int right) {
        long sum = (long) left + (long) right;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
