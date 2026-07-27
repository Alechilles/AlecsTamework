package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable bonded lifecycle and primary-action presentation for one card. */
public record BondedCompanionStatusPresentation(
        @Nonnull BondedCompanionStateView state,
        @Nonnull Action action,
        boolean actionEnabled,
        @Nullable BondedCompanionActionBlockReason blockReason,
        @Nullable String unavailableReason,
        long cooldownRemainingMs
) {
    public BondedCompanionStatusPresentation {
        state = Objects.requireNonNull(state, "state");
        action = Objects.requireNonNull(action, "action");
        if (blockReason == BondedCompanionActionBlockReason.NONE) {
            blockReason = null;
        }
        unavailableReason = normalize(unavailableReason);
        if (cooldownRemainingMs < 0L) {
            throw new IllegalArgumentException("cooldownRemainingMs cannot be negative");
        }
    }

    /** Retains the pre-typed presentation constructor for downstream callers. */
    public BondedCompanionStatusPresentation(
            BondedCompanionStateView state,
            Action action,
            boolean actionEnabled,
            @Nullable String unavailableReason,
            long cooldownRemainingMs) {
        this(state, action, actionEnabled, null, unavailableReason,
                cooldownRemainingMs);
    }

    public enum Action { SUMMON, DISMISS, REVIVE, NONE }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
