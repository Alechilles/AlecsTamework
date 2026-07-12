package com.alechilles.alecstamework.integration.claims;

import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable result of probing one concrete claim provider generation.
 */
public record ClaimProviderProbeResult(@Nonnull ClaimIntegrationProvider provider,
                                       @Nonnull String providerId,
                                       @Nonnull ClaimProviderState state,
                                       @Nonnull Set<ClaimProviderCapability> capabilities,
                                       @Nullable String pluginVersion,
                                       @Nullable String reason,
                                       @Nonnull ClaimProviderGeneration generation,
                                       @Nullable ClaimIntegrationBridge bridge) {
    public ClaimProviderProbeResult {
        if (provider == null || provider == ClaimIntegrationProvider.AUTO || provider == ClaimIntegrationProvider.OFF) {
            throw new IllegalArgumentException("Probe results require a concrete provider.");
        }
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID is required.");
        }
        if (state == null || state == ClaimProviderState.INVALID || state == ClaimProviderState.OFF) {
            throw new IllegalArgumentException("A provider probe cannot return " + state + ".");
        }
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        generation = generation == null ? ClaimProviderGeneration.NONE : generation;
        if (state == ClaimProviderState.READY && bridge == null) {
            throw new IllegalArgumentException("A ready provider requires a bridge.");
        }
        if (state != ClaimProviderState.READY && bridge != null) {
            throw new IllegalArgumentException("A non-ready provider cannot retain a bridge.");
        }
        if (state != ClaimProviderState.READY && (reason == null || reason.isBlank())) {
            reason = "Claim provider is not ready (" + state + ").";
        }
    }

    @Nonnull
    public static ClaimProviderProbeResult ready(@Nonnull ClaimIntegrationProvider provider,
                                                 @Nonnull String providerId,
                                                 @Nullable String pluginVersion,
                                                 @Nonnull ClaimProviderGeneration generation,
                                                 @Nonnull Set<ClaimProviderCapability> capabilities,
                                                 @Nonnull ClaimIntegrationBridge bridge) {
        return new ClaimProviderProbeResult(
                provider,
                providerId,
                ClaimProviderState.READY,
                capabilities,
                pluginVersion,
                null,
                generation,
                bridge
        );
    }

    @Nonnull
    public static ClaimProviderProbeResult unavailable(@Nonnull ClaimIntegrationProvider provider,
                                                       @Nonnull String providerId,
                                                       @Nonnull ClaimProviderState state,
                                                       @Nullable String pluginVersion,
                                                       @Nullable String reason,
                                                       @Nonnull ClaimProviderGeneration generation) {
        return new ClaimProviderProbeResult(
                provider,
                providerId,
                state,
                Set.of(),
                pluginVersion,
                reason,
                generation,
                null
        );
    }
}
