package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Post-commit event for lease start, availability, expiry, dismissal, or recovery convergence. */
public record CommandTimedSummoningChangedEvent(
        @Nullable CommandTimedSummoningView previous,
        @Nonnull CommandTimedSummoningView current,
        @Nonnull String reason,
        long occurredAtMs
) {
    public CommandTimedSummoningChangedEvent {
        Objects.requireNonNull(current, "current");
        reason = Objects.requireNonNull(reason, "reason");
        if (occurredAtMs < 0L) throw new IllegalArgumentException("occurredAtMs must be non-negative.");
    }
}
