package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.Instant;
import javax.annotation.Nullable;

/**
 * Resolves game-time "now" and converts breeding config durations into game-time milliseconds.
 *
 * <p>Configured breeding durations stay human-readable (seconds), while runtime values are stored and compared
 * using game-time timestamps so progression behaves consistently across chunk unload/reload and pause flows.
 */
public final class BreedingTimeService {
    private static final int DEFAULT_BASELINE_DAYTIME_SECONDS = WorldTimeResource.DAYTIME_SECONDS;
    private static final int DEFAULT_BASELINE_NIGHTTIME_SECONDS = WorldTimeResource.NIGHTTIME_SECONDS;
    private static final double DEFAULT_BASELINE_RATE = (double) WorldTimeResource.SECONDS_PER_DAY
            / (double) (DEFAULT_BASELINE_DAYTIME_SECONDS + DEFAULT_BASELINE_NIGHTTIME_SECONDS);

    private BreedingTimeService() {
    }

    /**
     * Returns current game-time instant when available, falling back to wall-clock.
     */
    public static Instant resolveCurrentInstant(@Nullable Store<EntityStore> store) {
        WorldTimeResource timeResource = resolveWorldTimeResource(store);
        if (timeResource != null && timeResource.getGameTime() != null) {
            return timeResource.getGameTime();
        }
        return Instant.now();
    }

    /**
     * Returns current game-time epoch-millis when available, falling back to wall-clock epoch-millis.
     */
    public static long resolveCurrentTimeMs(@Nullable Store<EntityStore> store) {
        return resolveCurrentInstant(store).toEpochMilli();
    }

    /**
     * Converts configured seconds into a game-time duration according to breeding timing basis.
     */
    public static long toGameDurationMs(double configuredSeconds,
                                        @Nullable TwBreedingConfig.TimerBasis timerBasis,
                                        @Nullable Store<EntityStore> store) {
        if (!Double.isFinite(configuredSeconds) || configuredSeconds <= 0.0) {
            return 0L;
        }
        TwBreedingConfig.TimerBasis resolvedBasis = timerBasis != null
                ? timerBasis
                : TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED;
        double gameSecondsPerRealSecond = switch (resolvedBasis) {
            case REAL_TIME -> resolveCurrentGameSecondsPerRealSecond(store);
            case WORLD_TIME_SCALED -> resolveBaselineGameSecondsPerRealSecond(store);
        };
        if (!Double.isFinite(gameSecondsPerRealSecond) || gameSecondsPerRealSecond <= 0.0) {
            gameSecondsPerRealSecond = DEFAULT_BASELINE_RATE;
        }
        double gameMillis = configuredSeconds * gameSecondsPerRealSecond * 1000.0;
        if (!Double.isFinite(gameMillis) || gameMillis <= 0.0) {
            return 0L;
        }
        if (gameMillis >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, Math.round(gameMillis));
    }

    /**
     * Returns game-seconds advanced per real second at current world time speed.
     */
    public static double resolveCurrentGameSecondsPerRealSecond(@Nullable Store<EntityStore> store) {
        World world = resolveWorld(store);
        if (world == null) {
            return resolveBaselineGameSecondsPerRealSecond(store);
        }
        return resolveRate(
                world.getDaytimeDurationSeconds(),
                world.getNighttimeDurationSeconds(),
                resolveBaselineGameSecondsPerRealSecond(store)
        );
    }

    /**
     * Returns baseline game-seconds-per-real-second from gameplay world defaults.
     */
    public static double resolveBaselineGameSecondsPerRealSecond(@Nullable Store<EntityStore> store) {
        World world = resolveWorld(store);
        if (world == null
                || world.getGameplayConfig() == null
                || world.getGameplayConfig().getWorldConfig() == null) {
            return DEFAULT_BASELINE_RATE;
        }
        return resolveRate(
                world.getGameplayConfig().getWorldConfig().getDaytimeDurationSeconds(),
                world.getGameplayConfig().getWorldConfig().getNighttimeDurationSeconds(),
                DEFAULT_BASELINE_RATE
        );
    }

    @Nullable
    private static World resolveWorld(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null) {
            return null;
        }
        return store.getExternalData().getWorld();
    }

    @Nullable
    private static WorldTimeResource resolveWorldTimeResource(@Nullable Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        return store.getResource(WorldTimeResource.getResourceType());
    }

    private static double resolveRate(int daytimeSeconds, int nighttimeSeconds, double fallback) {
        long totalDuration = (long) daytimeSeconds + (long) nighttimeSeconds;
        if (totalDuration <= 0L) {
            return fallback;
        }
        return (double) WorldTimeResource.SECONDS_PER_DAY / (double) totalDuration;
    }
}
