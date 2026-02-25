package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.actions.PassiveBreedingSweepService;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Runs coarse passive breeding sweeps on an interval instead of evaluating every tick.
 */
public final class CompanionPassiveBreedingSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 30_000L;

    private final PassiveBreedingSweepService sweepService;
    private long nextSweepAtMs;

    public CompanionPassiveBreedingSystem() {
        this.sweepService = new PassiveBreedingSweepService();
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long nowMs = System.currentTimeMillis();
        if (nowMs < nextSweepAtMs) {
            return;
        }
        nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;
        sweepService.runSweep(store, nowMs);
    }
}
