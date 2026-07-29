package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import javax.annotation.Nullable;

/**
 * Deterministic, bounded accounting for experience earned while a bonded companion is summoned.
 */
public final class SummonedCompanionExperienceService {
    private static final double MAX_TICK_SECONDS = 0.25d;
    private static final long HOUR_MS = 3_600_000L;

    public record State(double activeSeconds,
                        double windowAwardedXp,
                        long windowStartedAtMs,
                        long lastSampleAtMs) {
    }

    public record Result(State state, double awardedXp) {
    }

    public State reset(long nowMs) {
        return new State(0.0d, 0.0d, Math.max(0L, nowMs), 0L);
    }

    /**
     * Advances a companion's persisted summoned-XP cadence by one server sample.
     * Inactive samples intentionally discard incomplete active intervals.
     */
    public Result advance(@Nullable State state,
                          long nowMs,
                          double dt,
                          @Nullable TwLevelingConfig.SummonedXpSourceSettings settings,
                          boolean active) {
        State current = state == null ? reset(nowMs) : state;
        long sampleAtMs = Math.max(0L, nowMs);
        long windowStartedAtMs = Math.max(0L, current.windowStartedAtMs());
        double windowAwardedXp = nonNegative(current.windowAwardedXp());

        if (windowAwardedXp > 0.0d && sampleAtMs - windowStartedAtMs >= HOUR_MS) {
            windowAwardedXp = 0.0d;
            windowStartedAtMs = sampleAtMs;
        }

        if (current.lastSampleAtMs() <= 0L) {
            return new Result(new State(0.0d, windowAwardedXp, windowStartedAtMs, sampleAtMs), 0.0d);
        }

        if (!active || isGapped(current, sampleAtMs, dt) || !isAwardingEnabled(settings)) {
            return new Result(new State(0.0d, windowAwardedXp, windowStartedAtMs, sampleAtMs), 0.0d);
        }

        double activeSeconds = nonNegative(current.activeSeconds()) + Math.min(
                MAX_TICK_SECONDS,
                Math.max(0.0d, dt)
        );
        double awardIntervalSeconds = settings.getAwardIntervalSeconds();
        long fullIntervals = (long) Math.floor(activeSeconds / awardIntervalSeconds);
        if (fullIntervals <= 0L) {
            return new Result(new State(activeSeconds, windowAwardedXp, windowStartedAtMs, sampleAtMs), 0.0d);
        }

        activeSeconds -= fullIntervals * awardIntervalSeconds;
        double availableXp = Math.max(0.0d, settings.getMaxXpPerHour() - windowAwardedXp);
        double award = Math.min(
                fullIntervals * awardIntervalSeconds * settings.getXpPerActiveSecond(),
                availableXp
        );
        if (award > 0.0d && windowAwardedXp == 0.0d) {
            windowStartedAtMs = sampleAtMs;
        }
        windowAwardedXp += award;
        return new Result(new State(activeSeconds, windowAwardedXp, windowStartedAtMs, sampleAtMs), award);
    }

    private static boolean isAwardingEnabled(@Nullable TwLevelingConfig.SummonedXpSourceSettings settings) {
        return settings != null && settings.isEnabled();
    }

    private static boolean isGapped(State state, long sampleAtMs, double dt) {
        long elapsedMs = sampleAtMs - state.lastSampleAtMs();
        if (elapsedMs <= 0L) {
            return false;
        }
        double reportedMs = Double.isFinite(dt) && dt > 0.0d ? dt * 1000.0d : 0.0d;
        double expectedMaximumMs = Math.max(MAX_TICK_SECONDS * 1000.0d, reportedMs)
                + MAX_TICK_SECONDS * 1000.0d;
        return elapsedMs > expectedMaximumMs;
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) && value > 0.0d ? value : 0.0d;
    }
}
