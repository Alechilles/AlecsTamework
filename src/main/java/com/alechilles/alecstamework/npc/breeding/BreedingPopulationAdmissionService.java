package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure hard-cap admission policy for pre-rolled breeding birth plans.
 *
 * <p>Nearby capacity is enforced independently per canonical population type. Claim and player
 * headroom are total-child constraints. Active manual and passive reservations share the same
 * input and therefore block one another identically. This service does not mutate game state or
 * retain reservations; callers store the exact immutable reservation returned in the result.
 */
public final class BreedingPopulationAdmissionService {
    /** Evaluates initial admission while counting every supplied active reservation. */
    @Nonnull
    public AdmissionResult admit(@Nonnull AdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        return evaluate(request, null, request.plan().children());
    }

    /**
     * Rechecks capacity at spawn while excluding the current job's already-counted reservation.
     * The supplied children must be the exact ordered children admitted for this job, so a recheck
     * can retain or shrink an admission but can never expand it when capacity later increases.
     */
    @Nonnull
    public AdmissionResult recheckAtSpawn(@Nonnull AdmissionRequest request,
                                          @Nonnull List<PlannedChild> initiallyAdmittedChildren) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(initiallyAdmittedChildren, "initiallyAdmittedChildren");
        List<PlannedChild> candidates = List.copyOf(initiallyAdmittedChildren);
        if (!AdmissionResult.isOrderedSubsequence(request.plan().children(), candidates)) {
            throw new IllegalArgumentException(
                    "initiallyAdmittedChildren must preserve source plan order"
            );
        }
        return evaluate(request, request.jobId(), candidates);
    }

    @Nonnull
    private AdmissionResult evaluate(AdmissionRequest request,
                                     @Nullable UUID excludedJobId,
                                     List<PlannedChild> candidateChildren) {
        Objects.requireNonNull(request, "request");
        ReservationTotals reservations = aggregateReservations(request, excludedJobId);
        long claimHeadroom = remainingTotalHeadroom(request.claimHeadroom(), reservations.claimChildren());
        long playerHeadroom = remainingTotalHeadroom(request.playerHeadroom(), reservations.playerChildren());
        long totalHeadroom = Math.min(claimHeadroom, playerHeadroom);
        List<PlannedChild> admitted = admitInPlanOrder(
                request,
                candidateChildren,
                reservations.byPopulationType(),
                totalHeadroom
        );
        ActiveReservation reservation = new ActiveReservation(
                request.jobId(),
                countByPopulationType(admitted)
        );
        return new AdmissionResult(
                request.plan(),
                admitted,
                reservation,
                toDisplayHeadroom(claimHeadroom),
                toDisplayHeadroom(playerHeadroom),
                toDisplayHeadroom(totalHeadroom)
        );
    }

    @Nonnull
    private List<PlannedChild> admitInPlanOrder(AdmissionRequest request,
                                                List<PlannedChild> candidateChildren,
                                                Map<String, Long> reservedByType,
                                                long totalHeadroom) {
        if (totalHeadroom <= 0L || candidateChildren.isEmpty()) {
            return List.of();
        }
        Map<String, Long> remainingByType = new LinkedHashMap<>();
        List<PlannedChild> admitted = new ArrayList<>();
        for (PlannedChild child : candidateChildren) {
            if (admitted.size() >= totalHeadroom) {
                break;
            }
            String populationType = child.populationType();
            long remaining = remainingByType.computeIfAbsent(
                    populationType,
                    ignored -> nearbyHeadroom(request, reservedByType, populationType)
            );
            if (remaining <= 0L) {
                continue;
            }
            admitted.add(child);
            if (remaining != Long.MAX_VALUE) {
                remainingByType.put(populationType, remaining - 1L);
            }
        }
        return List.copyOf(admitted);
    }

    private long nearbyHeadroom(AdmissionRequest request,
                                Map<String, Long> reservedByType,
                                String populationType) {
        if (request.maxNearby() <= 0) {
            return Long.MAX_VALUE;
        }
        long live = request.liveNearbyByPopulationType().getOrDefault(populationType, 0);
        long reserved = reservedByType.getOrDefault(populationType, 0L);
        long used = saturatingAddNonNegative(live, reserved);
        return Math.max(0L, (long) request.maxNearby() - used);
    }

    private static long remainingTotalHeadroom(OptionalInt configuredHeadroom, long reservedChildren) {
        if (configuredHeadroom.isEmpty()) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, (long) configuredHeadroom.getAsInt() - reservedChildren);
    }

    private static ReservationTotals aggregateReservations(AdmissionRequest request,
                                                           @Nullable UUID excludedJobId) {
        Map<String, Long> byType = aggregateNearbyReservations(
                request.nearbyReservations(),
                excludedJobId
        );
        long claimTotal = aggregateTotalReservations(request.claimReservations(), excludedJobId);
        long playerTotal = aggregateTotalReservations(request.playerReservations(), excludedJobId);
        return new ReservationTotals(byType, claimTotal, playerTotal);
    }

    private static Map<String, Long> aggregateNearbyReservations(List<ActiveReservation> reservations,
                                                                 @Nullable UUID excludedJobId) {
        Map<String, Long> byType = new LinkedHashMap<>();
        for (ActiveReservation reservation : reservations) {
            if (excludedJobId != null && excludedJobId.equals(reservation.jobId())) {
                continue;
            }
            for (Map.Entry<String, Integer> entry : reservation.countsByPopulationType().entrySet()) {
                byType.merge(
                        entry.getKey(),
                        (long) entry.getValue(),
                        BreedingPopulationAdmissionService::saturatingAddNonNegative
                );
            }
        }
        return Collections.unmodifiableMap(byType);
    }

    private static long aggregateTotalReservations(List<ActiveReservation> reservations,
                                                   @Nullable UUID excludedJobId) {
        long total = 0L;
        for (ActiveReservation reservation : reservations) {
            if (excludedJobId == null || !excludedJobId.equals(reservation.jobId())) {
                total = saturatingAddNonNegative(total, reservation.totalChildren());
            }
        }
        return total;
    }

    private static Map<String, Integer> countByPopulationType(List<PlannedChild> children) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PlannedChild child : children) {
            counts.merge(child.populationType(), 1, Math::addExact);
        }
        return immutableNormalizedCounts(counts, "admitted counts");
    }

    private static long saturatingAddNonNegative(long left, long right) {
        if (left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static int toDisplayHeadroom(long headroom) {
        return headroom >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, headroom);
    }

    private static Map<String, Integer> immutableNormalizedCounts(Map<String, Integer> source, String label) {
        Objects.requireNonNull(source, label);
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String key = canonicalPopulationType(entry.getKey());
            Integer count = Objects.requireNonNull(entry.getValue(), label + " count");
            if (count < 0) {
                throw new IllegalArgumentException(label + " must not contain negative counts");
            }
            if (count == 0) {
                continue;
            }
            normalized.merge(key, count, BreedingPopulationAdmissionService::saturatingAddCount);
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static int saturatingAddCount(int left, int right) {
        long total = (long) left + (long) right;
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private static String canonicalPopulationType(String value) {
        Objects.requireNonNull(value, "populationType");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("populationType must not be blank");
        }
        return normalized;
    }

    /** Breeding entrypoint label; admission intentionally does not branch on this value. */
    public enum BreedingMode {
        MANUAL,
        PASSIVE
    }

    /** Exact active reservation owned by one admitted birth job. */
    public record ActiveReservation(@Nonnull UUID jobId,
                                    @Nonnull Map<String, Integer> countsByPopulationType) {
        public ActiveReservation {
            Objects.requireNonNull(jobId, "jobId");
            countsByPopulationType = immutableNormalizedCounts(countsByPopulationType, "reservation counts");
        }

        /** Returns the exact number of child slots reserved by this job. */
        public int totalChildren() {
            long total = 0L;
            for (int count : countsByPopulationType.values()) {
                total = saturatingAddNonNegative(total, count);
            }
            return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        }
    }

    /**
     * Immutable input snapshot for initial admission or spawn-time recheck.
     *
     * <p>Claim and player headroom are measured before the supplied breeding reservations;
     * {@link OptionalInt#empty()} means that constraint is unlimited. Reservation lists are
     * scope-specific so out-of-radius, different-claim, and different-owner jobs are not charged
     * against unrelated constraints.
     */
    public record AdmissionRequest(@Nonnull UUID jobId,
                                   @Nonnull BreedingMode mode,
                                   @Nonnull BreedingBirthPlan plan,
                                   int maxNearby,
                                   @Nonnull Map<String, Integer> liveNearbyByPopulationType,
                                   @Nonnull List<ActiveReservation> nearbyReservations,
                                   @Nonnull List<ActiveReservation> claimReservations,
                                   @Nonnull List<ActiveReservation> playerReservations,
                                   @Nonnull OptionalInt claimHeadroom,
                                   @Nonnull OptionalInt playerHeadroom) {
        public AdmissionRequest {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(plan, "plan");
            liveNearbyByPopulationType = immutableNormalizedCounts(
                    liveNearbyByPopulationType,
                    "live nearby counts"
            );
            nearbyReservations = immutableUniqueReservations(nearbyReservations, "nearbyReservations");
            claimReservations = immutableUniqueReservations(claimReservations, "claimReservations");
            playerReservations = immutableUniqueReservations(playerReservations, "playerReservations");
            claimHeadroom = requireNonNegative(claimHeadroom, "claimHeadroom");
            playerHeadroom = requireNonNegative(playerHeadroom, "playerHeadroom");
        }

        /** Uses one already-filtered reservation set for all three scopes. */
        public AdmissionRequest(@Nonnull UUID jobId,
                                @Nonnull BreedingMode mode,
                                @Nonnull BreedingBirthPlan plan,
                                int maxNearby,
                                @Nonnull Map<String, Integer> liveNearbyByPopulationType,
                                @Nonnull List<ActiveReservation> activeReservations,
                                @Nonnull OptionalInt claimHeadroom,
                                @Nonnull OptionalInt playerHeadroom) {
            this(
                    jobId,
                    mode,
                    plan,
                    maxNearby,
                    liveNearbyByPopulationType,
                    activeReservations,
                    activeReservations,
                    activeReservations,
                    claimHeadroom,
                    playerHeadroom
            );
        }

        private static List<ActiveReservation> immutableUniqueReservations(
                List<ActiveReservation> reservations,
                String label) {
            Objects.requireNonNull(reservations, label);
            List<ActiveReservation> copy = List.copyOf(reservations);
            Set<UUID> jobIds = new HashSet<>();
            for (ActiveReservation reservation : copy) {
                if (!jobIds.add(reservation.jobId())) {
                    throw new IllegalArgumentException(label + " must contain unique job IDs");
                }
            }
            return copy;
        }

        private static OptionalInt requireNonNegative(OptionalInt value, String label) {
            Objects.requireNonNull(value, label);
            if (value.isPresent() && value.getAsInt() < 0) {
                throw new IllegalArgumentException(label + " must not be negative");
            }
            return value;
        }
    }

    /** Immutable admission decision and exact reservation for the admitted child subsequence. */
    public record AdmissionResult(@Nonnull BreedingBirthPlan sourcePlan,
                                  @Nonnull List<PlannedChild> admittedChildren,
                                  @Nonnull ActiveReservation reservation,
                                  int availableClaimHeadroom,
                                  int availablePlayerHeadroom,
                                  int combinedTotalHeadroom) {
        public AdmissionResult {
            Objects.requireNonNull(sourcePlan, "sourcePlan");
            Objects.requireNonNull(admittedChildren, "admittedChildren");
            admittedChildren = List.copyOf(admittedChildren);
            Objects.requireNonNull(reservation, "reservation");
            if (!isOrderedSubsequence(sourcePlan.children(), admittedChildren)) {
                throw new IllegalArgumentException("admitted children must preserve source plan order");
            }
            if (!countByPopulationType(admittedChildren).equals(reservation.countsByPopulationType())) {
                throw new IllegalArgumentException("reservation counts must match admitted children");
            }
            if (availableClaimHeadroom < 0 || availablePlayerHeadroom < 0 || combinedTotalHeadroom < 0) {
                throw new IllegalArgumentException("reported headroom must not be negative");
            }
        }

        public int admittedCount() {
            return admittedChildren.size();
        }

        public boolean admittedAny() {
            return !admittedChildren.isEmpty();
        }

        private static boolean isOrderedSubsequence(List<PlannedChild> source, List<PlannedChild> candidate) {
            int candidateIndex = 0;
            for (PlannedChild child : source) {
                if (candidateIndex < candidate.size() && child.equals(candidate.get(candidateIndex))) {
                    candidateIndex++;
                }
            }
            return candidateIndex == candidate.size();
        }
    }

    private record ReservationTotals(Map<String, Long> byPopulationType,
                                     long claimChildren,
                                     long playerChildren) {
    }
}
