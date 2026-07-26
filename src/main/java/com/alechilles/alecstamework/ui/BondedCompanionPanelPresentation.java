package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Complete durable profile presentation retained with every bonded card action. */
public record BondedCompanionPanelPresentation(
        @Nonnull String profileId,
        @Nonnull String rosterId,
        long revision,
        @Nullable String displayName,
        @Nullable String species,
        @Nullable String gender,
        @Nullable String rolePresentation,
        @Nonnull Map<String, String> attributes,
        @Nonnull Map<String, String> extensions,
        @Nonnull BondedCompanionStatusPresentation status,
        @Nullable BondedCompanionReviveQuote reviveQuote
) {
    public BondedCompanionPanelPresentation {
        profileId = required(profileId, "profileId");
        rosterId = required(rosterId, "rosterId");
        displayName = normalize(displayName);
        species = normalize(species);
        gender = normalize(gender);
        rolePresentation = normalize(rolePresentation);
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        extensions = Map.copyOf(Objects.requireNonNull(extensions, "extensions"));
        status = Objects.requireNonNull(status, "status");
        if (revision < 0L) throw new IllegalArgumentException("revision cannot be negative");
    }

    private static String required(String value, String field) {
        String normalized = normalize(Objects.requireNonNull(value, field));
        if (normalized == null) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
