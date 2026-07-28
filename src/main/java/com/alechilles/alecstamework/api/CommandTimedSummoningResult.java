package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable result returned by public timed Summon/Dismiss operations. */
public record CommandTimedSummoningResult(@Nonnull Status status,
                                          @Nonnull String reason,
                                          @Nullable CommandTimedSummoningView session) {
    public CommandTimedSummoningResult {
        Objects.requireNonNull(status, "status");
        reason = Objects.requireNonNull(reason, "reason");
    }

    public boolean successful() {
        return status == Status.SUCCESS || status == Status.IDEMPOTENT;
    }

    public enum Status {
        SUCCESS,
        IDEMPOTENT,
        DENIED,
        COOLDOWN,
        RECOVERING,
        UNAVAILABLE
    }
}
