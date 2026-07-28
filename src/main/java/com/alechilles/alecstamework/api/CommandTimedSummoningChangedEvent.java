package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Post-commit event for lease start, availability, expiry, dismissal, or recovery convergence. */
public record CommandTimedSummoningChangedEvent(
        @Nullable CommandTimedSummoningView previous,
        @Nonnull CommandTimedSummoningView current,
        @Nonnull String reason,
        long occurredAtMs,
        long emittedAtMs
) implements TameworkEvent {
    public CommandTimedSummoningChangedEvent {
        Objects.requireNonNull(current, "current");
        reason = Objects.requireNonNull(reason, "reason");
    }

    /** Source-compatible constructor for callers that emit synchronously. */
    public CommandTimedSummoningChangedEvent(
            CommandTimedSummoningView previous,
            CommandTimedSummoningView current,
            String reason,
            long occurredAtMs
    ) {
        this(previous, current, reason, occurredAtMs, occurredAtMs);
    }
}
