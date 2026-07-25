package com.alechilles.alecstamework.companion.lifecycle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Validated durable location of one canonical companion.
 *
 * @param kind location category
 * @param key stable category-specific locator
 * @param worldKey stable world key, used only by live entities
 */
public record LifecycleLocation(@Nonnull LifecycleLocationKind kind,
                                @Nullable String key,
                                @Nullable String worldKey) {
    public LifecycleLocation {
        if (kind == null) {
            throw new IllegalArgumentException("Lifecycle location kind is required");
        }
        key = normalize(key);
        worldKey = normalize(worldKey);
        validate(kind, key, worldKey);
    }

    /** Creates a live-entity location. */
    public static LifecycleLocation liveEntity(@Nonnull String entityKey, @Nonnull String worldKey) {
        return new LifecycleLocation(LifecycleLocationKind.LIVE_ENTITY, entityKey, worldKey);
    }

    /** Creates a keyed non-world location. */
    public static LifecycleLocation keyed(@Nonnull LifecycleLocationKind kind, @Nonnull String key) {
        return new LifecycleLocation(kind, key, null);
    }

    /** Creates the canonical no-location value. */
    public static LifecycleLocation none() {
        return new LifecycleLocation(LifecycleLocationKind.NONE, null, null);
    }

    /** Creates a fail-closed unresolved location. */
    public static LifecycleLocation unresolved() {
        return new LifecycleLocation(LifecycleLocationKind.UNRESOLVED, null, null);
    }

    private static void validate(LifecycleLocationKind kind, String key, String worldKey) {
        switch (kind) {
            case LIVE_ENTITY -> {
                requireKey(key, "Live entity key");
                requireKey(worldKey, "Live entity world key");
            }
            case CAPTURE_ITEM, COOP_SLOT, COMMAND_ROSTER, PROVISIONING -> {
                requireKey(key, kind + " key");
                if (worldKey != null) {
                    throw new IllegalArgumentException(kind + " cannot carry a world key");
                }
            }
            case NONE, UNRESOLVED -> {
                if (key != null || worldKey != null) {
                    throw new IllegalArgumentException(kind + " cannot carry location keys");
                }
            }
        }
    }

    private static void requireKey(String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
