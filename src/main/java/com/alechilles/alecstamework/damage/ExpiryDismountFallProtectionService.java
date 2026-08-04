package com.alechilles.alecstamework.damage;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;

/**
 * Holds the short-lived fall-damage protection armed by a lease-expiry dismount.
 */
public final class ExpiryDismountFallProtectionService {
    public static final long MAXIMUM_PROTECTION_MS = 60_000L;
    private static final long FIRST_LANDING_ELIGIBLE_DELAY_MS = 250L;
    private static final ExpiryDismountFallProtectionService INSTANCE =
            new ExpiryDismountFallProtectionService();

    private final ConcurrentMap<UUID, Protection> protections =
            new ConcurrentHashMap<>();

    @Nonnull
    public static ExpiryDismountFallProtectionService getInstance() {
        return INSTANCE;
    }

    public void arm(@Nonnull UUID playerUuid, long nowMs) {
        protections.put(playerUuid, new Protection(
                nowMs, nowMs + MAXIMUM_PROTECTION_MS));
    }

    public boolean isProtected(@Nonnull UUID playerUuid, long nowMs) {
        Protection protection = protections.get(playerUuid);
        if (protection == null) return false;
        if (nowMs < protection.expiresAtMs()) return true;
        protections.remove(playerUuid, protection);
        return false;
    }

    /** Clears an armed protection after the dismounted player is observed grounded. */
    public boolean clearWhenGrounded(@Nonnull UUID playerUuid, long nowMs) {
        Protection protection = protections.get(playerUuid);
        if (protection == null || nowMs >= protection.expiresAtMs()
                || nowMs - protection.armedAtMs()
                        < FIRST_LANDING_ELIGIBLE_DELAY_MS) {
            return false;
        }
        return protections.remove(playerUuid, protection);
    }

    public void clear(@Nonnull UUID playerUuid) {
        protections.remove(playerUuid);
    }

    private record Protection(long armedAtMs, long expiresAtMs) {
    }
}
