package com.alechilles.alecstamework.avatarflight;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Identifies avatar-flight sessions created by the currently running server process. */
public final class AvatarFlightRuntimeEpoch {
    private static final String CURRENT = UUID.randomUUID().toString();

    private AvatarFlightRuntimeEpoch() {
    }

    @Nonnull
    public static String current() {
        return CURRENT;
    }

    public static boolean isCurrent(@Nullable String epoch) {
        return epoch != null && CURRENT.equals(epoch.trim());
    }
}
