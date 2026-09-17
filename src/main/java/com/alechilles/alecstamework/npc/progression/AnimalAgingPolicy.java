package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.AnimalAgingSettings;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure elapsed-time calculations for optional adult animal aging.
 *
 * <p>Callers supply eligible real time. This class neither reads live ECS state
 * nor decides whether an owner was eligible for progression.</p>
 */
public final class AnimalAgingPolicy {
    private static final double FULL_RATE = 1.0;
    private static final double PRE_PRIME_RATE_AT_25 = 0.5;
    private static final double PRE_PRIME_RATE_AT_0 = 0.25;
    private static final double POST_PRIME_RATE_AT_25 = 1.5;
    private static final double POST_PRIME_RATE_AT_0 = 1.75;

    private AnimalAgingPolicy() {
    }

    /**
     * Advances adult age by eligible real time. Loaded animals use the lower of
     * hunger and thirst as their care signal; unloaded animals always progress
     * at the normal rate because stored resources are not simulated.
     */
    @Nonnull
    public static Progress advance(@Nullable AnimalAgingSettings settings,
                                   double ageProgressMs,
                                   long eligibleElapsedMs,
                                   boolean loaded,
                                   double hungerPercent,
                                   double thirstPercent) {
        double current = sanitizeProgress(ageProgressMs);
        if (!isActive(settings)) {
            return progress(settings, current, 0.0);
        }
        AnimalAgingSettings safeSettings = settings;
        if (safeSettings.getMode() == AnimalAgingSettings.LifecycleMode.OFF) {
            return progress(safeSettings, current, 0.0);
        }
        long safeElapsed = Math.max(0L, eligibleElapsedMs);
        if (safeElapsed == 0L) {
            return progress(safeSettings, current, 0.0);
        }

        double remainingElapsed = safeElapsed;
        double applied = 0.0;
        while (remainingElapsed > 0.0) {
            Stage stage = resolveStage(safeSettings, current);
            if (stage == Stage.PRIME
                    && safeSettings.getMode() == AnimalAgingSettings.LifecycleMode.FREEZE_AT_PRIME) {
                current = safeSettings.getAdultToPrimeMs();
                break;
            }
            if (isDead(safeSettings, current)) {
                break;
            }

            double rate = agingRate(safeSettings, current, loaded, hungerPercent, thirstPercent);
            if (rate <= 0.0) {
                break;
            }
            double boundary = nextBoundary(safeSettings, stage);
            double progressUntilBoundary = Math.max(0.0, boundary - current);
            if (Double.isInfinite(boundary) || progressUntilBoundary == 0.0) {
                double gained = safeMultiply(remainingElapsed, rate);
                current = safeAdd(current, gained);
                applied = safeAdd(applied, gained);
                break;
            }

            double elapsedUntilBoundary = progressUntilBoundary / rate;
            if (remainingElapsed < elapsedUntilBoundary) {
                double gained = safeMultiply(remainingElapsed, rate);
                current = safeAdd(current, gained);
                applied = safeAdd(applied, gained);
                break;
            }
            current = boundary;
            applied = safeAdd(applied, progressUntilBoundary);
            remainingElapsed -= elapsedUntilBoundary;
        }
        return progress(safeSettings, current, applied);
    }

    /** Returns the juvenile/pre-prime multiplier for the supplied care values. */
    public static double growthRate(double hungerPercent, double thirstPercent) {
        return careRate(minimumNeed(hungerPercent, thirstPercent), true);
    }

    /**
     * Returns the adult aging multiplier at the given age. This is one for
     * unloaded animals, matching the maintenance-free unloaded concession.
     */
    public static double agingRate(@Nullable AnimalAgingSettings settings,
                                   double ageProgressMs,
                                   boolean loaded,
                                   double hungerPercent,
                                   double thirstPercent) {
        if (!loaded) {
            return FULL_RATE;
        }
        boolean prePrime = settings == null || sanitizeProgress(ageProgressMs) < settings.getAdultToPrimeMs();
        return careRate(minimumNeed(hungerPercent, thirstPercent), prePrime);
    }

    @Nonnull
    public static Stage resolveStage(@Nullable AnimalAgingSettings settings, double ageProgressMs) {
        if (!isActive(settings) || settings.getMode() == AnimalAgingSettings.LifecycleMode.OFF) {
            return Stage.ADULT;
        }
        double progress = sanitizeProgress(ageProgressMs);
        if (progress < settings.getAdultToPrimeMs()) {
            return Stage.ADULT;
        }
        if (settings.getMode() == AnimalAgingSettings.LifecycleMode.FREEZE_AT_PRIME
                || progress < primeEnd(settings)) {
            return Stage.PRIME;
        }
        return Stage.SENIOR;
    }

    public static boolean isDead(@Nullable AnimalAgingSettings settings, double ageProgressMs) {
        return isActive(settings)
                && settings.getMode() == AnimalAgingSettings.LifecycleMode.FULL
                && settings.isOldAgeDeathEnabled()
                && sanitizeProgress(ageProgressMs) >= seniorEnd(settings);
    }

    /** Returns the configured domestic yield multiplier for the reported age. */
    public static double yieldMultiplier(@Nullable AnimalAgingSettings settings, double ageProgressMs) {
        if (!isActive(settings) || settings.getMode() == AnimalAgingSettings.LifecycleMode.OFF) {
            return FULL_RATE;
        }
        return resolveStage(settings, ageProgressMs) == Stage.PRIME
                ? FULL_RATE
                : settings.getNonPrimeYieldMultiplier();
    }

    private static Progress progress(@Nullable AnimalAgingSettings settings,
                                     double ageProgressMs,
                                     double appliedElapsedMs) {
        Stage stage = resolveStage(settings, ageProgressMs);
        boolean dead = isDead(settings, ageProgressMs);
        return new Progress(
                ageProgressMs,
                stage,
                dead,
                remainingStageMs(settings, ageProgressMs, stage, dead),
                yieldMultiplier(settings, ageProgressMs),
                appliedElapsedMs
        );
    }

    private static long remainingStageMs(@Nullable AnimalAgingSettings settings,
                                         double ageProgressMs,
                                         @Nonnull Stage stage,
                                         boolean dead) {
        if (!isActive(settings)
                || settings.getMode() == AnimalAgingSettings.LifecycleMode.OFF
                || dead) {
            return dead ? 0L : Long.MAX_VALUE;
        }
        if (stage == Stage.PRIME
                && settings.getMode() == AnimalAgingSettings.LifecycleMode.FREEZE_AT_PRIME) {
            return Long.MAX_VALUE;
        }
        if (stage == Stage.SENIOR && !settings.isOldAgeDeathEnabled()) {
            return Long.MAX_VALUE;
        }
        double boundary = nextBoundary(settings, stage);
        return Math.max(0L, roundDown(boundary - sanitizeProgress(ageProgressMs)));
    }

    private static double nextBoundary(@Nonnull AnimalAgingSettings settings, @Nonnull Stage stage) {
        return switch (stage) {
            case ADULT -> settings.getAdultToPrimeMs();
            case PRIME -> settings.getMode() == AnimalAgingSettings.LifecycleMode.FREEZE_AT_PRIME
                    ? settings.getAdultToPrimeMs()
                    : primeEnd(settings);
            case SENIOR -> settings.isOldAgeDeathEnabled() ? seniorEnd(settings) : Double.POSITIVE_INFINITY;
        };
    }

    private static double primeEnd(@Nonnull AnimalAgingSettings settings) {
        return safeAdd(settings.getAdultToPrimeMs(), settings.getPrimeMs());
    }

    private static double seniorEnd(@Nonnull AnimalAgingSettings settings) {
        return safeAdd(primeEnd(settings), settings.getSeniorMs());
    }

    private static double careRate(double minimumNeed, boolean prePrime) {
        if (minimumNeed >= 50.0) {
            return FULL_RATE;
        }
        if (minimumNeed >= 25.0) {
            double atTwentyFive = prePrime ? PRE_PRIME_RATE_AT_25 : POST_PRIME_RATE_AT_25;
            return atTwentyFive + (minimumNeed - 25.0) * (FULL_RATE - atTwentyFive) / 25.0;
        }
        double atTwentyFive = prePrime ? PRE_PRIME_RATE_AT_25 : POST_PRIME_RATE_AT_25;
        double atZero = prePrime ? PRE_PRIME_RATE_AT_0 : POST_PRIME_RATE_AT_0;
        return atZero + minimumNeed * (atTwentyFive - atZero) / 25.0;
    }

    private static double minimumNeed(double hungerPercent, double thirstPercent) {
        return Math.min(clampPercent(hungerPercent), clampPercent(thirstPercent));
    }

    private static double clampPercent(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(100.0, value));
    }

    private static boolean isActive(@Nullable AnimalAgingSettings settings) {
        return settings != null && settings.isEnabled();
    }

    private static double sanitizeProgress(double progress) {
        return Double.isFinite(progress) && progress >= 0.0 ? progress : 0.0;
    }

    private static double safeAdd(double left, double right) {
        if (Double.isInfinite(left) || Double.isInfinite(right) || left > Double.MAX_VALUE - right) {
            return Double.MAX_VALUE;
        }
        return left + right;
    }

    private static double safeMultiply(double left, double right) {
        if (left == 0.0 || right == 0.0) {
            return 0.0;
        }
        if (left > Double.MAX_VALUE / right) {
            return Double.MAX_VALUE;
        }
        return left * right;
    }

    private static long roundDown(double value) {
        if (!Double.isFinite(value) || value >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) Math.max(0.0, Math.floor(value));
    }

    public enum Stage {
        ADULT,
        PRIME,
        SENIOR
    }

    /** Result of one age calculation, ready for the caller to persist. */
    public static final class Progress {
        private final double ageProgressMs;
        private final Stage stage;
        private final boolean dead;
        private final long remainingStageMs;
        private final double yieldMultiplier;
        private final double appliedElapsedMs;

        private Progress(double ageProgressMs,
                         @Nonnull Stage stage,
                         boolean dead,
                         long remainingStageMs,
                         double yieldMultiplier,
                         double appliedElapsedMs) {
            this.ageProgressMs = ageProgressMs;
            this.stage = stage;
            this.dead = dead;
            this.remainingStageMs = remainingStageMs;
            this.yieldMultiplier = yieldMultiplier;
            this.appliedElapsedMs = appliedElapsedMs;
        }

        public double getAgeProgressMs() {
            return ageProgressMs;
        }

        public double ageProgressMs() {
            return getAgeProgressMs();
        }

        @Nonnull
        public Stage getStage() {
            return stage;
        }

        @Nonnull
        public Stage stage() {
            return getStage();
        }

        public boolean isDead() {
            return dead;
        }

        public boolean dead() {
            return isDead();
        }

        public long getRemainingStageMs() {
            return remainingStageMs;
        }

        public long remainingStageMs() {
            return getRemainingStageMs();
        }

        public double getYieldMultiplier() {
            return yieldMultiplier;
        }

        public double yieldMultiplier() {
            return getYieldMultiplier();
        }

        public double getAppliedElapsedMs() {
            return appliedElapsedMs;
        }

        public double appliedElapsedMs() {
            return getAppliedElapsedMs();
        }
    }
}
