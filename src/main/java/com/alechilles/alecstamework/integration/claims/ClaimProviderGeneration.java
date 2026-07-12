package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;

/**
 * Value-only identity for a plugin instance, its classloader, and a reflected API contract.
 * It deliberately does not retain plugin or classloader objects.
 */
public record ClaimProviderGeneration(@Nonnull String pluginInstanceToken,
                                      @Nonnull String classLoaderToken,
                                      long reflectedContractGeneration) {
    public static final ClaimProviderGeneration NONE = new ClaimProviderGeneration("none", "none", 0L);

    public ClaimProviderGeneration {
        pluginInstanceToken = normalizeToken(pluginInstanceToken);
        classLoaderToken = normalizeToken(classLoaderToken);
        if (reflectedContractGeneration < 0L) {
            throw new IllegalArgumentException("Reflected contract generation cannot be negative.");
        }
    }

    @Nonnull
    private static String normalizeToken(String token) {
        return token == null || token.isBlank() ? "none" : token;
    }
}
