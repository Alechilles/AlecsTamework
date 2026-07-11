package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Parsed claim-provider setting that preserves invalid explicit values for fail-closed handling.
 */
public record ClaimProviderRequest(@Nullable String configuredValue,
                                   @Nullable ClaimIntegrationProvider provider,
                                   boolean valid) {
    public ClaimProviderRequest {
        if (valid && provider == null) {
            throw new IllegalArgumentException("A valid provider request requires a provider.");
        }
        if (!valid && provider != null) {
            throw new IllegalArgumentException("An invalid provider request cannot select a provider.");
        }
    }

    @Nonnull
    public static ClaimProviderRequest fromConfigValue(@Nullable String configuredValue) {
        ClaimIntegrationProvider parsed = ClaimIntegrationProvider.tryFromConfigValue(configuredValue);
        return parsed == null
                ? new ClaimProviderRequest(configuredValue, null, false)
                : new ClaimProviderRequest(configuredValue, parsed, true);
    }

    @Nonnull
    public static ClaimProviderRequest forProvider(@Nullable ClaimIntegrationProvider provider) {
        ClaimIntegrationProvider resolved = provider == null ? ClaimIntegrationProvider.AUTO : provider;
        return new ClaimProviderRequest(resolved.configValue(), resolved, true);
    }

    @Nonnull
    public String displayValue() {
        return configuredValue == null || configuredValue.isBlank() ? "Auto" : configuredValue.trim();
    }

    /**
     * Builds a stable diagnostic that includes both the config field path and the rejected value.
     */
    @Nonnull
    public String invalidDiagnostic(@Nonnull String fieldPath) {
        if (valid) {
            throw new IllegalStateException("A valid claim provider request has no invalid diagnostic.");
        }
        String normalizedPath = fieldPath == null || fieldPath.isBlank()
                ? "<unknown-field>"
                : fieldPath.trim();
        return "Invalid claim provider at " + normalizedPath + ": '" + displayValue() + "'.";
    }
}
