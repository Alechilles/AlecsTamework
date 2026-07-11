package com.alechilles.alecstamework.ownership;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable compare-and-transition request for one canonical companion profile.
 *
 * <p>{@link #NEW_PROFILE_REVISION} declares that the profile must not already exist. Otherwise
 * the expected revision, nullable owner, and nullable source world must match the committed entry
 * exactly. A null new owner clears ownership; a null destination world leaves the per-world bucket
 * unknown. Force bypasses readiness and positive-cap checks, but never compare-and-set checks.
 */
public record OwnerPopulationTransitionRequest(String profileId,
                                               long expectedRevision,
                                               UUID expectedOwnerId,
                                               String sourceWorldName,
                                               UUID newOwnerId,
                                               String destinationWorldName,
                                               CompanionLifecycleState lifecycleState,
                                               OwnerPopulationOperation operation,
                                               OwnerPopulationLimitScope limitScope,
                                               int limit,
                                               boolean force,
                                               long leaseDurationNanos) {
    public static final long NEW_PROFILE_REVISION = -1L;
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(10L);

    public OwnerPopulationTransitionRequest {
        profileId = OwnerPopulationEntry.normalizeProfileId(profileId);
        if (expectedRevision < NEW_PROFILE_REVISION) {
            throw new IllegalArgumentException("Expected revision must be -1 or a committed revision.");
        }
        sourceWorldName = OwnerPopulationScopeKey.normalizeWorldName(sourceWorldName);
        destinationWorldName = OwnerPopulationScopeKey.normalizeWorldName(destinationWorldName);
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(limitScope, "limitScope");
        if (leaseDurationNanos <= 0L) {
            throw new IllegalArgumentException("Reservation lease duration must be positive.");
        }
    }

    /** Convenience constructor using the standard ten-second monotonic lease. */
    public OwnerPopulationTransitionRequest(String profileId,
                                            long expectedRevision,
                                            UUID expectedOwnerId,
                                            String sourceWorldName,
                                            UUID newOwnerId,
                                            String destinationWorldName,
                                            CompanionLifecycleState lifecycleState,
                                            OwnerPopulationOperation operation,
                                            OwnerPopulationLimitScope limitScope,
                                            int limit,
                                            boolean force) {
        this(
                profileId,
                expectedRevision,
                expectedOwnerId,
                sourceWorldName,
                newOwnerId,
                destinationWorldName,
                lifecycleState,
                operation,
                limitScope,
                limit,
                force,
                DEFAULT_LEASE_DURATION.toNanos()
        );
    }
}
