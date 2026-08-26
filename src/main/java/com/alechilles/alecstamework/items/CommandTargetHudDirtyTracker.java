package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Tracks contributor-driven target HUD refresh requests. */
final class CommandTargetHudDirtyTracker {
    private final Object lock = new Object();
    private final Map<UUID, Long> dirtyVersions = new HashMap<>();
    private final Map<UUID, Long> presentedVersions = new HashMap<>();
    private final AtomicLong nextVersion = new AtomicLong();
    private final BiConsumer<Store<EntityStore>, UUID> invalidationSink;

    CommandTargetHudDirtyTracker(
            @Nonnull BiConsumer<Store<EntityStore>, UUID> invalidationSink
    ) {
        this.invalidationSink = invalidationSink;
    }

    void markDirty(@Nullable Store<EntityStore> store, @Nonnull UUID playerUuid) {
        synchronized (lock) {
            dirtyVersions.put(playerUuid, nextVersion.incrementAndGet());
        }
        invalidate(store, playerUuid);
    }

    long version(@Nonnull UUID playerUuid) {
        synchronized (lock) {
            return dirtyVersions.getOrDefault(playerUuid, 0L);
        }
    }

    boolean pending(@Nonnull UUID playerUuid) {
        synchronized (lock) {
            return dirtyVersions.getOrDefault(playerUuid, 0L)
                    > presentedVersions.getOrDefault(playerUuid, 0L);
        }
    }

    void markPresented(@Nonnull UUID playerUuid, long version) {
        synchronized (lock) {
            long current = dirtyVersions.getOrDefault(playerUuid, 0L);
            if (version > 0L && current == version) {
                presentedVersions.put(playerUuid, version);
            }
        }
    }

    void clear(@Nonnull UUID playerUuid) {
        synchronized (lock) {
            dirtyVersions.remove(playerUuid);
            presentedVersions.remove(playerUuid);
        }
    }

    void clearAll() {
        synchronized (lock) {
            dirtyVersions.clear();
            presentedVersions.clear();
        }
    }

    void invalidate(@Nullable Store<EntityStore> store, @Nonnull UUID playerUuid) {
        try {
            invalidationSink.accept(store, playerUuid);
        } catch (RuntimeException | LinkageError ignored) {
            // The target scanner remains authoritative when a dirty signal fails.
        }
    }
}
