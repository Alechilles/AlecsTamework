package com.alechilles.alecstamework.npc.breeding;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact provisional parent fields written by one breeding job.
 *
 * <p>Rollback compares this value with live state before restoring the pre-job snapshot, preventing
 * an old cancellation from overwriting a newer cooldown or manual selection.
 */
public record AppliedCooldownFingerprint(boolean applied,
                                         boolean ready,
                                         long cooldownUntilMs,
                                         long cooldownStartedAtMs,
                                         long cooldownDurationMs,
                                         @Nullable UUID lastPartnerUuid,
                                         long lastHappinessUpdateMs,
                                         @Nullable UUID manualBreedingPlayerUuid,
                                         long manualBreedingUntilMs,
                                         @Nonnull ParentBreedingSnapshot.AlarmSnapshot alarm) {
    public AppliedCooldownFingerprint {
        if (cooldownDurationMs < 0L) {
            throw new IllegalArgumentException("cooldownDurationMs must be nonnegative");
        }
        if (manualBreedingPlayerUuid == null && manualBreedingUntilMs != 0L) {
            throw new IllegalArgumentException("manual breeding deadline requires a player UUID");
        }
        if (manualBreedingPlayerUuid != null && manualBreedingUntilMs == 0L) {
            throw new IllegalArgumentException("manual breeding player requires a nonzero deadline");
        }
        if (alarm == null) {
            throw new NullPointerException("alarm");
        }
    }

    /** Compatibility marker for a job that has not written a provisional cooldown. */
    @Nonnull
    public static AppliedCooldownFingerprint none() {
        return new AppliedCooldownFingerprint(
                false,
                false,
                0L,
                0L,
                0L,
                null,
                0L,
                null,
                0L,
                ParentBreedingSnapshot.AlarmSnapshot.missing()
        );
    }
}
