package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable capacity boundaries charged by one active breeding reservation.
 *
 * <p>A nonpositive nearby radius means the authored nearby cap is unlimited. Claim and player
 * scopes are absent when those independent constraints do not apply.
 */
public record BreedingReservationScope(double nearbyRadius,
                                       @Nullable BreedingClaimCapacityScope claimScope,
                                       @Nonnull List<BreedingPlayerCapacityScope> playerScopes) {
    public BreedingReservationScope {
        if (!Double.isFinite(nearbyRadius) || nearbyRadius < 0.0) {
            throw new IllegalArgumentException("nearbyRadius must be finite and nonnegative");
        }
        Objects.requireNonNull(playerScopes, "playerScopes");
        TreeSet<BreedingPlayerCapacityScope> sorted = new TreeSet<>();
        for (BreedingPlayerCapacityScope playerScope : playerScopes) {
            sorted.add(Objects.requireNonNull(playerScope, "playerScope"));
        }
        playerScopes = List.copyOf(new ArrayList<>(sorted));
    }

    /** Compatibility scope with no active nearby, claim, or player capacity boundary. */
    @Nonnull
    public static BreedingReservationScope unscoped() {
        return new BreedingReservationScope(0.0, null, List.of());
    }
}
