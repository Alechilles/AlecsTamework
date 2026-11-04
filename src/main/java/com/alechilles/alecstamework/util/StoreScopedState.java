package com.alechilles.alecstamework.util;

import com.hypixel.hytale.component.Store;
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
            T state = statesByStore.get(store);
            if (state == null) {
                // States often hold Refs, which reference their store, so the weak keys of a removed
                // world are never collected. Drop shut-down stores when a new one first appears.
                statesByStore.keySet().removeIf(key -> key instanceof Store<?> s && s.isShutdown());
                state = factory.get();
                statesByStore.put(store, state);
            }
            return state;
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
