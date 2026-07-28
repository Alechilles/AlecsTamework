package com.alechilles.alecstamework.companion.command.timed;

import java.util.List;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable role-policy snapshot retained for one lease configuration. */
public record TimedSummonPolicy(
        @Nullable String configId,
        @Nullable Long configRevision,
        long activeDurationMs,
        long resummonCooldownMs,
        boolean autoStoreOnOwnerLogout,
        @Nonnull List<Long> warningThresholdsMs
) {
    public TimedSummonPolicy {
        configId = normalize(configId);
        if ((configId == null) != (configRevision == null)
                || configRevision != null && configRevision < 0
                || activeDurationMs < 0
                || resummonCooldownMs < 0
                || warningThresholdsMs == null) {
            throw new IllegalArgumentException(
                    "Complete timed summon policy is required"
            );
        }
        TreeSet<Long> unique = new TreeSet<>(
                java.util.Comparator.reverseOrder()
        );
        for (Long threshold : warningThresholdsMs) {
            if (threshold == null || threshold <= 0
                    || activeDurationMs == 0
                    || threshold >= activeDurationMs
                    || !unique.add(threshold)) {
                throw new IllegalArgumentException(
                        "Timed warning thresholds must be unique, "
                                + "positive, descending, and below duration"
                );
            }
        }
        if (!List.copyOf(unique).equals(warningThresholdsMs)) {
            throw new IllegalArgumentException(
                    "Timed warning thresholds must be descending"
            );
        }
        warningThresholdsMs = List.copyOf(unique);
    }

    public boolean unlimited() {
        return activeDurationMs == 0;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}

