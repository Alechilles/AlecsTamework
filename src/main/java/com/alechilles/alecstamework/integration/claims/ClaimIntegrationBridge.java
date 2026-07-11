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

    /**
     * Resolves richer claim details when a provider exposes them. Legacy providers retain their
     * scalar lookup behavior through this compatibility default.
     */
    @Nonnull
    default ClaimResolution resolveClaim(@Nullable String worldName, double blockX, double blockZ) {
        return ClaimResolution.fromLookupResult(lookupClaim(worldName, blockX, blockZ));
    }

    @Nonnull
    default ClaimResolution resolveClaim(@Nullable String worldName, @Nullable Vector3d position) {
        if (position == null) {
            return ClaimResolution.error("Position is missing.");
        }
        return resolveClaim(worldName, position.x, position.z);
    }

    @Nonnull
    ClaimLookupResult lookupClaim(@Nullable String worldName, double blockX, double blockZ);
}
