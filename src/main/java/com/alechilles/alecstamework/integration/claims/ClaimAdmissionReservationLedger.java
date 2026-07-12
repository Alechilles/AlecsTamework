package com.alechilles.alecstamework.integration.claims;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Maintains the correlated token, profile, and claim indexes for pending claim reservations.
 *
 * <p>The admission service owns synchronization. Keeping the indexes together makes every
 * registration and removal one cohesive operation and prevents their accounting from drifting.</p>
 */
final class ClaimAdmissionReservationLedger {
    private final Map<UUID, PendingAdmission> pendingByToken = new HashMap<>();
    private final Map<String, UUID> pendingTokenByProfile = new HashMap<>();
    private final Map<ClaimPopulationKey, Long> pendingByClaim = new HashMap<>();

    void register(@Nonnull ClaimAdmissionReservation reservation, long expiresAtNanos) {
        PendingAdmission pending = new PendingAdmission(reservation, expiresAtNanos);
        pendingByToken.put(reservation.tokenId(), pending);
        for (ClaimOccupancyTransition transition : reservation.transitions()) {
            pendingTokenByProfile.put(transition.profileId(), reservation.tokenId());
        }
        if (reservation.targetClaimKey() != null && reservation.reservedSlots() > 0L) {
            pendingByClaim.merge(reservation.targetClaimKey(), reservation.reservedSlots(), Long::sum);
        }
    }

    @Nullable
    PendingAdmission find(@Nonnull ClaimAdmissionReservation reservation) {
        PendingAdmission pending = pendingByToken.get(reservation.tokenId());
        return pending != null && pending.reservation() == reservation ? pending : null;
    }

    boolean hasPendingProfile(@Nonnull String profileId) {
        return pendingTokenByProfile.containsKey(profileId);
    }

    long pendingForClaim(@Nonnull ClaimPopulationKey key) {
        return pendingByClaim.getOrDefault(key, 0L);
    }

    int pendingCount() {
        return pendingByToken.size();
    }

    long pendingSlots() {
        long slots = 0L;
        for (PendingAdmission pending : pendingByToken.values()) {
            slots += pending.reservation().reservedSlots();
        }
        return slots;
    }

    @Nonnull
    List<PendingAdmission> pendingAdmissions() {
        return List.copyOf(pendingByToken.values());
    }

    void remove(@Nonnull PendingAdmission pending) {
        ClaimAdmissionReservation reservation = pending.reservation();
        pendingByToken.remove(reservation.tokenId(), pending);
        for (ClaimOccupancyTransition transition : reservation.transitions()) {
            pendingTokenByProfile.remove(transition.profileId(), reservation.tokenId());
        }
        if (reservation.targetClaimKey() == null || reservation.reservedSlots() <= 0L) {
            return;
        }
        long updated = pendingForClaim(reservation.targetClaimKey()) - reservation.reservedSlots();
        if (updated < 0L) {
            throw new IllegalStateException("Claim pending population underflow.");
        }
        if (updated == 0L) {
            pendingByClaim.remove(reservation.targetClaimKey());
        } else {
            pendingByClaim.put(reservation.targetClaimKey(), updated);
        }
    }

    record PendingAdmission(@Nonnull ClaimAdmissionReservation reservation, long expiresAtNanos) {
    }
}
