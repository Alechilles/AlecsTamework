package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.ambient.AmbientHerdCoordinator;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Advances a bounded leader registry; it never queries the world's whole NPC population. */
public final class AmbientHerdSystem extends TickingSystem<EntityStore> {
    private final AmbientHerdCoordinator coordinator;

    public AmbientHerdSystem(AmbientHerdCoordinator coordinator) { this.coordinator = coordinator; }

    @Override
    public void tick(float dt, int systemIndex, Store<EntityStore> store) {
        coordinator.tick(store, System.nanoTime() / 1_000_000L);
    }
}
