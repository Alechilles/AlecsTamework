package com.alechilles.alecstamework.npc.breeding;

import javax.annotation.Nonnull;

/** Immutable world-position snapshot used for capacity filtering and delayed birth placement. */
public record BreedingBirthAnchor(double x, double y, double z) {
    public BreedingBirthAnchor {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Breeding birth anchor coordinates must be finite");
        }
    }

    /** Compatibility anchor for legacy job factories that did not retain placement. */
    @Nonnull
    public static BreedingBirthAnchor origin() {
        return new BreedingBirthAnchor(0.0, 0.0, 0.0);
    }

    /** Returns squared distance without allocating a mutable vector. */
    public double distanceSquared(@Nonnull BreedingBirthAnchor other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
