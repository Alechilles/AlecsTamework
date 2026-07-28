package com.alechilles.alecstamework.companion.bonded;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable family scope used to fence a bonded summon transaction. */
public record BondedCompanionActiveCapacity(
        @Nonnull String familyId,
        int maximumActive
) {
    public BondedCompanionActiveCapacity {
        familyId = text(familyId);
        if (maximumActive < 0) {
            throw new IllegalArgumentException("negative maximumActive");
        }
    }

    private static String text(String value) {
        String normalized = Objects.requireNonNull(value, "familyId").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("familyId is blank");
        }
        return normalized;
    }
}
