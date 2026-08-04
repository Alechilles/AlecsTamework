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

        int secondsRemaining = (int) Math.floorDiv(expiresAtMs - nowMs, 1_000L);
        return switch (secondsRemaining) {
            case 60, 30, 10 -> Optional.of(new Warning(secondsRemaining, NotificationStyle.Warning));
            case 5, 4, 3, 2, 1 -> Optional.of(new Warning(secondsRemaining, NotificationStyle.Danger));
            default -> Optional.empty();
        };
    }

    public record Warning(int secondsRemaining, NotificationStyle style) {
    }
}
