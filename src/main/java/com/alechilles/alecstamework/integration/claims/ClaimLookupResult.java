package com.alechilles.alecstamework.integration.claims;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The claim at one block column, as one claim mod reports it.
 *
 * @param claimKey identifies the claim among all claims of every claim mod (for example
 *                 {@code simpleclaims:<party>} or {@code questlines:<id>}); set only when found
 * @param claimChunkCount how many chunks the claim covers; 0 unless found
 * @param claimPartyId the owning SimpleClaims party; null for other claim mods
 * @param message a diagnostic detail, not player-facing
 */
public record ClaimLookupResult(@Nonnull Status status,
                                @Nullable String claimKey,
                                int claimChunkCount,
                                @Nullable UUID claimPartyId,
                                @Nullable String message) {
    public enum Status {
        CLAIM_FOUND,
        NO_CLAIM,
        UNAVAILABLE,
        ERROR
    }

    public ClaimLookupResult {
        Objects.requireNonNull(status, "status");
        claimChunkCount = Math.max(0, claimChunkCount);
    }

    @Nonnull
    public static ClaimLookupResult found(@Nonnull String claimKey,
                                          int claimChunkCount,
                                          @Nullable UUID claimPartyId) {
        return new ClaimLookupResult(Status.CLAIM_FOUND, claimKey, claimChunkCount, claimPartyId, null);
    }

    @Nonnull
    public static ClaimLookupResult noClaim() {
        return new ClaimLookupResult(Status.NO_CLAIM, null, 0, null, null);
    }

    @Nonnull
    public static ClaimLookupResult unavailable(@Nullable String message) {
        return new ClaimLookupResult(Status.UNAVAILABLE, null, 0, null, message);
    }

    @Nonnull
    public static ClaimLookupResult error(@Nullable String message) {
        return new ClaimLookupResult(Status.ERROR, null, 0, null, message);
    }
}
