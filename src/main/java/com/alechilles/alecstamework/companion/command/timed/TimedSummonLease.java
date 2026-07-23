package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonical timed detail without copied roster or lifecycle state. */
public record TimedSummonLease(
        @Nonnull ProfileId profileId,
        long leaseRevision,
        @Nullable TimedSummonSessionId sessionId,
        @Nullable Long remainingMs,
        @Nullable Long cooldownUntilMs,
        @Nonnull TimedSummonPolicy policy,
        @Nonnull Set<Long> emittedWarningThresholdsMs,
        @Nullable Long checkpointedAtMs,
        long createdAtMs,
        long updatedAtMs
) {
    public TimedSummonLease {
        if (profileId == null || leaseRevision <= 0
                || policy == null
                || emittedWarningThresholdsMs == null
                || remainingMs != null && remainingMs < 0) {
            throw new IllegalArgumentException(
                    "Complete timed summon lease is required"
            );
        }
        TreeSet<Long> emitted = new TreeSet<>(
                java.util.Comparator.reverseOrder()
        );
        emitted.addAll(emittedWarningThresholdsMs);
        if (!policy.warningThresholdsMs().containsAll(emitted)) {
            throw new IllegalArgumentException(
                    "Emitted warnings must belong to the policy snapshot"
            );
        }
        emittedWarningThresholdsMs = Set.copyOf(emitted);
        if (sessionId == null) {
            if (remainingMs != null || checkpointedAtMs != null
                    || !emitted.isEmpty()) {
                throw new IllegalArgumentException(
                        "Dormant timed lease cannot retain session evidence"
                );
            }
        } else {
            if (checkpointedAtMs == null || cooldownUntilMs != null
                    || policy.unlimited() != (remainingMs == null)
                    || remainingMs != null
                    && remainingMs > policy.activeDurationMs()) {
                throw new IllegalArgumentException(
                        "Active timed lease evidence is inconsistent"
                );
            }
        }
    }

    public boolean activeSession() {
        return sessionId != null;
    }

    public boolean unlimitedSession() {
        return activeSession() && policy.unlimited();
    }

    public boolean cooldownActive(long nowMs) {
        return cooldownUntilMs != null && nowMs < cooldownUntilMs;
    }
}
