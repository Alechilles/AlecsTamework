package com.alechilles.alecstamework.integration.claims;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/** Pure classification and generation-matching rules shared by claim admission operations. */
final class ClaimAdmissionRules {
    private ClaimAdmissionRules() {
    }

    static boolean transitionsKnownNonPositive(List<ClaimOccupancyTransition> transitions) {
        for (ClaimOccupancyTransition transition : transitions) {
            if (!transition.isKnownNonPositiveAtSameLocation()) {
                return false;
            }
        }
        return true;
    }

    static long pessimisticSlots(ClaimAdmissionRequest request) {
        long slots = 0L;
        for (ClaimOccupancyTransition transition : request.transitions()) {
            if (transition.proposed().occupiesClaim()
                    && !transition.isKnownNonPositiveAtSameLocation()) {
                slots++;
            }
        }
        return slots;
    }

    static TransitionDelta analyzeTransitions(List<ClaimOccupancyTransition> transitions,
                                              Set<String> currentTargetProfiles) {
        Set<String> futureProfiles = new HashSet<>(currentTargetProfiles);
        for (ClaimOccupancyTransition transition : transitions) {
            futureProfiles.remove(transition.profileId());
        }
        for (ClaimOccupancyTransition transition : transitions) {
            if (transition.proposed().occupiesClaim()) {
                futureProfiles.add(transition.profileId());
            }
        }
        long departures = currentTargetProfiles.stream().filter(profile -> !futureProfiles.contains(profile)).count();
        long arrivals = futureProfiles.stream().filter(profile -> !currentTargetProfiles.contains(profile)).count();
        return new TransitionDelta(arrivals, departures);
    }

    static boolean sameStoredPolicy(ClaimAdmissionReservation reservation,
                                    ClaimPolicyContext current) {
        return reservation.providerId().equals(current.providerId())
                && reservation.providerGeneration().equals(current.providerGeneration())
                && reservation.settingsRevision() == current.settingsRevision();
    }

    static boolean samePolicy(ClaimPolicyContext first, ClaimPolicyContext second) {
        return first.providerId().equals(second.providerId())
                && first.providerGeneration().equals(second.providerGeneration())
                && first.settingsRevision() == second.settingsRevision();
    }

    static boolean sameTopology(ClaimAdmissionReservation reservation,
                                @Nullable ClaimResolution refreshed) {
        if (refreshed == null || reservation.topologyStatus() != refreshed.status()) {
            return false;
        }
        if (refreshed.status() != ClaimLookupResult.Status.CLAIM_FOUND) {
            return refreshed.status() == ClaimLookupResult.Status.NO_CLAIM;
        }
        String digest = refreshed.footprint() == null ? null : refreshed.footprint().digest();
        return Objects.equals(reservation.targetClaimKey(), refreshed.key())
                && Objects.equals(reservation.footprintDigest(), digest);
    }

    record TransitionDelta(long arrivals, long departures) {
    }
}
