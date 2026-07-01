package com.alechilles.alecstamework.util;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Maintains mutable runtime state per live Hytale store instead of on a shared singleton system.
 *
 * <p>Hytale registers one system instance globally, so world stores can tick the same system object
 * concurrently. Use this helper for small cross-tick state objects that must be isolated per store.
 */
public final class StoreScopedState<T> {
    private final Supplier<T> factory;
    private final Map<Object, T> statesByStore = new WeakHashMap<>();

    public StoreScopedState(@Nonnull Supplier<T> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Nonnull
    public T get(@Nonnull Object store) {
        Objects.requireNonNull(store, "store");
        synchronized (statesByStore) {
            return statesByStore.computeIfAbsent(store, ignored -> factory.get());
        }
    }

    public void remove(@Nonnull Object store) {
        Objects.requireNonNull(store, "store");
        synchronized (statesByStore) {
            statesByStore.remove(store);
        }
    }

    int sizeForTests() {
        synchronized (statesByStore) {
            return statesByStore.size();
        }
    }
}
