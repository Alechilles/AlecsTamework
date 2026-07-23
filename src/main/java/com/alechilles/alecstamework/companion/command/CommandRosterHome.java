package com.alechilles.alecstamework.companion.command;

import javax.annotation.Nonnull;

/** Complete world-qualified command home position. */
public record CommandRosterHome(
        @Nonnull String worldKey,
        double x,
        double y,
        double z
) {
    public CommandRosterHome {
        if (worldKey == null || worldKey.isBlank()
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)) {
            throw new IllegalArgumentException(
                    "Finite world-qualified command home is required"
            );
        }
        worldKey = worldKey.trim();
    }
}
