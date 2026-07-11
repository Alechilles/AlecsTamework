package com.alechilles.alecstamework.npc.breeding;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable pre-job breeding state for one parent.
 *
 * <p>World-time values remain signed. Manual selection remains a separate wall-clock deadline and
 * is retained exactly so cancellation can decide whether it is still valid before restoration.
 */
public record ParentBreedingSnapshot(@Nullable String configId,
                                     double happiness,
                                     long lastHappinessUpdateMs,
                                     boolean ready,
                                     boolean enabled,
                                     long cooldownUntilMs,
                                     long cooldownStartedAtMs,
                                     long cooldownDurationMs,
                                     @Nullable UUID lastPartnerUuid,
                                     @Nullable UUID manualBreedingPlayerUuid,
                                     long manualBreedingUntilMs,
                                     @Nonnull AlarmSnapshot alarm) {
    public ParentBreedingSnapshot {
        configId = normalizeOptional(configId);
        if (!Double.isFinite(happiness)) {
            throw new IllegalArgumentException("happiness must be finite");
        }
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

    /** Empty compatibility state used by legacy job factories. */
    @Nonnull
    public static ParentBreedingSnapshot empty() {
        return new ParentBreedingSnapshot(
                null,
                0.0,
                0L,
                false,
                false,
                0L,
                0L,
                0L,
                null,
                null,
                0L,
                AlarmSnapshot.missing()
        );
    }

    @Nullable
    private static String normalizeOptional(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** Exact state of the role-defined breeding alarm before provisional cooldown mutation. */
    public record AlarmSnapshot(boolean exists, boolean set, long untilMs) {
        public AlarmSnapshot {
            if (!exists && set) {
                throw new IllegalArgumentException("A missing alarm cannot be set");
            }
            if (!set && untilMs != 0L) {
                throw new IllegalArgumentException("An unset alarm must use the zero deadline sentinel");
            }
        }

        @Nonnull
        public static AlarmSnapshot missing() {
            return new AlarmSnapshot(false, false, 0L);
        }

        @Nonnull
        public static AlarmSnapshot unset() {
            return new AlarmSnapshot(true, false, 0L);
        }

        @Nonnull
        public static AlarmSnapshot set(long untilMs) {
            if (untilMs == 0L) {
                throw new IllegalArgumentException("A set alarm requires a nonzero signed deadline");
            }
            return new AlarmSnapshot(true, true, untilMs);
        }
    }
}
