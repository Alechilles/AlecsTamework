package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.api.TameworkProgressionTimeScales;
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
     * Adds a signed timestamp and duration without wrapping across the {@code long} boundary.
     */
    public static long saturatingAdd(long timestampMs, long durationMs) {
        try {
            return Math.addExact(timestampMs, durationMs);
        } catch (ArithmeticException ignored) {
            return durationMs >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /**
     * Subtracts two signed timestamps without wrapping across the {@code long} boundary.
     */
    public static long saturatingSubtract(long leftMs, long rightMs) {
        try {
            return Math.subtractExact(leftMs, rightMs);
        } catch (ArithmeticException ignored) {
            return leftMs >= 0L && rightMs < 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** Returns a deadline for a positive duration, or the zero sentinel when no duration exists. */
    public static long deadlineAfter(long nowMs, long durationMs) {
        if (durationMs <= 0L) {
            return 0L;
        }
        long deadlineMs = saturatingAdd(nowMs, durationMs);
        // Zero is the persisted "unset" sentinel, so avoid emitting it for a real cooldown.
        return deadlineMs != 0L ? deadlineMs : 1L;
    }

    /** Returns a nonzero cooldown start for a positive duration, preserving signed world time. */
    public static long cooldownStartedAt(long nowMs, long durationMs) {
        if (durationMs <= 0L) {
            return 0L;
        }
        return nowMs != 0L ? nowMs : -1L;
    }

    /** Returns whether a nonzero signed deadline remains in the future. */
    public static boolean isDeadlineActive(long deadlineMs, long nowMs) {
        return deadlineMs != 0L && nowMs < deadlineMs;
    }

    /**
     * Returns the nonnegative time remaining until a signed deadline, saturating on overflow.
     */
    public static long remainingDurationMs(long deadlineMs, long nowMs) {
        if (!isDeadlineActive(deadlineMs, nowMs)) {
            return 0L;
        }
        return Math.max(0L, saturatingSubtract(deadlineMs, nowMs));
    }

    /** Reconstructs cooldown window metadata from a persisted signed deadline. */
    public static CooldownTiming reconstructCooldownTiming(long deadlineMs, long nowMs) {
        long durationMs = remainingDurationMs(deadlineMs, nowMs);
        long startedAtMs = cooldownStartedAt(nowMs, durationMs);
        return new CooldownTiming(deadlineMs, startedAtMs, durationMs);
    }

    /** Immutable signed cooldown window reconstructed at restore time. */
    public record CooldownTiming(long deadlineMs, long startedAtMs, long durationMs) {
        public CooldownTiming {
            durationMs = Math.max(0L, durationMs);
        }
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
        return toGameDurationMs(
                configuredSeconds,
                resolvedBasis,
                resolveCurrentGameSecondsPerRealSecond(store),
                resolveBaselineGameSecondsPerRealSecond(store),
                TameworkProgressionTimeScales.resolveWorldScale(store)
        );
    }

    static long toGameDurationMs(double configuredSeconds,
                                 @Nullable TwBreedingConfig.TimerBasis timerBasis,
                                 double currentGameSecondsPerRealSecond,
                                 double baselineGameSecondsPerRealSecond,
                                 double progressionScale) {
        if (!Double.isFinite(configuredSeconds) || configuredSeconds <= 0.0) {
            return 0L;
        }
        TwBreedingConfig.TimerBasis resolvedBasis = timerBasis != null
                ? timerBasis
                : TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED;
        double gameSecondsPerRealSecond = switch (resolvedBasis) {
            case REAL_TIME -> currentGameSecondsPerRealSecond;
            case WORLD_TIME_SCALED -> baselineGameSecondsPerRealSecond;
        };
        if (!Double.isFinite(gameSecondsPerRealSecond) || gameSecondsPerRealSecond <= 0.0) {
            gameSecondsPerRealSecond = DEFAULT_BASELINE_RATE;
        }
        double gameMillis = configuredSeconds * gameSecondsPerRealSecond * 1000.0;
        if (resolvedBasis == TwBreedingConfig.TimerBasis.WORLD_TIME_SCALED
                && Double.isFinite(progressionScale)
                && progressionScale > TameworkProgressionTimeScales.DEFAULT_SCALE) {
            gameMillis /= progressionScale;
        }
        if (!Double.isFinite(gameMillis) || gameMillis <= 0.0) {
            return 0L;
        }
        if (gameMillis >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, Math.round(gameMillis));
    }

    /**
     * Converts a game-time duration to an estimated real-time duration using current world time speed.
     *
     * <p>Since cooldown progression is tracked in game-time timestamps, UI-facing "remaining time" should
     * present a human-readable estimate in real time. This estimate adapts to the current world time rate.
     */
    public static long toEstimatedRealDurationMs(long gameDurationMs, @Nullable Store<EntityStore> store) {
        if (gameDurationMs <= 0L) {
            return 0L;
        }
        double gameSecondsPerRealSecond = resolveCurrentGameSecondsPerRealSecond(store);
        if (!Double.isFinite(gameSecondsPerRealSecond) || gameSecondsPerRealSecond <= 0.0) {
            gameSecondsPerRealSecond = resolveBaselineGameSecondsPerRealSecond(store);
        }
        if (!Double.isFinite(gameSecondsPerRealSecond) || gameSecondsPerRealSecond <= 0.0) {
            gameSecondsPerRealSecond = DEFAULT_BASELINE_RATE;
        }
        double realMillis = (double) gameDurationMs / gameSecondsPerRealSecond;
        if (!Double.isFinite(realMillis) || realMillis <= 0.0) {
            return 0L;
        }
        if (realMillis >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, Math.round(realMillis));
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
