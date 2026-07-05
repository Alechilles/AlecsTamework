package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Optional-claims-plugin bridge used by Tamework population policies.
 */
public interface ClaimIntegrationBridge {
    @Nonnull
    String providerId();

    boolean isAvailable();

    @Nullable
    String getUnavailableReason();

    @Nonnull
    default ClaimLookupResult lookupClaim(@Nullable String worldName, @Nullable Vector3d position) {
        if (position == null) {
            return ClaimLookupResult.error("Position is missing.");
        }
        return lookupClaim(worldName, position.x, position.z);
    }

    @Nonnull
    ClaimLookupResult lookupClaim(@Nullable String worldName, double blockX, double blockZ);
}
