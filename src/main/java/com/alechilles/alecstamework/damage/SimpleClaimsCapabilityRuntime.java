package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shares reflected SimpleClaims capabilities for the current live plugin generation.
 */
public final class SimpleClaimsCapabilityRuntime implements AutoCloseable {
    private final SimpleClaimsDamageCapabilityResolver resolver;

    /** Creates a runtime that follows the live SimpleClaims plugin lifecycle. */
    public SimpleClaimsCapabilityRuntime() {
        this(new SimpleClaimsDamageCapabilityRegistry());
    }

    SimpleClaimsCapabilityRuntime(@Nonnull SimpleClaimsDamageCapabilityResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Resolves the claim bridge for the current live plugin generation. */
    @Nonnull
    public BridgeResolution resolveBridge() {
        SimpleClaimsDamageCapabilityResolver.Resolution resolution = resolver.resolve();
        SimpleClaimsDamageGeneration capability = resolution.capability();
        SimpleClaimsBreedingBridge bridge = capability == null ? null : capability.bridge();
        return bridge == null
                ? BridgeResolution.unavailable(resolution.reason())
                : BridgeResolution.ready(bridge);
    }

    @Nonnull
    SimpleClaimsDamageCapabilityResolver damageResolver() {
        return resolver;
    }

    /** Drops any cached generation before the next capability lookup. */
    public void invalidate() {
        resolver.invalidate();
    }

    @Override
    public void close() {
        resolver.close();
    }

    /** Result of resolving the claim bridge for the current provider generation. */
    public record BridgeResolution(
            @Nullable SimpleClaimsBreedingBridge bridge,
            @Nullable String reason) {

        @Nonnull
        static BridgeResolution ready(@Nonnull SimpleClaimsBreedingBridge bridge) {
            return new BridgeResolution(Objects.requireNonNull(bridge, "bridge"), null);
        }

        @Nonnull
        static BridgeResolution unavailable(@Nullable String reason) {
            return new BridgeResolution(null, reason);
        }

        /** Returns true when claim capabilities are ready for use. */
        public boolean available() {
            return bridge != null;
        }
    }
}
