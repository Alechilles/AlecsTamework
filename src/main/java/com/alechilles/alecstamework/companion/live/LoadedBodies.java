package com.alechilles.alecstamework.companion.live;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runtime-only map from profile id to its loaded body reference (spec 6.7). A role change
 * removes a body with UNLOAD and re-adds it with LOAD; removal only unregisters the exact
 * reference that is registered, so a late removal never drops the new body.
 */
public final class LoadedBodies<R> {
    private final Map<UUID, R> bodies = new ConcurrentHashMap<>();

    @Nullable
    public R get(@Nonnull UUID profileId) {
        return bodies.get(profileId);
    }

    public void put(@Nonnull UUID profileId, @Nonnull R body) {
        bodies.put(profileId, body);
    }

    /** Unregisters {@code body} only if it is the registered body. Returns true when it was. */
    public boolean removeIfSame(@Nonnull UUID profileId, @Nonnull R body) {
        return bodies.remove(profileId, body);
    }

    /** Visits every entry. The map is concurrent, so entries changed during the visit may or may not be seen. */
    public void forEach(@Nonnull BiConsumer<UUID, R> action) {
        bodies.forEach(action);
    }

    /** Drops every entry matching {@code filter}, for example all bodies of a removed world. */
    public void removeIf(@Nonnull BiPredicate<UUID, R> filter) {
        bodies.entrySet().removeIf(e -> filter.test(e.getKey(), e.getValue()));
    }
}
