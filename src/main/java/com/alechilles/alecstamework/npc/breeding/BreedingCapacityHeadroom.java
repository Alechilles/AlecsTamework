package com.alechilles.alecstamework.npc.breeding;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeMap;
import javax.annotation.Nonnull;

/**
 * Immutable live claim and player headroom measured before active breeding reservations.
 *
 * <p>An absent claim value and an omitted player scope are unlimited. The admission service
 * subtracts matching reservations from each finite value before choosing the smallest boundary.
 */
public record BreedingCapacityHeadroom(
        @Nonnull OptionalInt claimHeadroom,
        @Nonnull Map<BreedingPlayerCapacityScope, Integer> playerHeadroomByScope) {
    public BreedingCapacityHeadroom {
        Objects.requireNonNull(claimHeadroom, "claimHeadroom");
        if (claimHeadroom.isPresent() && claimHeadroom.getAsInt() < 0) {
            throw new IllegalArgumentException("claimHeadroom must be nonnegative");
        }
        Objects.requireNonNull(playerHeadroomByScope, "playerHeadroomByScope");
        TreeMap<BreedingPlayerCapacityScope, Integer> normalized = new TreeMap<>();
        for (Map.Entry<BreedingPlayerCapacityScope, Integer> entry : playerHeadroomByScope.entrySet()) {
            BreedingPlayerCapacityScope scope = Objects.requireNonNull(entry.getKey(), "player scope");
            Integer headroom = Objects.requireNonNull(entry.getValue(), "player headroom");
            if (headroom < 0) {
                throw new IllegalArgumentException("player headroom must be nonnegative");
            }
            normalized.put(scope, headroom);
        }
        playerHeadroomByScope = Collections.unmodifiableMap(normalized);
    }

    /** Returns headroom with no finite claim or player boundary. */
    @Nonnull
    public static BreedingCapacityHeadroom unlimited() {
        return new BreedingCapacityHeadroom(OptionalInt.empty(), Map.of());
    }
}
