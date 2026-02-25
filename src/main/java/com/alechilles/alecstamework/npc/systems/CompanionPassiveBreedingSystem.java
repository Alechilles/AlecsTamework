package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.actions.PassiveBreedingSweepService;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
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
        long nowMs = BreedingTimeService.resolveCurrentTimeMs(store);
        if (nowMs < nextSweepAtMs) {
            return;
        }
        long intervalGameMs = BreedingTimeService.toGameDurationMs(
                SWEEP_INTERVAL_MS / 1000.0,
                com.alechilles.alecstamework.config.assets.TwBreedingConfig.TimerBasis.REAL_TIME,
                store
        );
        if (intervalGameMs <= 0L) {
            intervalGameMs = SWEEP_INTERVAL_MS;
        }
        nextSweepAtMs = nowMs + intervalGameMs;
        sweepService.runSweep(store, nowMs);
    }
}
