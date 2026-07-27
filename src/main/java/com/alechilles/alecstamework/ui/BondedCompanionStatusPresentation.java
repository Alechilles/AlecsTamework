package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionStateView;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable bonded lifecycle and primary-action presentation for one card. */
public record BondedCompanionStatusPresentation(
        @Nonnull BondedCompanionStateView state,
        @Nonnull Action action,
        boolean actionEnabled,
        @Nullable String unavailableReason,
        long cooldownRemainingMs
) {
    public BondedCompanionStatusPresentation {
        state = Objects.requireNonNull(state, "state");
        action = Objects.requireNonNull(action, "action");
        unavailableReason = normalize(unavailableReason);
        if (cooldownRemainingMs < 0L) {
            throw new IllegalArgumentException("cooldownRemainingMs cannot be negative");
        }
    }

    public enum Action { SUMMON, DISMISS, REVIVE, NONE }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
