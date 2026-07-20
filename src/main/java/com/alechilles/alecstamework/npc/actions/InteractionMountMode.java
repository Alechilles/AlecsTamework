package com.alechilles.alecstamework.npc.actions;

import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable role-parameter mapping for Tamework mount implementations. */
enum InteractionMountMode {
    NATIVE,
    TAMEWORK_RIDE,
    TAMEWORK_MOUNTED_GLIDE,
    TAMEWORK_AVATAR_FLIGHT;

    @Nonnull
    static InteractionMountMode parse(@Nullable String value) {
        if (value == null || value.isBlank()) return NATIVE;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "tameworkride" -> TAMEWORK_RIDE;
            case "tameworkmountedglide" -> TAMEWORK_MOUNTED_GLIDE;
            case "tameworkavatarflight" -> TAMEWORK_AVATAR_FLIGHT;
            default -> NATIVE;
        };
    }

    static boolean isKnown(@Nullable String value) {
        return value == null || value.isBlank() || parse(value) != NATIVE
                || "native".equalsIgnoreCase(value.trim());
    }
}
