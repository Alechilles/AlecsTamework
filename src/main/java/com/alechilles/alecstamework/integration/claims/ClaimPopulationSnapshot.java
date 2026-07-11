package com.alechilles.alecstamework.integration.claims;

import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Unique canonical population resolved for one target claim.
 */
public record ClaimPopulationSnapshot(@Nonnull Status status,
                                      @Nullable ClaimPopulationKey claimKey,
                                      @Nullable ClaimFootprint footprint,
                                      @Nonnull Set<String> profileIds,
                                      long occupancyRevision,
                                      @Nullable String message) {
    public ClaimPopulationSnapshot {
        status = status == null ? Status.ERROR : status;
        profileIds = profileIds == null ? Set.of() : Set.copyOf(profileIds);
    }

    public int population() {
        return profileIds.size();
    }

    @Nullable
    public String footprintDigest() {
        return footprint == null ? null : footprint.digest();
    }

    public enum Status {
        READY,
        NO_CLAIM,
        UNAVAILABLE,
        ERROR
    }
}
