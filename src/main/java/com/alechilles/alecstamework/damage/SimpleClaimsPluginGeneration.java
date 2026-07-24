package com.alechilles.alecstamework.damage;

import javax.annotation.Nonnull;

/** Value-only identity for one live SimpleClaims plugin/reflection generation. */
record SimpleClaimsPluginGeneration(@Nonnull String pluginInstanceToken,
                                    @Nonnull String classLoaderToken,
                                    long reflectedContractGeneration) {
    static final SimpleClaimsPluginGeneration NONE =
            new SimpleClaimsPluginGeneration("none", "none", 0L);

    SimpleClaimsPluginGeneration {
        pluginInstanceToken = normalize(pluginInstanceToken);
        classLoaderToken = normalize(classLoaderToken);
        if (reflectedContractGeneration < 0L) {
            throw new IllegalArgumentException("Reflected contract generation cannot be negative.");
        }
    }

    @Nonnull
    private static String normalize(String token) {
        return token == null || token.isBlank() ? "none" : token;
    }
}
