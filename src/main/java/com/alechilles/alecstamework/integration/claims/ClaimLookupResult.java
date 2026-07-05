package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provider-neutral claim lookup result used by population-cap policies.
 */
public record ClaimLookupResult(@Nonnull Status status,
                                @Nullable ClaimPopulationKey key,
                                int claimChunkCount,
                                @Nullable String message) {
    public ClaimLookupResult {
        status = status == null ? Status.ERROR : status;
        claimChunkCount = Math.max(0, claimChunkCount);
    }

    @Nonnull
    public static ClaimLookupResult found(@Nonnull ClaimPopulationKey key, int claimChunkCount) {
        return new ClaimLookupResult(Status.CLAIM_FOUND, key, claimChunkCount, null);
    }

    @Nonnull
    public static ClaimLookupResult noClaim() {
        return new ClaimLookupResult(Status.NO_CLAIM, null, 0, null);
    }

    @Nonnull
    public static ClaimLookupResult unavailable(@Nullable String message) {
        return new ClaimLookupResult(Status.UNAVAILABLE, null, 0, message);
    }

    @Nonnull
    public static ClaimLookupResult error(@Nullable String message) {
        return new ClaimLookupResult(Status.ERROR, null, 0, message);
    }

    public enum Status {
        CLAIM_FOUND,
        NO_CLAIM,
        UNAVAILABLE,
        ERROR
    }
}
