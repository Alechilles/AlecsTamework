package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Evaluates the legacy claim-access API over a live damage/identity generation. */
final class SimpleClaimsRawAccessEvaluator {
    private final SimpleClaimsDamageCapabilityResolver capabilityResolver;

    SimpleClaimsRawAccessEvaluator(@Nonnull SimpleClaimsDamageCapabilityResolver capabilityResolver) {
        this.capabilityResolver = Objects.requireNonNull(capabilityResolver, "capabilityResolver");
    }

    @Nonnull
    SimpleClaimsRawAccessDecision evaluate(@Nullable String worldName,
                                           @Nullable Vector3d targetPosition,
                                           @Nullable UUID attackerPlayerUuid,
                                           @Nullable TwGlobalConfig globalConfig) {
        boolean integrationEnabled = globalConfig != null
                && TameworkRuntimeSettings.simpleClaimsEnabled(globalConfig.isSimpleClaimsEnabled());
        if (!integrationEnabled) {
            return decision(false, true, SimpleClaimsRawAccessDecision.Status.SKIPPED,
                    "simpleclaims-disabled", null);
        }
        if (worldName == null || worldName.isBlank() || !isFinite(targetPosition)) {
            return unavailable("live-claim-context-missing");
        }

        SimpleClaimsDamageCapabilityResolver.Resolution resolution = resolveCapability();
        SimpleClaimsDamageGeneration capability = resolution.capability();
        if (resolution.state() != ClaimProviderState.READY || capability == null) {
            return unavailable(firstNonBlank(resolution.reason(), "simpleclaims-unavailable"));
        }
        if (!capability.claimIdentityAvailable()) {
            return unavailable(firstNonBlank(
                    capability.unavailableReason(),
                    "simpleclaims-claim-identity-unavailable"
            ));
        }

        SimpleClaimsClaimIdentityAccess.Result lookup = lookup(capability, worldName, targetPosition);
        if (lookup == null) {
            return decision(true, true, SimpleClaimsRawAccessDecision.Status.ALLOW_FAIL_OPEN,
                    "simpleclaims-claim-lookup-returned-null", null);
        }
        return switch (lookup.status()) {
            case NO_CLAIM -> decision(true, true, SimpleClaimsRawAccessDecision.Status.ALLOWED,
                    "outside-claim", null);
            case UNAVAILABLE -> unavailable(firstNonBlank(
                    lookup.message(), "simpleclaims-claim-identity-unavailable"));
            case ERROR -> decision(true, true, SimpleClaimsRawAccessDecision.Status.ALLOW_FAIL_OPEN,
                    firstNonBlank(lookup.message(), "simpleclaims-claim-lookup-error"), lookup.claimPartyId());
            case CLAIM_FOUND -> evaluateNative(
                    capability,
                    worldName,
                    targetPosition,
                    attackerPlayerUuid,
                    globalConfig.getSimpleClaimsDamageAllowDamagePermissionKey(),
                    lookup.claimPartyId()
            );
        };
    }

    @Nullable
    private SimpleClaimsClaimIdentityAccess.Result lookup(@Nonnull SimpleClaimsDamageGeneration capability,
                                                          @Nonnull String worldName,
                                                          @Nonnull Vector3d targetPosition) {
        try {
            return capability.claimIdentityAccess().lookup(worldName, targetPosition);
        } catch (Throwable throwable) {
            return new SimpleClaimsClaimIdentityAccess.Result(
                    SimpleClaimsClaimIdentityAccess.Status.ERROR,
                    null,
                    message(throwable)
            );
        }
    }

    @Nonnull
    private SimpleClaimsRawAccessDecision evaluateNative(
            @Nonnull SimpleClaimsDamageGeneration capability,
            @Nonnull String worldName,
            @Nonnull Vector3d targetPosition,
            @Nullable UUID attackerPlayerUuid,
            @Nullable String permissionKey,
            @Nullable UUID lookupPartyId) {
        SimpleClaimsBreedingBridge.DamageAccessResult result;
        try {
            result = capability.nativeAccess().evaluate(
                    worldName,
                    targetPosition,
                    attackerPlayerUuid,
                    permissionKey
            );
        } catch (Throwable throwable) {
            return decision(true, true, SimpleClaimsRawAccessDecision.Status.ALLOW_FAIL_OPEN,
                    message(throwable), lookupPartyId);
        }
        if (result == null) {
            return decision(true, true, SimpleClaimsRawAccessDecision.Status.ALLOW_FAIL_OPEN,
                    "simpleclaims-native-damage-result-missing", lookupPartyId);
        }
        UUID partyId = lookupPartyId != null ? lookupPartyId : result.claimPartyId();
        String reason = firstNonBlank(result.message(), result.status().name().toLowerCase());
        return switch (result.status()) {
            case ALLOWED -> decision(true, true, SimpleClaimsRawAccessDecision.Status.ALLOWED, reason, partyId);
            case DENIED -> decision(true, false, SimpleClaimsRawAccessDecision.Status.DENIED, reason, partyId);
            case LOOKUP_ERROR -> decision(
                    true, true, SimpleClaimsRawAccessDecision.Status.ALLOW_FAIL_OPEN, reason, partyId);
            case UNAVAILABLE -> decision(
                    false, true, SimpleClaimsRawAccessDecision.Status.UNAVAILABLE, reason, partyId);
        };
    }

    @Nonnull
    private SimpleClaimsDamageCapabilityResolver.Resolution resolveCapability() {
        try {
            SimpleClaimsDamageCapabilityResolver.Resolution resolution = capabilityResolver.resolve();
            return resolution != null
                    ? resolution
                    : resolutionError("SimpleClaims damage capability resolver returned null.");
        } catch (Throwable throwable) {
            return resolutionError("SimpleClaims damage capability resolution failed: " + message(throwable));
        }
    }

    @Nonnull
    private static SimpleClaimsDamageCapabilityResolver.Resolution resolutionError(@Nonnull String reason) {
        return SimpleClaimsDamageCapabilityResolver.Resolution.unavailable(
                ClaimProviderState.ERROR,
                ClaimProviderGeneration.NONE,
                null,
                reason
        );
    }

    @Nonnull
    private static SimpleClaimsRawAccessDecision unavailable(@Nonnull String reason) {
        return decision(false, true, SimpleClaimsRawAccessDecision.Status.UNAVAILABLE, reason, null);
    }

    @Nonnull
    private static SimpleClaimsRawAccessDecision decision(boolean available,
                                                          boolean allowed,
                                                          @Nonnull SimpleClaimsRawAccessDecision.Status status,
                                                          @Nonnull String reason,
                                                          @Nullable UUID partyId) {
        return new SimpleClaimsRawAccessDecision(available, allowed, status, reason, partyId);
    }

    private static boolean isFinite(@Nullable Vector3d position) {
        return position != null
                && Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }

    @Nonnull
    private static String firstNonBlank(@Nullable String preferred, @Nonnull String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    @Nonnull
    private static String message(@Nonnull Throwable throwable) {
        String detail = throwable.getMessage();
        return detail == null || detail.isBlank() ? throwable.getClass().getSimpleName() : detail;
    }
}
