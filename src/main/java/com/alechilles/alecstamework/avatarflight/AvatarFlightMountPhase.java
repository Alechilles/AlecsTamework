package com.alechilles.alecstamework.avatarflight;

import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable lifecycle phase shared by the rider and source-NPC session components. */
public enum AvatarFlightMountPhase {
    PREPARING,
    ACTIVE,
    RESTORING;

    @Nonnull
    public static AvatarFlightMountPhase parse(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return PREPARING;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PREPARING;
        }
    }
}
