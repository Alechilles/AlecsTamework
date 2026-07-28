package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stateless vigour charge spending and recharge rules for avatar flight.
 */
public final class AvatarFlightVigourService {
    private static final double MILLIS_PER_SECOND = 1000.0;

    private AvatarFlightVigourService() {
    }

    public enum RechargeMode {
        NONE,
        DELAYED,
        GROUNDED,
        FAST_FLIGHT
    }

    public record State(double charges, long lastUpdateAtMs, long rechargeBlockedUntilMs) {
    }

    public record Result(@Nonnull State state, @Nonnull RechargeMode mode) {
        public Result {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(mode, "mode");
        }
    }

    @Nonnull
    public static Result recharge(@Nonnull State state,
                                  @Nullable TwAvatarFlightConfig config,
                                  boolean grounded,
                                  double horizontalSpeed,
                                  long nowMs) {
        return recharge(state, config, AvatarFlightProgressionTuning.neutral(), grounded, horizontalSpeed, nowMs);
    }

    @Nonnull
    public static Result recharge(@Nonnull State state,
                                  @Nullable TwAvatarFlightConfig config,
                                  @Nullable AvatarFlightProgressionTuning tuning,
                                  boolean grounded,
                                  double horizontalSpeed,
                                  long nowMs) {
        Objects.requireNonNull(state, "state");
        AvatarFlightProgressionTuning effectiveTuning = tuning == null ? AvatarFlightProgressionTuning.neutral() : tuning;
        double maxCharges = maxCharges(config, effectiveTuning);
        if (!isEnabled(config) || maxCharges <= 0.0) {
            return new Result(new State(maxCharges, nowMs, 0L), RechargeMode.NONE);
        }

        double charges = clamp(state.charges(), 0.0, maxCharges);
        long blockedUntilMs = state.rechargeBlockedUntilMs();
        if (charges >= maxCharges) {
            return new Result(new State(maxCharges, nowMs, blockedUntilMs), RechargeMode.NONE);
        }
        if (blockedUntilMs != 0L && nowMs < blockedUntilMs) {
            return new Result(new State(charges, nowMs, blockedUntilMs), RechargeMode.DELAYED);
        }

        RechargeCadence cadence = rechargeCadence(config, effectiveTuning, grounded, horizontalSpeed);
        if (cadence.mode() == RechargeMode.NONE || cadence.secondsPerCharge() <= 0.0) {
            return new Result(new State(charges, nowMs, blockedUntilMs), RechargeMode.NONE);
        }

        long elapsedStartMs = eligibleElapsedStart(state.lastUpdateAtMs(), blockedUntilMs, nowMs);
        long elapsedMs = elapsedMillis(elapsedStartMs, nowMs);
        double gain = elapsedMs / (cadence.secondsPerCharge() * MILLIS_PER_SECOND);
        double recharged = clamp(charges + gain, 0.0, maxCharges);
        return new Result(new State(recharged, nowMs, blockedUntilMs), cadence.mode());
    }

    public static boolean canSpend(@Nonnull State state, @Nullable TwAvatarFlightConfig config, double cost) {
        return canSpend(state, config, AvatarFlightProgressionTuning.neutral(), cost);
    }

    public static boolean canSpend(@Nonnull State state,
                                   @Nullable TwAvatarFlightConfig config,
                                   @Nullable AvatarFlightProgressionTuning tuning,
                                   double cost) {
        Objects.requireNonNull(state, "state");
        if (isFreeCost(cost) || !isEnabled(config)) {
            return true;
        }
        AvatarFlightProgressionTuning effectiveTuning = tuning == null ? AvatarFlightProgressionTuning.neutral() : tuning;
        return clamp(state.charges(), 0.0, maxCharges(config, effectiveTuning)) >= cost;
    }

    @Nonnull
    public static State spend(@Nonnull State state,
                              @Nullable TwAvatarFlightConfig config,
                              double cost,
                              long nowMs) {
        return spend(state, config, AvatarFlightProgressionTuning.neutral(), cost, nowMs);
    }

    @Nonnull
    public static State spend(@Nonnull State state,
                              @Nullable TwAvatarFlightConfig config,
                              @Nullable AvatarFlightProgressionTuning tuning,
                              double cost,
                              long nowMs) {
        Objects.requireNonNull(state, "state");
        AvatarFlightProgressionTuning effectiveTuning = tuning == null ? AvatarFlightProgressionTuning.neutral() : tuning;
        double maxCharges = maxCharges(config, effectiveTuning);
        double charges = clamp(state.charges(), 0.0, maxCharges);
        long blockedUntilMs = state.rechargeBlockedUntilMs();
        if (isFreeCost(cost) || !isEnabled(config) || charges < cost) {
            return new State(charges, nowMs, blockedUntilMs);
        }

        double spent = clamp(charges - cost, 0.0, maxCharges);
        long delayMs = rechargeDelayMs(config);
        long nextBlockedUntilMs = delayMs > 0L ? addDelay(nowMs, delayMs) : 0L;
        return new State(spent, nowMs, nextBlockedUntilMs);
    }

    @Nonnull
    private static RechargeCadence rechargeCadence(@Nonnull TwAvatarFlightConfig config,
                                                   @Nonnull AvatarFlightProgressionTuning tuning,
                                                   boolean grounded,
                                                   double horizontalSpeed) {
        TwAvatarFlightConfig.VigourSettings vigour = config.getVigour();
        if (grounded) {
            return cadence(RechargeMode.GROUNDED,
                    vigour.getGroundedRechargeSecondsPerCharge() / tuning.vigourRechargeRateMultiplier());
        }
        if (AvatarFlightSpeedMetrics.isFastFlightSpeed(horizontalSpeed, config)) {
            return cadence(RechargeMode.FAST_FLIGHT,
                    vigour.getFastFlightRechargeSecondsPerCharge() / tuning.vigourRechargeRateMultiplier());
        }
        return new RechargeCadence(RechargeMode.NONE, 0.0);
    }

    @Nonnull
    private static RechargeCadence cadence(@Nonnull RechargeMode mode, double secondsPerCharge) {
        return Double.isFinite(secondsPerCharge) && secondsPerCharge > 0.0
                ? new RechargeCadence(mode, secondsPerCharge)
                : new RechargeCadence(RechargeMode.NONE, 0.0);
    }

    private static boolean isEnabled(@Nullable TwAvatarFlightConfig config) {
        return config != null && config.getVigour().isEnabled();
    }

    private static double maxCharges(@Nullable TwAvatarFlightConfig config,
                                     @Nonnull AvatarFlightProgressionTuning tuning) {
        if (config == null) {
            return 0.0;
        }
        double maxCharges = config.getVigour().getMaxCharges() * tuning.vigourCapacityMultiplier();
        return Double.isFinite(maxCharges) && maxCharges > 0.0 ? maxCharges : 0.0;
    }

    private static long rechargeDelayMs(@Nullable TwAvatarFlightConfig config) {
        if (config == null) {
            return 0L;
        }
        double seconds = config.getVigour().getRechargeDelayAfterSpendSeconds();
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return 0L;
        }
        double millis = seconds * MILLIS_PER_SECOND;
        return millis >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(millis);
    }

    private static boolean isFreeCost(double cost) {
        return Double.isNaN(cost) || cost <= 0.0;
    }

    private static long elapsedMillis(long lastUpdateAtMs, long nowMs) {
        if (lastUpdateAtMs == 0L || nowMs <= lastUpdateAtMs) {
            return 0L;
        }
        long elapsed = nowMs - lastUpdateAtMs;
        return elapsed < 0L ? Long.MAX_VALUE : elapsed;
    }

    private static long eligibleElapsedStart(long lastUpdateAtMs, long blockedUntilMs, long nowMs) {
        if (blockedUntilMs == 0L || nowMs < blockedUntilMs) {
            return lastUpdateAtMs;
        }
        if (lastUpdateAtMs == 0L) {
            return blockedUntilMs;
        }
        return Math.max(lastUpdateAtMs, blockedUntilMs);
    }

    private static long addDelay(long nowMs, long delayMs) {
        long blockedUntilMs = nowMs + delayMs;
        return blockedUntilMs < nowMs ? Long.MAX_VALUE : blockedUntilMs;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private record RechargeCadence(@Nonnull RechargeMode mode, double secondsPerCharge) {
        private RechargeCadence {
            Objects.requireNonNull(mode, "mode");
        }
    }
}
