package com.alechilles.alecstamework.npc.breeding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared hard-cap admission policy for pre-rolled manual and passive breeding plans.
 *
 * <p>Each decision takes one deterministic snapshot of every active registry reservation. Nearby
 * reservations are filtered by world, distance, and canonical population type. Claim and player
 * reservations are charged only to matching immutable scopes. Spawn-time rechecks exclude the
 * current job and can only retain or shrink its existing ordered child admission.
 */
public final class BreedingPopulationAdmissionService {
    private final BreedingBirthJobRegistry registry;

    /** Uses the shared registry supplied by the plugin-owned breeding service seam. */
    public BreedingPopulationAdmissionService(@Nonnull BreedingBirthJobRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Evaluates initial admission against all active manual and passive reservations. */
    @Nonnull
    public AdmissionResult admit(@Nonnull AdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        return evaluate(request, request.plan().children(), null);
    }

    /**
     * Evaluates a replay-safe ordered subset of the immutable plan.
     *
     * <p>Already committed children are omitted by restart replay while the full plan remains the
     * durable job identity. The subset may only preserve order and shrink the source plan.</p>
     */
    @Nonnull
    public AdmissionResult admit(@Nonnull AdmissionRequest request,
                                 @Nonnull List<PlannedChild> candidateChildren) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidateChildren, "candidateChildren");
        List<PlannedChild> candidates = List.copyOf(candidateChildren);
        if (!BreedingJobAdmission.isOrderedSubsequence(request.plan().children(), candidates)) {
            throw new IllegalArgumentException("Candidate children must preserve source plan order");
        }
        return evaluate(request, candidates, null);
    }

    /**
     * Rechecks every cap while excluding this job's own reservation and preserving shrink-only
     * plan order.
     */
    @Nonnull
    public AdmissionResult recheckAtSpawn(@Nonnull AdmissionRequest request,
                                          @Nonnull BreedingJobAdmission currentAdmission) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(currentAdmission, "currentAdmission");
        if (!request.reservationScope().equals(currentAdmission.reservation().scope())) {
            throw new IllegalArgumentException("Current admission must use the request reservation scope");
        }
        if (!BreedingJobAdmission.isOrderedSubsequence(
                request.plan().children(),
                currentAdmission.children()
        )) {
            throw new IllegalArgumentException("Current admission must preserve source plan order");
        }
        return evaluate(request, currentAdmission.children(), request.jobId());
    }

    @Nonnull
    private AdmissionResult evaluate(AdmissionRequest request,
                                     List<PlannedChild> candidates,
                                     @Nullable UUID excludedJobId) {
        List<BreedingActiveReservation> activeReservations = registry.activeReservations();
        ReservationUsage usage = ReservationUsage.calculate(request, activeReservations, excludedJobId);
        long claimHeadroom = remaining(
                request.capacityHeadroom().claimHeadroom(),
                usage.claimChildren()
        );
        PlayerHeadroom playerHeadroom = calculatePlayerHeadroom(request, usage);
        long combinedTotal = Math.min(claimHeadroom, playerHeadroom.minimum());
        List<PlannedChild> admittedChildren = admitInPlanOrder(
                request,
                candidates,
                usage.nearbyByPopulationType(),
                combinedTotal
        );
        BreedingJobAdmission admission = BreedingJobAdmission.of(
                admittedChildren,
                request.reservationScope()
        );
        return new AdmissionResult(
                request.plan(),
                admission,
                toDisplayHeadroom(claimHeadroom),
                toDisplayHeadroom(playerHeadroom.minimum()),
                toDisplayHeadroom(combinedTotal),
                playerHeadroom.displayByScope()
        );
    }

    @Nonnull
    private List<PlannedChild> admitInPlanOrder(AdmissionRequest request,
                                                List<PlannedChild> candidates,
                                                Map<String, Long> reservedNearbyByType,
                                                long totalHeadroom) {
        if (totalHeadroom <= 0L || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Long> remainingByType = new LinkedHashMap<>();
        ArrayList<PlannedChild> admitted = new ArrayList<>();
        for (PlannedChild child : candidates) {
            if (admitted.size() >= totalHeadroom) {
                break;
            }
            String populationType = child.populationType();
            long remaining = remainingByType.computeIfAbsent(
                    populationType,
                    ignored -> nearbyHeadroom(request, reservedNearbyByType, populationType)
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
                                Map<String, Long> reservedNearbyByType,
                                String populationType) {
        if (request.maxNearby() <= 0) {
            return Long.MAX_VALUE;
        }
        long live = request.liveNearbyByPopulationType().getOrDefault(populationType, 0);
        long reserved = reservedNearbyByType.getOrDefault(populationType, 0L);
        long used = saturatingAdd(live, reserved);
        return Math.max(0L, (long) request.maxNearby() - used);
    }

    private static PlayerHeadroom calculatePlayerHeadroom(AdmissionRequest request,
                                                           ReservationUsage usage) {
        if (request.capacityHeadroom().playerHeadroomByScope().isEmpty()) {
            return new PlayerHeadroom(Long.MAX_VALUE, Map.of());
        }
        long minimum = Long.MAX_VALUE;
        Map<BreedingPlayerCapacityScope, Integer> displayByScope = new LinkedHashMap<>();
        for (Map.Entry<BreedingPlayerCapacityScope, Integer> entry
                : request.capacityHeadroom().playerHeadroomByScope().entrySet()) {
            long reserved = usage.playerChildrenByScope().getOrDefault(entry.getKey(), 0L);
            long available = Math.max(0L, (long) entry.getValue() - reserved);
            minimum = Math.min(minimum, available);
            displayByScope.put(entry.getKey(), toDisplayHeadroom(available));
        }
        return new PlayerHeadroom(minimum, Collections.unmodifiableMap(displayByScope));
    }

    private static long remaining(OptionalInt liveHeadroom, long reservedChildren) {
        return liveHeadroom.isEmpty()
                ? Long.MAX_VALUE
                : Math.max(0L, (long) liveHeadroom.getAsInt() - reservedChildren);
    }

    private static long saturatingAdd(long left, long right) {
        return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static int toDisplayHeadroom(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    private static Map<String, Integer> immutableCounts(Map<String, Integer> source, String label) {
        Objects.requireNonNull(source, label);
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String type = PlannedChild.canonicalPopulationType(entry.getKey());
            Integer count = Objects.requireNonNull(entry.getValue(), label + " count");
            if (count < 0) {
                throw new IllegalArgumentException(label + " must not contain negative counts");
            }
            if (count > 0) {
                normalized.merge(type, count, Math::addExact);
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    /** Breeding entrypoint label; both modes intentionally use the identical admission path. */
    public enum BreedingMode {
        MANUAL,
        PASSIVE
    }

    /** Immutable capacity input measured for one plan at its intended birth anchor. */
    public record AdmissionRequest(
            @Nonnull UUID jobId,
            @Nonnull String worldId,
            @Nonnull BreedingMode mode,
            @Nonnull BreedingBirthPlan plan,
            @Nonnull BreedingBirthAnchor anchor,
            @Nonnull BreedingReservationScope reservationScope,
            int maxNearby,
            @Nonnull Map<String, Integer> liveNearbyByPopulationType,
            @Nonnull BreedingCapacityHeadroom capacityHeadroom) {
        public AdmissionRequest {
            Objects.requireNonNull(jobId, "jobId");
            worldId = requireNonBlank(worldId, "worldId");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(reservationScope, "reservationScope");
            liveNearbyByPopulationType = immutableCounts(
                    liveNearbyByPopulationType,
                    "live nearby counts"
            );
            Objects.requireNonNull(capacityHeadroom, "capacityHeadroom");
            validateScopeConsistency(worldId, reservationScope, maxNearby, capacityHeadroom);
        }

        private static void validateScopeConsistency(String worldId,
                                                     BreedingReservationScope reservationScope,
                                                     int maxNearby,
                                                     BreedingCapacityHeadroom headroom) {
            if (maxNearby > 0 && reservationScope.nearbyRadius() <= 0.0) {
                throw new IllegalArgumentException("A finite nearby cap requires a positive radius");
            }
            BreedingClaimCapacityScope claimScope = reservationScope.claimScope();
            if (headroom.claimHeadroom().isPresent() && claimScope == null) {
                throw new IllegalArgumentException("Finite claim headroom requires a claim scope");
            }
            if (claimScope != null && !worldId.equals(claimScope.worldId())) {
                throw new IllegalArgumentException("Claim scope world must match request world");
            }
            for (BreedingPlayerCapacityScope playerScope : reservationScope.playerScopes()) {
                if (playerScope.scope() == BreedingPlayerCapacityScope.Scope.PER_WORLD
                        && !worldId.equals(playerScope.worldId())) {
                    throw new IllegalArgumentException("Per-world player scope must match request world");
                }
            }
            if (!reservationScope.playerScopes().containsAll(headroom.playerHeadroomByScope().keySet())) {
                throw new IllegalArgumentException("Player headroom scopes must be reserved by this job");
            }
        }

        private static String requireNonBlank(String value, String label) {
            Objects.requireNonNull(value, label);
            String normalized = value.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(label + " must not be blank");
            }
            return normalized;
        }
    }

    /** Immutable shrink-only decision and exact reservation matching the admitted children. */
    public record AdmissionResult(
            @Nonnull BreedingBirthPlan sourcePlan,
            @Nonnull BreedingJobAdmission admission,
            int availableClaimHeadroom,
            int availablePlayerHeadroom,
            int combinedTotalHeadroom,
            @Nonnull Map<BreedingPlayerCapacityScope, Integer> availablePlayerHeadroomByScope) {
        public AdmissionResult {
            Objects.requireNonNull(sourcePlan, "sourcePlan");
            Objects.requireNonNull(admission, "admission");
            if (!BreedingJobAdmission.isOrderedSubsequence(sourcePlan.children(), admission.children())) {
                throw new IllegalArgumentException("Admission must preserve source plan order");
            }
            if (availableClaimHeadroom < 0 || availablePlayerHeadroom < 0 || combinedTotalHeadroom < 0) {
                throw new IllegalArgumentException("Reported headroom must be nonnegative");
            }
            availablePlayerHeadroomByScope = Map.copyOf(availablePlayerHeadroomByScope);
        }

        @Nonnull
        public List<PlannedChild> admittedChildren() {
            return admission.children();
        }

        @Nonnull
        public BreedingBirthReservation reservation() {
            return admission.reservation();
        }

        public int admittedCount() {
            return admission.children().size();
        }

        public boolean admittedAny() {
            return !admission.children().isEmpty();
        }
    }

    private record PlayerHeadroom(long minimum,
                                  Map<BreedingPlayerCapacityScope, Integer> displayByScope) {
    }

    private record ReservationUsage(Map<String, Long> nearbyByPopulationType,
                                    long claimChildren,
                                    Map<BreedingPlayerCapacityScope, Long> playerChildrenByScope) {
        static ReservationUsage calculate(AdmissionRequest request,
                                          List<BreedingActiveReservation> reservations,
                                          @Nullable UUID excludedJobId) {
            Map<String, Long> nearbyByType = new LinkedHashMap<>();
            long claimChildren = 0L;
            Map<BreedingPlayerCapacityScope, Long> playerChildrenByScope = new LinkedHashMap<>();
            for (BreedingActiveReservation active : reservations) {
                if (excludedJobId != null && excludedJobId.equals(active.jobId())) {
                    continue;
                }
                if (isNearby(request, active)) {
                    mergeCounts(nearbyByType, active.reservation().countsByPopulationType());
                }
                if (Objects.equals(
                        request.reservationScope().claimScope(),
                        active.reservation().scope().claimScope()
                ) && request.reservationScope().claimScope() != null) {
                    claimChildren = saturatingAdd(claimChildren, active.reservation().totalChildren());
                }
                for (BreedingPlayerCapacityScope playerScope
                        : request.capacityHeadroom().playerHeadroomByScope().keySet()) {
                    if (active.reservation().scope().playerScopes().contains(playerScope)) {
                        playerChildrenByScope.merge(
                                playerScope,
                                (long) active.reservation().totalChildren(),
                                BreedingPopulationAdmissionService::saturatingAdd
                        );
                    }
                }
            }
            return new ReservationUsage(
                    Collections.unmodifiableMap(nearbyByType),
                    claimChildren,
                    Collections.unmodifiableMap(playerChildrenByScope)
            );
        }

        private static boolean isNearby(AdmissionRequest request, BreedingActiveReservation active) {
            if (request.maxNearby() <= 0 || !request.worldId().equals(active.worldId())) {
                return false;
            }
            double radius = request.reservationScope().nearbyRadius();
            double radiusSquared = radius * radius;
            return request.anchor().distanceSquared(active.anchor()) <= radiusSquared;
        }

        private static void mergeCounts(Map<String, Long> target, Map<String, Integer> source) {
            for (Map.Entry<String, Integer> entry : source.entrySet()) {
                target.merge(
                        entry.getKey(),
                        (long) entry.getValue(),
                        BreedingPopulationAdmissionService::saturatingAdd
                );
            }
        }
    }
}
