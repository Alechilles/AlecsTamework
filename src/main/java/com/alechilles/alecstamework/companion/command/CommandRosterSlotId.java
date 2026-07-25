package com.alechilles.alecstamework.companion.command;

import java.util.UUID;
import javax.annotation.Nonnull;

/** Stable opaque command-roster slot and lifecycle location identity. */
public record CommandRosterSlotId(@Nonnull UUID value)
        implements Comparable<CommandRosterSlotId> {
    public CommandRosterSlotId {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Command roster slot ID is required"
            );
        }
    }

    @Nonnull
    public static CommandRosterSlotId parse(@Nonnull String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Command roster slot text is required"
            );
        }
        return new CommandRosterSlotId(UUID.fromString(value.trim()));
    }

    @Override
    public int compareTo(CommandRosterSlotId other) {
        if (other == null) {
            throw new NullPointerException(
                    "Other command roster slot is required"
            );
        }
        return value.compareTo(other.value());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

