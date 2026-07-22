package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Expires capture VFX sessions whose player disconnected or changed worlds. */
public final class CaptureChannelSessionCleanupSystem extends TickingSystem<EntityStore> {
    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        CaptureChannelVfxSystem.sweepOrphanedSessions(store, System.currentTimeMillis());
    }
}
