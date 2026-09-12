package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.movement.CompanionFollowFlockService;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Expires only active follow intents; structural work runs on the world after ECS processing. */
public final class CompanionFollowFlockSystem extends TickingSystem<EntityStore> {
    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        CompanionFollowFlockService.get().tick(dt, store);
    }
}
