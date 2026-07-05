package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Selects the active claims bridge from config/runtime provider settings.
 */
public final class ClaimIntegrationProviderSelector {
    private static final ClaimIntegrationBridge OFF_BRIDGE = new StaticUnavailableBridge("off", "Claim integration is off.");
    private static final ClaimIntegrationBridge MISSING_BRIDGE =
            new StaticUnavailableBridge("missing", "No claim integration provider is available.");

    private ClaimIntegrationProviderSelector() {
    }

    @Nonnull
    public static ClaimIntegrationBridge select(@Nullable ClaimIntegrationProvider provider,
                                                @Nullable ClaimIntegrationBridge questLines,
                                                @Nullable ClaimIntegrationBridge simpleClaims) {
        ClaimIntegrationProvider resolved = provider == null ? ClaimIntegrationProvider.AUTO : provider;
        return switch (resolved) {
            case OFF -> OFF_BRIDGE;
            case QUESTLINES_CLAIMS -> availableOrMissing(questLines);
            case SIMPLE_CLAIMS -> availableOrMissing(simpleClaims);
            case AUTO -> {
                if (questLines != null && questLines.isAvailable()) {
                    yield questLines;
                }
                if (simpleClaims != null && simpleClaims.isAvailable()) {
                    yield simpleClaims;
                }
                yield MISSING_BRIDGE;
            }
        };
    }

    @Nonnull
    private static ClaimIntegrationBridge availableOrMissing(@Nullable ClaimIntegrationBridge bridge) {
        return bridge == null ? MISSING_BRIDGE : bridge;
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
