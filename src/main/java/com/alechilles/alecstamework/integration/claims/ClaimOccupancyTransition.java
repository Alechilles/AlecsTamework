package com.alechilles.alecstamework.integration.claims;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Expected and proposed durable occupancy for one atomic admission unit.
 */
public record ClaimOccupancyTransition(@Nullable ClaimOccupancyEntry expected,
                                       @Nonnull ClaimOccupancyEntry proposed) {
    public ClaimOccupancyTransition {
        Objects.requireNonNull(proposed, "proposed");
        if (expected != null && !expected.profileId().equals(proposed.profileId())) {
            throw new IllegalArgumentException("Expected and proposed profile IDs must match.");
        }
        long expectedRevision = expected == null ? 0L : expected.revision();
        if (expectedRevision == Long.MAX_VALUE || proposed.revision() != expectedRevision + 1L) {
            throw new IllegalArgumentException("The proposed occupancy revision must advance exactly once.");
        }
    }

    @Nonnull
    public String profileId() {
        return proposed.profileId();
    }

    /**
     * Returns true when the transition is provably non-positive without consulting a provider.
     */
    public boolean isKnownNonPositiveAtSameLocation() {
        if (!proposed.occupiesClaim()) {
            return true;
        }
        return expected != null
                && expected.occupiesClaim()
                && Objects.equals(expected.physicalChunk(), proposed.physicalChunk());
    }

    /**
     * The only placement restore that remains zero-delta solely from durable state.
     */
    public boolean isUnloadedSameLocationRehydrate() {
        return expected != null
                && expected.lifecycleState() == CompanionLifecycleState.UNLOADED
                && expected.occupiesClaim()
                && proposed.occupiesClaim()
                && Objects.equals(expected.physicalChunk(), proposed.physicalChunk());
    }
}
