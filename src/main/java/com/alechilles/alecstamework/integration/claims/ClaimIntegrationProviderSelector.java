package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds immutable unavailable bridges for fail-closed claim-policy seams.
 */
public final class ClaimIntegrationProviderSelector {
    private ClaimIntegrationProviderSelector() {
    }

    /** Builds an immutable unavailable bridge for one operation-scoped registry result. */
    @Nonnull
    public static ClaimIntegrationBridge unavailable(@Nullable String providerId,
                                                     @Nullable String reason) {
        String resolvedId = providerId == null || providerId.isBlank()
                ? "unavailable" : providerId.trim();
        String resolvedReason = reason == null || reason.isBlank()
                ? "Claim integration provider is unavailable." : reason.trim();
        return new StaticUnavailableBridge(resolvedId, resolvedReason);
    }

    private record StaticUnavailableBridge(@Nonnull String providerId,
                                           @Nonnull String reason) implements ClaimIntegrationBridge {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String getUnavailableReason() {
            return reason;
        }

        @Nonnull
        @Override
        public ClaimLookupResult lookupClaim(String worldName, double blockX, double blockZ) {
            return ClaimLookupResult.unavailable(reason);
        }
    }
}
