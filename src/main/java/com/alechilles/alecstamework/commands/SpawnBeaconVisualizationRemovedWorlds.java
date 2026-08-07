package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/**
 * Remembers removed world identities without retaining their world/store graphs.
 */
final class SpawnBeaconVisualizationRemovedWorlds {
    private final ReferenceQueue<World> collectedWorlds = new ReferenceQueue<>();
    private final Set<WeakIdentityKey> identities = new HashSet<>();

    boolean contains(@Nonnull World world) {
        purgeCollected();
        return identities.contains(new WeakIdentityKey(world, null));
    }

    void add(@Nonnull World world) {
        purgeCollected();
        identities.add(new WeakIdentityKey(world, collectedWorlds));
    }

    void clear() {
        identities.clear();
        while (collectedWorlds.poll() != null) {
            // Drain queued keys so the service releases all lifecycle bookkeeping.
        }
    }

    private void purgeCollected() {
        WeakIdentityKey collected;
        while ((collected = (WeakIdentityKey) collectedWorlds.poll()) != null) {
            identities.remove(collected);
        }
    }

    private static final class WeakIdentityKey extends WeakReference<World> {
        private final int identityHash;

        private WeakIdentityKey(World world, ReferenceQueue<World> queue) {
            super(world, queue);
            identityHash = System.identityHashCode(world);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            World world = get();
            return world != null
                    && other instanceof WeakIdentityKey key
                    && world == key.get();
        }

        @Override
        public int hashCode() {
            return identityHash;
        }
    }
}
