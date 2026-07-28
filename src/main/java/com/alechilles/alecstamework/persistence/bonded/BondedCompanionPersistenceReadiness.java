package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionAvailability;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable startup and diagnostic state for only the bonded persistence authority. */
public record BondedCompanionPersistenceReadiness(
        @Nonnull BondedCompanionAvailability availability,
        @Nonnull String diagnosticCode
) {
    public BondedCompanionPersistenceReadiness {
        availability = Objects.requireNonNull(availability, "availability");
        diagnosticCode = requireText(diagnosticCode, "diagnosticCode");
    }

    /** Creates a ready diagnostic without affecting generic persistence state. */
    @Nonnull
    public static BondedCompanionPersistenceReadiness ready() {
        return new BondedCompanionPersistenceReadiness(
                BondedCompanionAvailability.availableNow(),
                "bonded-persistence-ready"
        );
    }

    /** Creates an unavailable diagnostic without affecting generic persistence state. */
    @Nonnull
    public static BondedCompanionPersistenceReadiness failed(
            @Nonnull String code
    ) {
        String normalized = requireText(code, "code");
        return new BondedCompanionPersistenceReadiness(
                BondedCompanionAvailability.unavailable(normalized),
                normalized
        );
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
