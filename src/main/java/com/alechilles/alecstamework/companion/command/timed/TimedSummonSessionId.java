package com.alechilles.alecstamework.companion.command.timed;

import java.util.UUID;
import javax.annotation.Nonnull;

/** Stable identity for one finite or unlimited active summon lease. */
public record TimedSummonSessionId(@Nonnull UUID value)
        implements Comparable<TimedSummonSessionId> {
    public TimedSummonSessionId {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Timed summon session ID is required"
            );
        }
    }

    @Nonnull
    public static TimedSummonSessionId parse(@Nonnull String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Timed summon session text is required"
            );
        }
        return new TimedSummonSessionId(
                UUID.fromString(value.trim())
        );
    }

    @Override
    public int compareTo(TimedSummonSessionId other) {
        if (other == null) {
            throw new NullPointerException(
                    "Other timed summon session is required"
            );
        }
        return value.compareTo(other.value());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

