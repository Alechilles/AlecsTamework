package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rich provider-neutral claim resolution used internally by admission and population policies.
 *
 * <p>The footprint is absent only for compatibility providers that expose a scalar extent but no
 * chunk coordinates.</p>
 */
public record ClaimResolution(@Nonnull ClaimLookupResult.Status status,
                              @Nullable ClaimPopulationKey key,
                              @Nullable ClaimFootprint footprint,
                              int claimChunkCount,
                              @Nullable String message) {
    public ClaimResolution {
        status = status == null ? ClaimLookupResult.Status.ERROR : status;
        claimChunkCount = footprint == null
                ? Math.max(0, claimChunkCount)
                : footprint.chunkCount();
    }

    @Nonnull
    public static ClaimResolution found(@Nonnull ClaimPopulationKey key,
                                        @Nonnull ClaimFootprint footprint) {
        return new ClaimResolution(
                ClaimLookupResult.Status.CLAIM_FOUND,
                key,
                footprint,
                footprint.chunkCount(),
                null
        );
    }

    @Nonnull
    public static ClaimResolution foundWithoutFootprint(@Nonnull ClaimPopulationKey key, int claimChunkCount) {
        return new ClaimResolution(
                ClaimLookupResult.Status.CLAIM_FOUND,
                key,
                null,
                claimChunkCount,
                null
        );
    }

    @Nonnull
    public static ClaimResolution noClaim() {
        return new ClaimResolution(ClaimLookupResult.Status.NO_CLAIM, null, null, 0, null);
    }

    @Nonnull
    public static ClaimResolution unavailable(@Nullable String message) {
        return new ClaimResolution(ClaimLookupResult.Status.UNAVAILABLE, null, null, 0, message);
    }

    @Nonnull
    public static ClaimResolution error(@Nullable String message) {
        return new ClaimResolution(ClaimLookupResult.Status.ERROR, null, null, 0, message);
    }

    @Nonnull
    public static ClaimResolution fromLookupResult(@Nullable ClaimLookupResult lookup) {
        if (lookup == null) {
            return error("Claim lookup result was null.");
        }
        return new ClaimResolution(
                lookup.status(),
                lookup.key(),
                null,
                lookup.claimChunkCount(),
                lookup.message()
        );
    }

    /**
     * Maps the richer internal result onto the stable legacy bridge DTO.
     */
    @Nonnull
    public ClaimLookupResult toLookupResult() {
        return switch (status) {
            case CLAIM_FOUND -> key == null
                    ? ClaimLookupResult.error("Claim population key was missing.")
                    : ClaimLookupResult.found(key, claimChunkCount);
            case NO_CLAIM -> ClaimLookupResult.noClaim();
            case UNAVAILABLE -> ClaimLookupResult.unavailable(message);
            case ERROR -> ClaimLookupResult.error(message);
        };
    }
}
