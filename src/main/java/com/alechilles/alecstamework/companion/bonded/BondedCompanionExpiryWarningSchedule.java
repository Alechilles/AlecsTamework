package com.alechilles.alecstamework.companion.bonded;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;

import java.util.Optional;

/**
 * Determines which, if any, expiry warning should be shown for a finite companion lease.
 */
public final class BondedCompanionExpiryWarningSchedule {

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

    /** Returns the configured companion effect only for the 30-second visual warning. */
    public static Optional<String> modelEffectId(
            Warning warning, String configuredEffectId
    ) {
        if (warning == null || warning.secondsRemaining() != 30
                || configuredEffectId == null) {
            return Optional.empty();
        }
        String effectId = configuredEffectId.trim();
        return effectId.isEmpty() ? Optional.empty() : Optional.of(effectId);
    }

    public record Warning(int secondsRemaining, NotificationStyle style) {
    }
}
