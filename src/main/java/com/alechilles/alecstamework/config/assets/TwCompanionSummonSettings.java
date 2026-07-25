package com.alechilles.alecstamework.config.assets;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Role-scoped balance and storage policy for timed command summoning. */
public final class TwCompanionSummonSettings {
    private static final Long[] EMPTY_THRESHOLDS = new Long[0];

    private boolean enabled;
    private long activeDurationMs;
    private long resummonCooldownMs;
    private boolean autoStoreOnOwnerLogout = true;
    private Long[] expiryWarningThresholdsMs = EMPTY_THRESHOLDS;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the non-negative active-session duration.
     *
     * <p>Zero means unlimited. This value is a duration, not a world timestamp.
     */
    public long getActiveDurationMs() {
        return activeDurationMs;
    }

    /**
     * Returns the non-negative duration applied after dismissal or expiry.
     *
     * <p>Zero disables the additional cooldown. This value is a duration, not
     * a world timestamp.
     */
    public long getResummonCooldownMs() {
        return resummonCooldownMs;
    }

    public boolean isAutoStoreOnOwnerLogout() {
        return autoStoreOnOwnerLogout;
    }

    /** Returns an isolated primitive copy in configured descending order. */
    @Nonnull
    public long[] getExpiryWarningThresholdsMs() {
        Long[] boxed = getExpiryWarningThresholdsBoxed();
        long[] result = new long[boxed.length];
        for (int index = 0; index < boxed.length; index++) {
            result[index] = boxed[index];
        }
        return result;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setActiveDurationMs(long activeDurationMs) {
        this.activeDurationMs = requireDuration(
                "ActiveDurationMs",
                activeDurationMs
        );
    }

    void setResummonCooldownMs(long resummonCooldownMs) {
        this.resummonCooldownMs = requireDuration(
                "ResummonCooldownMs",
                resummonCooldownMs
        );
    }

    void setAutoStoreOnOwnerLogout(boolean autoStoreOnOwnerLogout) {
        this.autoStoreOnOwnerLogout = autoStoreOnOwnerLogout;
    }

    void setExpiryWarningThresholdsMs(@Nullable Long[] thresholds) {
        expiryWarningThresholdsMs = validateShape(thresholds);
    }

    /** Validates relationships that may depend on inherited duration values. */
    void validate() {
        validatedThresholds();
    }

    @Nonnull
    private Long[] validatedThresholds() {
        Long[] thresholds = validateShape(expiryWarningThresholdsMs);
        for (long threshold : thresholds) {
            if (activeDurationMs <= 0L || threshold >= activeDurationMs) {
                throw new IllegalArgumentException(
                        "Summon warning threshold " + threshold
                                + " must be below positive ActiveDurationMs."
                );
            }
        }
        return thresholds;
    }

    @Nonnull
    TwCompanionSummonSettings copy() {
        TwCompanionSummonSettings copy =
                new TwCompanionSummonSettings();
        copy.enabled = enabled;
        copy.activeDurationMs = activeDurationMs;
        copy.resummonCooldownMs = resummonCooldownMs;
        copy.autoStoreOnOwnerLogout = autoStoreOnOwnerLogout;
        copy.expiryWarningThresholdsMs = validatedThresholds();
        return copy;
    }

    @Nonnull
    Long[] getExpiryWarningThresholdsBoxed() {
        return expiryWarningThresholdsMs == null
                ? EMPTY_THRESHOLDS
                : expiryWarningThresholdsMs.clone();
    }

    private static long requireDuration(
            @Nonnull String field,
            long value
    ) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "Summon " + field + " must not be negative."
            );
        }
        return value;
    }

    @Nonnull
    private static Long[] validateShape(@Nullable Long[] thresholds) {
        if (thresholds == null || thresholds.length == 0) {
            return EMPTY_THRESHOLDS;
        }
        Long[] copy = thresholds.clone();
        long previous = Long.MAX_VALUE;
        for (int index = 0; index < copy.length; index++) {
            Long threshold = copy[index];
            if (threshold == null || threshold <= 0L) {
                throw new IllegalArgumentException(
                        "Summon warning thresholds must be positive "
                                + "(index " + index + ")."
                );
            }
            if (threshold >= previous) {
                throw new IllegalArgumentException(
                        "Summon warning thresholds must be strictly "
                                + "descending and unique."
                );
            }
            previous = threshold;
        }
        return copy;
    }
}
