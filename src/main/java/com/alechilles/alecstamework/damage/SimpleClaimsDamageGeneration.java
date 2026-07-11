package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Reflected SimpleClaims damage/identity capabilities belonging to one plugin generation. */
record SimpleClaimsDamageGeneration(
        @Nonnull NativeSimpleClaimsDamageAccess nativeAccess,
        @Nonnull LegacySimpleClaimsPartyPermissionBypass legacyPartyBypass,
        @Nonnull SimpleClaimsClaimIdentityAccess claimIdentityAccess,
        boolean nativeDamageAvailable,
        boolean claimIdentityAvailable,
        @Nullable String unavailableReason) {

    SimpleClaimsDamageGeneration {
        Objects.requireNonNull(nativeAccess, "nativeAccess");
        Objects.requireNonNull(legacyPartyBypass, "legacyPartyBypass");
        Objects.requireNonNull(claimIdentityAccess, "claimIdentityAccess");
    }

    @Nonnull
    static SimpleClaimsDamageGeneration reflect(@Nonnull Object plugin) {
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        if (classLoader == null) {
            classLoader = SimpleClaimsDamageGeneration.class.getClassLoader();
        }
        SimpleClaimsBreedingBridge bridge = SimpleClaimsBreedingBridge.forDamagePlugin(plugin);
        return new SimpleClaimsDamageGeneration(
                bridge::evaluateDamageAccess,
                new ReflectiveLegacySimpleClaimsPartyPermissionBypass(bridge, classLoader),
                (worldName, position) -> mapLookup(bridge.lookupClaimIdentity(worldName, position)),
                bridge.isDamagePolicyAvailable(),
                bridge.isAvailable(),
                firstNonBlank(bridge.getDamagePolicyUnavailableReason(), bridge.getUnavailableReason())
        );
    }

    @Nonnull
    static SimpleClaimsDamageGeneration fixed(@Nonnull NativeSimpleClaimsDamageAccess nativeAccess,
                                              @Nonnull LegacySimpleClaimsPartyPermissionBypass legacyPartyBypass) {
        return new SimpleClaimsDamageGeneration(
                nativeAccess,
                legacyPartyBypass,
                (worldName, position) -> new SimpleClaimsClaimIdentityAccess.Result(
                        SimpleClaimsClaimIdentityAccess.Status.UNAVAILABLE,
                        null,
                        "Claim identity is unavailable in this fixed damage capability."
                ),
                true,
                false,
                null
        );
    }

    boolean usable() {
        return nativeDamageAvailable || claimIdentityAvailable;
    }

    @Nonnull
    private static SimpleClaimsClaimIdentityAccess.Result mapLookup(@Nonnull ClaimLookupResult result) {
        return switch (result.status()) {
            case CLAIM_FOUND -> new SimpleClaimsClaimIdentityAccess.Result(
                    SimpleClaimsClaimIdentityAccess.Status.CLAIM_FOUND,
                    result.key() != null ? result.key().ownerId() : null,
                    result.message()
            );
            case NO_CLAIM -> new SimpleClaimsClaimIdentityAccess.Result(
                    SimpleClaimsClaimIdentityAccess.Status.NO_CLAIM,
                    null,
                    result.message()
            );
            case UNAVAILABLE -> new SimpleClaimsClaimIdentityAccess.Result(
                    SimpleClaimsClaimIdentityAccess.Status.UNAVAILABLE,
                    null,
                    result.message()
            );
            case ERROR -> new SimpleClaimsClaimIdentityAccess.Result(
                    SimpleClaimsClaimIdentityAccess.Status.ERROR,
                    null,
                    result.message()
            );
        };
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }
}
