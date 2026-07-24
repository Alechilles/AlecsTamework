package com.alechilles.alecstamework.items.persistence;

import javax.annotation.Nullable;

/**
 * Immutable spawner presentation evidence safe to carry across persistence threads.
 *
 * <p>The world and actor are resolved again by the completion dispatcher. Only copied position
 * coordinates and optional asset IDs cross the asynchronous boundary.</p>
 */
public record SpawnerPublishedEffect(
        double x,
        double y,
        double z,
        @Nullable String particleSystem,
        @Nullable String soundEvent
) {
    public SpawnerPublishedEffect {
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)) {
            throw new IllegalArgumentException(
                    "Spawner effect position must be finite"
            );
        }
        particleSystem = normalize(particleSystem);
        soundEvent = normalize(soundEvent);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
