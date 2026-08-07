package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Holds proxy ownership and refresh-loop lifecycle for one exact world instance.
 */
final class SpawnBeaconVisualizationWorldState {
    private final World world;
    private final Store<EntityStore> store;
    private final Map<UUID, Ref<EntityStore>> proxies = new HashMap<>();
    private final Set<UUID> warnedSources = new HashSet<>();
    private boolean active = true;
    private boolean loopStarted;

    SpawnBeaconVisualizationWorldState(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store) {
        this.world = world;
        this.store = store;
    }

    World world() {
        return world;
    }

    Store<EntityStore> store() {
        return store;
    }

    Map<UUID, Ref<EntityStore>> proxies() {
        return proxies;
    }

    Set<UUID> warnedSources() {
        return warnedSources;
    }

    synchronized boolean active() {
        return active;
    }

    synchronized boolean startLoop() {
        if (!active || loopStarted) {
            return false;
        }
        loopStarted = true;
        return true;
    }

    synchronized void deactivate() {
        active = false;
    }

    void clearOwnership() {
        proxies.clear();
        warnedSources.clear();
    }

    /** Identity key prevents stale state reuse when a same-name world is recreated. */
    static final class Key {
        private final World world;

        Key(@Nonnull World world) {
            this.world = world;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.world == world;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(world);
        }
    }
}
