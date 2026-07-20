package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stateful but game-object-free planner for one managed-coop runtime sweep.
 *
 * <p>The planner preserves the current roam/capture cadence, one-operation-per-coop behavior,
 * production-on-roam-transition trigger, interaction-state synchronization, and conservative
 * removed-coop check cadence. It does not mutate ECS, vanilla coop state, or persistence.</p>
 */
public final class ManagedCoopRuntimeSweepPlanner {
    static final long CAPTURE_INTERVAL_MS = 350L;
    static final long RELEASE_INTERVAL_MS = 350L;
    static final long REMOVED_COOP_CHECK_INTERVAL_MS = 5_000L;

    public enum Branch {
        NONE,
        CAPTURE,
        RELEASE
    }

    /** Synchronous action for one exact context. Contexts must not cross async callbacks. */
    public record CoopPlan(@Nonnull ManagedCoopContext context,
                           @Nonnull Branch branch,
                           @Nullable ManagedCoopCaptureCandidate candidate,
                           @Nullable ResidentRecord resident,
                           boolean produce,
                           boolean syncInteractionState) {
        public CoopPlan {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(branch, "branch");
            if (branch == Branch.CAPTURE && candidate == null
                    || branch == Branch.RELEASE && resident == null
                    || branch == Branch.NONE && (candidate != null || resident != null)) {
                throw new IllegalArgumentException("coop plan payload does not match branch");
            }
        }
    }

    public record SweepPlan(@Nonnull List<CoopPlan> coops,
                            @Nonnull Set<String> activeCoopKeys,
                            boolean checkRemovedCoops) {
        public SweepPlan {
            coops = List.copyOf(coops);
            activeCoopKeys = Set.copyOf(activeCoopKeys);
        }
    }

    private final OccupancyGateway occupancy;
    private final LifecycleGateway lifecycle;
    @Nullable
    private final ManagedCoopPersistenceGate persistenceGate;
    private final Map<String, Long> nextCaptureAt = new HashMap<>();
    private final Map<String, Long> nextReleaseAt = new HashMap<>();
    private final Map<String, Boolean> lastRoaming = new HashMap<>();
    private long nextRemovedCoopCheckAtMs;

    public ManagedCoopRuntimeSweepPlanner(
            @Nonnull ManagedCoopOccupancyService occupancy,
            @Nonnull ManagedCoopLifecycleAdmissionGuard lifecycle) {
        this(occupancy, lifecycle, resident -> true);
    }

    public ManagedCoopRuntimeSweepPlanner(
            @Nonnull ManagedCoopOccupancyService occupancy,
            @Nonnull ManagedCoopLifecycleAdmissionGuard lifecycle,
            @Nonnull ReleaseEligibilityGateway releaseEligibility) {
        this(occupancyGateway(occupancy, releaseEligibility), lifecycleGateway(lifecycle), null);
    }

    ManagedCoopRuntimeSweepPlanner(
            @Nonnull ManagedCoopOccupancyService occupancy,
            @Nonnull ManagedCoopLifecycleAdmissionGuard lifecycle,
            @Nonnull ReleaseEligibilityGateway releaseEligibility,
            @Nonnull ManagedCoopPersistenceGate persistenceGate) {
        this(occupancyGateway(occupancy, releaseEligibility),
                context -> lifecycle.inspect(context).allowed()
                        && persistenceGate.automation(context).allowed(),
                persistenceGate);
    }

    private static OccupancyGateway occupancyGateway(
            ManagedCoopOccupancyService occupancy,
            ReleaseEligibilityGateway releaseEligibility
    ) {
        ManagedCoopOccupancyService requiredOccupancy =
                Objects.requireNonNull(occupancy, "occupancy");
        ReleaseEligibilityGateway requiredEligibility =
                Objects.requireNonNull(releaseEligibility, "releaseEligibility");
        return new OccupancyGateway() {
            @Override
            public boolean permitsCapture(ManagedCoopContext context,
                                          ManagedCoopCaptureCandidate candidate) {
                return requiredOccupancy.resolveCapturePlacement(
                        context, candidate.npcUuid(), candidate.stableProfileId()).permitted();
            }

            @Nullable
            @Override
            public ResidentRecord firstHoused(ManagedCoopContext context) {
                return requiredOccupancy.firstHousedResident(
                        context, requiredEligibility::permitsRelease);
            }
        };
    }

    private static LifecycleGateway lifecycleGateway(
            ManagedCoopLifecycleAdmissionGuard lifecycle
    ) {
        ManagedCoopLifecycleAdmissionGuard required =
                Objects.requireNonNull(lifecycle, "lifecycle");
        return context -> required.inspect(context).allowed();
    }

    ManagedCoopRuntimeSweepPlanner(@Nonnull OccupancyGateway occupancy) {
        this(occupancy, context -> true, null);
    }

    ManagedCoopRuntimeSweepPlanner(@Nonnull OccupancyGateway occupancy,
                                   @Nonnull LifecycleGateway lifecycle) {
        this(occupancy, lifecycle, null);
    }

    ManagedCoopRuntimeSweepPlanner(@Nonnull OccupancyGateway occupancy,
                                   @Nonnull LifecycleGateway lifecycle,
                                   @Nullable ManagedCoopPersistenceGate persistenceGate) {
        this.occupancy = Objects.requireNonNull(occupancy, "occupancy");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.persistenceGate = persistenceGate;
    }

    /** Fast preflight used to avoid an entity-store scan when every coop is roaming or throttled. */
    public synchronized boolean needsCaptureCandidates(
            @Nonnull List<ManagedCoopContext> contexts,
            int gameHour,
            long nowMs) {
        Objects.requireNonNull(contexts, "contexts");
        if (gameHour < 0 || gameHour > 23) {
            throw new IllegalArgumentException("gameHour must be between 0 and 23");
        }
        for (ManagedCoopContext context : contexts) {
            if (context == null) {
                continue;
            }
            TwCoopConfig.LifecycleRules rules = context.config().getLifecycleRules();
            if (lifecycle.permitsNormalWork(context)
                    && !shouldRoam(gameHour, rules)
                    && rules.isCaptureWildNPCsInRange()
                    && rules.getWildCaptureRadius() > 0.0
                    && nowMs >= nextCaptureAt.getOrDefault(context.coopKey(), 0L)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Plans one sweep from immutable candidate values and a caller-supplied game hour.
     * A reliable empty context scan intentionally schedules removed-coop reconciliation.
     */
    @Nonnull
    public synchronized SweepPlan plan(@Nonnull List<ManagedCoopContext> contexts,
                                       @Nonnull List<ManagedCoopCaptureCandidate> candidates,
                                       int gameHour,
                                       long nowMs,
                                       boolean contextScanReliable) {
        Objects.requireNonNull(contexts, "contexts");
        Objects.requireNonNull(candidates, "candidates");
        if (gameHour < 0 || gameHour > 23) {
            throw new IllegalArgumentException("gameHour must be between 0 and 23");
        }

        LinkedHashSet<String> activeKeys = new LinkedHashSet<>();
        for (ManagedCoopContext context : contexts) {
            if (context != null) {
                activeKeys.add(context.coopKey());
            }
        }
        pruneInactive(activeKeys);
        boolean checkRemoved = contextScanReliable && nowMs >= nextRemovedCoopCheckAtMs;
        if (checkRemoved) {
            nextRemovedCoopCheckAtMs = saturatedAdd(nowMs, REMOVED_COOP_CHECK_INTERVAL_MS);
        }

        HashSet<UUID> consumedCandidates = new HashSet<>();
        ArrayList<CoopPlan> plans = new ArrayList<>(contexts.size());
        for (ManagedCoopContext context : contexts) {
            if (context == null) {
                continue;
            }
            if (!lifecycle.permitsNormalWork(context)) {
                plans.add(none(context, false));
                continue;
            }
            boolean roaming = shouldRoam(gameHour, context.config().getLifecycleRules());
            boolean wasRoaming = lastRoaming.getOrDefault(context.coopKey(), false);
            lastRoaming.put(context.coopKey(), roaming);
            boolean produce = roaming && !wasRoaming;
            plans.add(roaming
                    ? planRelease(context, nowMs, produce)
                    : planCapture(context, candidates, consumedCandidates, nowMs));
        }
        return new SweepPlan(plans, activeKeys, checkRemoved);
    }

    @Nonnull
    private CoopPlan planRelease(ManagedCoopContext context, long nowMs, boolean produce) {
        if (nowMs < nextReleaseAt.getOrDefault(context.coopKey(), 0L)) {
            return none(context, produce);
        }
        nextReleaseAt.put(context.coopKey(), saturatedAdd(nowMs, RELEASE_INTERVAL_MS));
        ResidentRecord resident = occupancy.firstHoused(context);
        if (resident != null && persistenceGate != null
                && !persistenceGate.release(context, resident).allowed()) {
            resident = null;
        }
        return resident == null
                ? none(context, produce)
                : new CoopPlan(context, Branch.RELEASE, null, resident, produce, true);
    }

    @Nonnull
    private CoopPlan planCapture(ManagedCoopContext context,
                                 List<ManagedCoopCaptureCandidate> candidates,
                                 Set<UUID> consumed,
                                 long nowMs) {
        TwCoopConfig.LifecycleRules rules = context.config().getLifecycleRules();
        if (nowMs < nextCaptureAt.getOrDefault(context.coopKey(), 0L)) {
            return none(context, false);
        }
        nextCaptureAt.put(context.coopKey(), saturatedAdd(nowMs, CAPTURE_INTERVAL_MS));
        if (!rules.isCaptureWildNPCsInRange()) {
            return none(context, false);
        }
        ManagedCoopCaptureCandidate candidate = nearestEligible(context, candidates, consumed);
        if (candidate == null) {
            return none(context, false);
        }
        if (persistenceGate != null && !persistenceGate.intake(
                context, candidate.stableProfileId(), null, true).allowed()) {
            return none(context, false);
        }
        consumed.add(candidate.npcUuid());
        return new CoopPlan(context, Branch.CAPTURE, candidate, null, false, true);
    }

    @Nullable
    private ManagedCoopCaptureCandidate nearestEligible(
            ManagedCoopContext context,
            List<ManagedCoopCaptureCandidate> candidates,
            Set<UUID> consumed) {
        TwCoopConfig.LifecycleRules rules = context.config().getLifecycleRules();
        double radius = rules.getWildCaptureRadius();
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return null;
        }
        Set<String> acceptedRoles = normalizedRoles(rules.getAcceptedRoleIds());
        boolean requireTamed = context.config().getCapturePolicy().isRequireTamed();
        double centerX = context.authorityKey().x() + 0.5;
        double centerY = context.authorityKey().y() + 0.5;
        double centerZ = context.authorityKey().z() + 0.5;
        double radiusSquared = radius * radius;
        double bestDistance = Double.POSITIVE_INFINITY;
        ManagedCoopCaptureCandidate best = null;
        for (ManagedCoopCaptureCandidate candidate : candidates) {
            if (candidate == null || consumed.contains(candidate.npcUuid())
                    || requireTamed && !candidate.tamed()
                    || !acceptedRoles.isEmpty() && !acceptedRoles.contains(candidate.roleId())) {
                continue;
            }
            double dx = candidate.x() - centerX;
            double dy = candidate.y() - centerY;
            double dz = candidate.z() - centerZ;
            double distance = dx * dx + dy * dy + dz * dz;
            if (!Double.isFinite(distance) || distance > radiusSquared || distance >= bestDistance) {
                continue;
            }
            if (!occupancy.permitsCapture(context, candidate)) {
                continue;
            }
            best = candidate;
            bestDistance = distance;
        }
        return best;
    }

    private boolean shouldRoam(int gameHour, TwCoopConfig.LifecycleRules rules) {
        int start = rules.getResidentRoamStartHour();
        int end = rules.getResidentRoamEndHour();
        if (start == end) {
            return true;
        }
        return start < end
                ? gameHour >= start && gameHour < end
                : gameHour >= start || gameHour < end;
    }

    private Set<String> normalizedRoles(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return Set.of();
        }
        HashSet<String> result = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    @Nonnull
    private CoopPlan none(ManagedCoopContext context, boolean produce) {
        return new CoopPlan(context, Branch.NONE, null, null, produce, true);
    }

    private void pruneInactive(Set<String> activeKeys) {
        nextCaptureAt.keySet().removeIf(key -> !activeKeys.contains(key));
        nextReleaseAt.keySet().removeIf(key -> !activeKeys.contains(key));
        lastRoaming.keySet().removeIf(key -> !activeKeys.contains(key));
    }

    private long saturatedAdd(long value, long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    interface OccupancyGateway {
        boolean permitsCapture(@Nonnull ManagedCoopContext context,
                               @Nonnull ManagedCoopCaptureCandidate candidate);

        @Nullable
        ResidentRecord firstHoused(@Nonnull ManagedCoopContext context);
    }

    @FunctionalInterface
    interface LifecycleGateway {
        boolean permitsNormalWork(@Nonnull ManagedCoopContext context);
    }

    /** Independent canonical lifecycle check applied before a housed release is journaled. */
    @FunctionalInterface
    public interface ReleaseEligibilityGateway {
        boolean permitsRelease(@Nonnull ResidentRecord resident);
    }
}
