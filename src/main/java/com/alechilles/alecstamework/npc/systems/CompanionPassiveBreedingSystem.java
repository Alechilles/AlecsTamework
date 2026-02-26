package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.actions.PassiveBreedingSweepService;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runs coarse passive breeding sweeps on an interval instead of evaluating every tick.
 */
public final class CompanionPassiveBreedingSystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 30_000L;

    private final PassiveBreedingSweepService sweepService;
    private long nextSweepAtMs;
    private long lastSchedulerNowMs;

    public CompanionPassiveBreedingSystem() {
        this.sweepService = new PassiveBreedingSweepService();
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        SweepRuntimeSettings runtimeSettings = resolveRuntimeSettings();
        long schedulerNowMs = resolveSchedulerNowMs(store, runtimeSettings.basis());
        if (lastSchedulerNowMs > 0L && schedulerNowMs < lastSchedulerNowMs) {
            nextSweepAtMs = 0L;
        }
        lastSchedulerNowMs = schedulerNowMs;
        if (schedulerNowMs < nextSweepAtMs) {
            return;
        }
        nextSweepAtMs = schedulerNowMs + resolveIntervalMs(runtimeSettings, store);
        sweepService.runSweep(store, BreedingTimeService.resolveCurrentTimeMs(store));
    }

    @Nonnull
    private static SweepRuntimeSettings resolveRuntimeSettings() {
        int intervalSeconds = (int) Math.max(1L, SWEEP_INTERVAL_MS / 1000L);
        @Nullable TwBreedingConfig.TimerBasis basis = null;
        DefaultAssetMap<String, TwBreedingConfig> assetMap = TwBreedingConfig.getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null || assetMap.getAssetMap().isEmpty()) {
            return new SweepRuntimeSettings(intervalSeconds, TwBreedingConfig.TimerBasis.REAL_TIME);
        }
        for (Map.Entry<String, TwBreedingConfig> entry : assetMap.getAssetMap().entrySet()) {
            TwBreedingConfig config = entry.getValue();
            if (config == null || !config.isEnabled()) {
                continue;
            }
            TwBreedingConfig.PassiveBreedingSettings passive = config.getPassiveBreeding();
            if (passive == null || !passive.isEnabled()) {
                continue;
            }
            intervalSeconds = Math.min(intervalSeconds, passive.getSweepIntervalSeconds());
            if (basis == null) {
                basis = passive.getTimerBasis();
            } else if (basis != passive.getTimerBasis()) {
                basis = TwBreedingConfig.TimerBasis.REAL_TIME;
            }
        }
        if (basis == null) {
            basis = TwBreedingConfig.TimerBasis.REAL_TIME;
        }
        return new SweepRuntimeSettings(Math.max(1, intervalSeconds), basis);
    }

    private static long resolveSchedulerNowMs(@Nonnull Store<EntityStore> store,
                                              @Nonnull TwBreedingConfig.TimerBasis basis) {
        return basis == TwBreedingConfig.TimerBasis.REAL_TIME
                ? System.currentTimeMillis()
                : BreedingTimeService.resolveCurrentTimeMs(store);
    }

    private static long resolveIntervalMs(@Nonnull SweepRuntimeSettings settings,
                                          @Nonnull Store<EntityStore> store) {
        if (settings.basis() == TwBreedingConfig.TimerBasis.REAL_TIME) {
            return Math.max(1L, settings.intervalSeconds() * 1000L);
        }
        long gameDurationMs = BreedingTimeService.toGameDurationMs(
                settings.intervalSeconds(),
                TwBreedingConfig.TimerBasis.REAL_TIME,
                store
        );
        if (gameDurationMs <= 0L) {
            return Math.max(1L, settings.intervalSeconds() * 1000L);
        }
        return gameDurationMs;
    }

    private record SweepRuntimeSettings(int intervalSeconds, TwBreedingConfig.TimerBasis basis) {
    }
}
