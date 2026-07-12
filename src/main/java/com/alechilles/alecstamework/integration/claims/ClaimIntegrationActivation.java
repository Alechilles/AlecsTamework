package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure, provider-neutral activation truth table for legacy claim integration settings.
 *
 * <p>The legacy master switch gates both policy families. Population also requires a valid,
 * non-Off provider and a rule relevant to the operation. Damage is intentionally independent of
 * population-provider validity because it is governed by SimpleClaims' native damage capability.
 */
public record ClaimIntegrationActivation(boolean masterEnabled,
                                         boolean providerValid,
                                         boolean providerEnabled,
                                         boolean claimCapsConfigured,
                                         boolean breedingRequiresClaim,
                                         boolean protectTamedFromNonMembers,
                                         boolean standardPopulationActive,
                                         boolean breedingPopulationActive,
                                         boolean damageActive) {

    @Nonnull
    public static ClaimIntegrationActivation evaluate(boolean masterEnabled,
                                                      @Nullable ClaimProviderRequest providerRequest,
                                                      int limitPerClaimChunk,
                                                      int limitPerClaimTotal,
                                                      boolean breedingRequiresClaim,
                                                      boolean protectTamedFromNonMembers) {
        boolean providerValid = providerRequest != null && providerRequest.valid();
        boolean providerEnabled = providerValid
                && providerRequest.provider() != ClaimIntegrationProvider.OFF;
        boolean capsConfigured = limitPerClaimChunk > 0 || limitPerClaimTotal > 0;
        boolean standardPopulationActive = masterEnabled && providerEnabled && capsConfigured;
        boolean breedingPopulationActive = masterEnabled
                && providerEnabled
                && (capsConfigured || breedingRequiresClaim);
        boolean damageActive = masterEnabled && protectTamedFromNonMembers;
        return new ClaimIntegrationActivation(
                masterEnabled,
                providerValid,
                providerEnabled,
                capsConfigured,
                breedingRequiresClaim,
                protectTamedFromNonMembers,
                standardPopulationActive,
                breedingPopulationActive,
                damageActive
        );
    }

    /** Returns the operation-relevant population gate without resolving a provider. */
    public boolean populationActive(boolean breedingOperation) {
        return breedingOperation ? breedingPopulationActive : standardPopulationActive;
    }
}
