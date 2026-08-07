package com.alechilles.alecstamework.companion.bonded;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;

import java.util.Optional;

/**
 * Determines which, if any, expiry warning should be shown for a finite companion lease.
 */
public final class BondedCompanionExpiryWarningSchedule {
    private static final float EXPIRY_CLEANUP_GRACE_SECONDS = 2F;

    private BondedCompanionExpiryWarningSchedule() {
    }

    public static Optional<Warning> warning(long expiresAtMs, long nowMs) {
        if (expiresAtMs <= 0L || expiresAtMs <= nowMs) {
            return Optional.empty();
        }

        long secondsRemaining = Math.floorDiv(expiresAtMs - nowMs, 1_000L);
        if (secondsRemaining > 60L) {
            return Optional.empty();
        }
        int remainingSeconds = (int) secondsRemaining;
        return switch (remainingSeconds) {
            case 60, 30, 10 -> Optional.of(new Warning(
                    remainingSeconds, NotificationStyle.Warning));
            case 5, 4, 3, 2, 1 -> Optional.of(new Warning(
                    remainingSeconds, NotificationStyle.Danger));
            default -> Optional.empty();
        };
    }

    /** Returns the configured companion effect only for the 5-second visual warning. */
    public static Optional<String> modelEffectId(
            Warning warning, String configuredEffectId
    ) {
        if (warning == null || warning.secondsRemaining() != 5
                || configuredEffectId == null) {
            return Optional.empty();
        }
        String effectId = configuredEffectId.trim();
        return effectId.isEmpty() ? Optional.empty() : Optional.of(effectId);
    }

    /** Keeps a completed disappearance effect active until lease cleanup removes its target. */
    public static float effectDurationSeconds(
            long expiresAtMs, long nowMs, float configuredDuration
    ) {
        float minimumDuration = Math.max(0.05F, configuredDuration);
        if (expiresAtMs <= nowMs) {
            return minimumDuration;
        }
        float remainingLeaseSeconds = (expiresAtMs - nowMs) / 1_000F;
        return Math.max(minimumDuration,
                remainingLeaseSeconds + EXPIRY_CLEANUP_GRACE_SECONDS);
    }

    public record Warning(int secondsRemaining, NotificationStyle style) {
    }
}
