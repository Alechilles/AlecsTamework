package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Typed payload for one final combat damage packet. */
public record CombatDamageActivityView(
        @Nonnull ActivityHeader header,
        @Nonnull CombatParticipantView source,
        @Nonnull CombatParticipantView target,
        double finalDamage,
        @Nonnull String damageType,
        @Nullable CompanionXpOutcomeView sourceXpOutcome,
        @Nullable CompanionXpOutcomeView targetXpOutcome
) implements ActivityView {
    public CombatDamageActivityView {
        header = Objects.requireNonNull(header, "header");
        source = Objects.requireNonNull(source, "source");
        target = Objects.requireNonNull(target, "target");
        damageType = requireText(damageType, "damageType");
        if (!Double.isFinite(finalDamage) || finalDamage < 0.0) {
            throw new IllegalArgumentException("finalDamage must be finite and non-negative.");
        }
    }

    public CombatDamageActivityView(
            @Nonnull ActivityHeader header,
            @Nonnull CombatParticipantView source,
            @Nonnull CombatParticipantView target,
            double finalDamage,
            @Nonnull String damageType
    ) {
        this(header, source, target, finalDamage, damageType, null, null);
    }

    @Override
    @Nonnull
    public ActivityDomain domain() {
        return ActivityDomain.COMBAT;
    }

    @Override
    @Nonnull
    public CombatDamageActivityView withHeader(@Nonnull ActivityHeader nextHeader) {
        return new CombatDamageActivityView(
                nextHeader, source, target, finalDamage, damageType,
                sourceXpOutcome, targetXpOutcome);
    }

    /** Alias for the XP transition caused by source-side damage. */
    @Nullable
    public CompanionXpOutcomeView dealtXpOutcome() {
        return sourceXpOutcome;
    }

    /** Alias for the XP transition caused by target-side damage. */
    @Nullable
    public CompanionXpOutcomeView takenXpOutcome() {
        return targetXpOutcome;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
