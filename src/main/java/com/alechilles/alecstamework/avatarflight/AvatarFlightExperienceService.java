package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import javax.annotation.Nullable;

/**
 * Deterministic, bounded accounting for experience earned from qualified avatar flight time.
 */
public final class AvatarFlightExperienceService {
    private static final double MAX_TICK_SECONDS = 0.25d;
    private static final long MINUTE_MS = 60_000L;

    public record State(double qualifiedSeconds,
                        double windowAwardedXp,
                        long windowStartedAtMs,
                        long lastSampleAtMs) {
    }

    public record Result(State state, double awardedXp) {
    }

    public State reset(long nowMs) {
        return new State(0.0d, 0.0d, nowMs, 0L);
    }

    public Result tick(State state,
                       @Nullable TwLevelingConfig.FlightXpSourceSettings settings,
                       boolean qualifies,
                       long nowMs) {
        State current = state == null ? reset(nowMs) : state;
        if (current.lastSampleAtMs() <= 0L) {
            return new Result(new State(
                    nonNegative(current.qualifiedSeconds()),
                    nonNegative(current.windowAwardedXp()),
                    current.windowStartedAtMs(),
                    nowMs
            ), 0.0d);
        }

        long windowStartedAtMs = current.windowStartedAtMs();
        double windowAwardedXp = nonNegative(current.windowAwardedXp());
        if (nowMs - windowStartedAtMs >= MINUTE_MS || nowMs < windowStartedAtMs) {
            windowStartedAtMs = nowMs;
            windowAwardedXp = 0.0d;
        }

        double qualifiedSeconds = nonNegative(current.qualifiedSeconds());
        if (!qualifies || !isAwardingEnabled(settings)) {
            return new Result(new State(qualifiedSeconds, windowAwardedXp, windowStartedAtMs, nowMs), 0.0d);
        }

        double elapsedSeconds = Math.min(
                MAX_TICK_SECONDS,
                Math.max(0.0d, nowMs - current.lastSampleAtMs()) / 1000.0d
        );
        qualifiedSeconds += elapsedSeconds;

        double awardIntervalSeconds = settings.getAwardIntervalSeconds();
        long fullIntervals = (long) Math.floor(qualifiedSeconds / awardIntervalSeconds);
        if (fullIntervals <= 0L) {
            return new Result(new State(qualifiedSeconds, windowAwardedXp, windowStartedAtMs, nowMs), 0.0d);
        }

        qualifiedSeconds -= fullIntervals * awardIntervalSeconds;
        double availableXp = Math.max(0.0d, settings.getMaxXpPerMinute() - windowAwardedXp);
        double award = Math.min(
                fullIntervals * awardIntervalSeconds * settings.getXpPerQualifiedSecond(),
                availableXp
        );
        windowAwardedXp += award;
        return new Result(new State(qualifiedSeconds, windowAwardedXp, windowStartedAtMs, nowMs), award);
    }

    private static boolean isAwardingEnabled(@Nullable TwLevelingConfig.FlightXpSourceSettings settings) {
        return settings != null
                && settings.isEnabled()
                && settings.getXpPerQualifiedSecond() > 0.0d
                && settings.getAwardIntervalSeconds() > 0.0d
                && settings.getMaxXpPerMinute() > 0.0d;
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) && value > 0.0d ? value : 0.0d;
    }
}
