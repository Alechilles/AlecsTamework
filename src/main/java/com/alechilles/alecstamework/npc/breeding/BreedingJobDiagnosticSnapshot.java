package com.alechilles.alecstamework.npc.breeding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable, process-local diagnostic history for one breeding birth job.
 *
 * <p>Birth jobs themselves remain the gameplay authority. This snapshot retains only operator
 * evidence that would otherwise disappear when reservations are released: admission headroom,
 * the exact final spawn count, and the reason and rollback result for terminal failures or
 * cancellations.</p>
 */
public record BreedingJobDiagnosticSnapshot(
        @Nonnull UUID jobId,
        @Nullable CapacitySnapshot initialCapacity,
        @Nullable CapacitySnapshot spawnCapacity,
        int spawnedChildren,
        boolean spawnedCountFinal,
        @Nonnull Outcome outcome,
        @Nullable String reason,
        @Nonnull RollbackStatus rollbackStatus,
        @Nullable String rollbackDetail) {
    public BreedingJobDiagnosticSnapshot {
        Objects.requireNonNull(jobId, "jobId");
        if (spawnedChildren < 0) {
            throw new IllegalArgumentException("spawnedChildren must be nonnegative");
        }
        Objects.requireNonNull(outcome, "outcome");
        reason = normalize(reason);
        Objects.requireNonNull(rollbackStatus, "rollbackStatus");
        rollbackDetail = normalize(rollbackDetail);
    }

    /** Creates the initial non-terminal snapshot before optional capacity details are attached. */
    @Nonnull
    public static BreedingJobDiagnosticSnapshot registered(@Nonnull UUID jobId) {
        return new BreedingJobDiagnosticSnapshot(
                jobId,
                null,
                null,
                0,
                false,
                Outcome.ACTIVE,
                null,
                RollbackStatus.NOT_ATTEMPTED,
                null
        );
    }

    @Nonnull
    BreedingJobDiagnosticSnapshot withInitialCapacity(@Nonnull CapacitySnapshot capacity) {
        return copy(capacity, spawnCapacity, spawnedChildren, spawnedCountFinal, outcome, reason,
                rollbackStatus, rollbackDetail);
    }

    @Nonnull
    BreedingJobDiagnosticSnapshot withSpawnCapacity(@Nonnull CapacitySnapshot capacity) {
        return copy(initialCapacity, capacity, spawnedChildren, spawnedCountFinal, outcome, reason,
                rollbackStatus, rollbackDetail);
    }

    @Nonnull
    BreedingJobDiagnosticSnapshot withOutcome(@Nonnull Outcome nextOutcome,
                                               int nextSpawnedChildren,
                                               @Nullable String nextReason,
                                               @Nonnull RollbackStatus nextRollbackStatus,
                                               @Nullable String nextRollbackDetail) {
        return copy(
                initialCapacity,
                spawnCapacity,
                nextSpawnedChildren,
                true,
                nextOutcome,
                nextReason,
                nextRollbackStatus,
                nextRollbackDetail
        );
    }

    private BreedingJobDiagnosticSnapshot copy(CapacitySnapshot nextInitialCapacity,
                                                CapacitySnapshot nextSpawnCapacity,
                                                int nextSpawnedChildren,
                                                boolean nextSpawnedCountFinal,
                                                Outcome nextOutcome,
                                                String nextReason,
                                                RollbackStatus nextRollbackStatus,
                                                String nextRollbackDetail) {
        return new BreedingJobDiagnosticSnapshot(
                jobId,
                nextInitialCapacity,
                nextSpawnCapacity,
                nextSpawnedChildren,
                nextSpawnedCountFinal,
                nextOutcome,
                nextReason,
                nextRollbackStatus,
                nextRollbackDetail
        );
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** Capacity decision captured at initial admission or the spawn-time recheck. */
    public record CapacitySnapshot(
            int maxNearby,
            @Nonnull Map<String, Integer> liveNearbyByPopulationType,
            int admittedChildren,
            int availableClaimHeadroom,
            int availablePlayerHeadroom,
            int combinedTotalHeadroom,
            @Nonnull Map<BreedingPlayerCapacityScope, Integer> availablePlayerHeadroomByScope) {
        public CapacitySnapshot {
            if (admittedChildren < 0
                    || availableClaimHeadroom < 0
                    || availablePlayerHeadroom < 0
                    || combinedTotalHeadroom < 0) {
                throw new IllegalArgumentException("Capacity diagnostics must be nonnegative");
            }
            liveNearbyByPopulationType = immutableCounts(liveNearbyByPopulationType);
            availablePlayerHeadroomByScope = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            availablePlayerHeadroomByScope,
                            "availablePlayerHeadroomByScope"
                    ))
            );
        }

        @Nonnull
        static CapacitySnapshot from(
                @Nonnull BreedingPopulationAdmissionService.AdmissionRequest request,
                @Nonnull BreedingPopulationAdmissionService.AdmissionResult result) {
            return new CapacitySnapshot(
                    request.maxNearby(),
                    request.liveNearbyByPopulationType(),
                    result.admittedCount(),
                    result.availableClaimHeadroom(),
                    result.availablePlayerHeadroom(),
                    result.combinedTotalHeadroom(),
                    result.availablePlayerHeadroomByScope()
            );
        }

        private static Map<String, Integer> immutableCounts(Map<String, Integer> source) {
            Objects.requireNonNull(source, "liveNearbyByPopulationType");
            LinkedHashMap<String, Integer> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : source.entrySet()) {
                String type = PlannedChild.canonicalPopulationType(entry.getKey());
                Integer count = Objects.requireNonNull(entry.getValue(), "live nearby count");
                if (count < 0) {
                    throw new IllegalArgumentException("Live nearby counts must be nonnegative");
                }
                copy.put(type, count);
            }
            return Collections.unmodifiableMap(copy);
        }
    }

    /** Operator-facing terminal classification independent of the lower-level job state enum. */
    public enum Outcome {
        ACTIVE,
        COMPLETED,
        FAILED,
        CANCELLED,
        CAPACITY_REJECTED,
        PARENTS_INVALID,
        EFFECTS_FAILED
    }

    /**
     * Strength of rollback evidence: {@code ATTEMPTED} means a void callback returned normally,
     * while {@code COMPLETED} is reserved for an explicit restoration report.
     */
    public enum RollbackStatus {
        NOT_ATTEMPTED,
        ATTEMPTED,
        COMPLETED,
        SKIPPED,
        PARTIAL,
        FAILED
    }
}
