package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;
import org.joml.Vector3d;

/**
 * Reads claims from one claim mod for claim limits and the breeding claim rule.
 *
 * <p>Call on the world thread that owns {@code worldName}. Implementations never throw: a claim
 * mod that is missing, not ready or failing answers {@link ClaimLookupResult.Status#UNAVAILABLE}
 * or {@link ClaimLookupResult.Status#ERROR}.</p>
 */
@FunctionalInterface
public interface ClaimModProvider {
    /** The claim covering the block column at {@code position}. */
    @Nonnull
    ClaimLookupResult lookupClaim(@Nonnull String worldName, @Nonnull Vector3d position);
}
