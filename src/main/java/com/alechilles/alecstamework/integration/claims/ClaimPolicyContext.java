package com.alechilles.alecstamework.integration.claims;

import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable provider and settings snapshot retained for exactly one top-level claim operation.
 */
public record ClaimPolicyContext(@Nullable String requestedValue,
                                 @Nullable ClaimIntegrationProvider requestedProvider,
                                 @Nullable ClaimIntegrationProvider resolvedProvider,
                                 @Nonnull String providerId,
                                 @Nonnull ClaimProviderState state,
                                 @Nonnull Set<ClaimProviderCapability> capabilities,
                                 @Nullable String pluginVersion,
                                 @Nullable String reason,
                                 @Nonnull ClaimProviderGeneration providerGeneration,
                                 long settingsRevision,
                                 @Nullable ClaimIntegrationBridge bridge) {
    public ClaimPolicyContext {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID is required.");
        }
        state = state == null ? ClaimProviderState.ERROR : state;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        providerGeneration = providerGeneration == null ? ClaimProviderGeneration.NONE : providerGeneration;
        if (state == ClaimProviderState.READY && bridge == null) {
            throw new IllegalArgumentException("A ready policy context requires a bridge.");
        }
        if (state != ClaimProviderState.READY && bridge != null) {
            throw new IllegalArgumentException("A non-ready policy context cannot retain a bridge.");
        }
    }

    public boolean ready() {
        return state == ClaimProviderState.READY;
    }

    public long reflectedContractGeneration() {
        return providerGeneration.reflectedContractGeneration();
    }
}
