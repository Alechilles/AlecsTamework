package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.Gson;
import java.util.Arrays;
import javax.annotation.Nonnull;

/** Immutable balance-policy snapshot retained for the lifetime of one summon session. */
public record CommandTimedSummonPolicySnapshot(
        long activeDurationMs,
        long resummonCooldownMs,
        boolean autoStoreOnOwnerLogout,
        @Nonnull long[] expiryWarningThresholdsMs
) {
    private static final Gson GSON = new Gson();
    private static final CommandTimedSummonPolicySnapshot UNLIMITED =
            new CommandTimedSummonPolicySnapshot(0L, 0L, true, new long[0]);

    public CommandTimedSummonPolicySnapshot {
        expiryWarningThresholdsMs = expiryWarningThresholdsMs == null
                ? new long[0] : expiryWarningThresholdsMs.clone();
        if (activeDurationMs < 0L || resummonCooldownMs < 0L) {
            throw new IllegalArgumentException("Summon policy durations must be non-negative.");
        }
        long previous = Long.MAX_VALUE;
        for (long threshold : expiryWarningThresholdsMs) {
            if (threshold <= 0L || activeDurationMs == 0L || threshold >= activeDurationMs
                    || threshold >= previous) {
                throw new IllegalArgumentException(
                        "Warning thresholds must be positive, unique, descending, and below ActiveDurationMs.");
            }
            previous = threshold;
        }
    }

    @Override
    public long[] expiryWarningThresholdsMs() {
        return expiryWarningThresholdsMs.clone();
    }

    public boolean unlimited() {
        return activeDurationMs == 0L;
    }

    @Nonnull
    static String toJson(@Nonnull CommandTimedSummonPolicySnapshot snapshot) {
        return GSON.toJson(snapshot);
    }

    @Nonnull
    static CommandTimedSummonPolicySnapshot fromJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return UNLIMITED;
        }
        CommandTimedSummonPolicySnapshot decoded = GSON.fromJson(json, CommandTimedSummonPolicySnapshot.class);
        return decoded == null ? UNLIMITED : new CommandTimedSummonPolicySnapshot(
                decoded.activeDurationMs,
                decoded.resummonCooldownMs,
                decoded.autoStoreOnOwnerLogout,
                decoded.expiryWarningThresholdsMs
        );
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof CommandTimedSummonPolicySnapshot that
                && activeDurationMs == that.activeDurationMs
                && resummonCooldownMs == that.resummonCooldownMs
                && autoStoreOnOwnerLogout == that.autoStoreOnOwnerLogout
                && Arrays.equals(expiryWarningThresholdsMs, that.expiryWarningThresholdsMs));
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(activeDurationMs);
        result = 31 * result + Long.hashCode(resummonCooldownMs);
        result = 31 * result + Boolean.hashCode(autoStoreOnOwnerLogout);
        return 31 * result + Arrays.hashCode(expiryWarningThresholdsMs);
    }
}
