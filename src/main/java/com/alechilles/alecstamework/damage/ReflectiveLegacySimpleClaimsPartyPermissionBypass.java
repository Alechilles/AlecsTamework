package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Reproduces the pre-remediation raw-party bypass exactly for one compatibility release.
 *
 * <p>The historical code passed the attacker player UUID to {@code hasPartyPermission}, even
 * though that API expects a party identity. This class deliberately preserves only that grant;
 * native SimpleClaims relationship and tamed-damage behavior remains authoritative otherwise.</p>
 */
final class ReflectiveLegacySimpleClaimsPartyPermissionBypass
        implements LegacySimpleClaimsPartyPermissionBypass {
    private static final String MANAGER_CLASS = "com.buuz135.simpleclaims.claim.ClaimManager";
    private static final String PARTY_CLASS = "com.buuz135.simpleclaims.claim.party.PartyInfo";

    private final SimpleClaimsBreedingBridge bridge;
    @Nullable
    private final Method getInstance;
    @Nullable
    private final Method getPartyById;
    @Nullable
    private final Method hasPartyPermission;
    @Nullable
    private final String unavailableReason;

    ReflectiveLegacySimpleClaimsPartyPermissionBypass(@Nonnull SimpleClaimsBreedingBridge bridge) {
        this.bridge = bridge;
        Method resolvedGetInstance = null;
        Method resolvedGetPartyById = null;
        Method resolvedHasPartyPermission = null;
        String failure = null;
        try {
            ClassLoader loader = ReflectiveLegacySimpleClaimsPartyPermissionBypass.class.getClassLoader();
            Class<?> managerType = Class.forName(MANAGER_CLASS, false, loader);
            Class<?> partyType = Class.forName(PARTY_CLASS, false, loader);
            resolvedGetInstance = managerType.getMethod("getInstance");
            resolvedGetPartyById = managerType.getMethod("getPartyById", UUID.class);
            resolvedHasPartyPermission = partyType.getMethod("hasPartyPermission", UUID.class, String.class);
        } catch (Throwable throwable) {
            failure = message(throwable);
        }
        this.getInstance = resolvedGetInstance;
        this.getPartyById = resolvedGetPartyById;
        this.hasPartyPermission = resolvedHasPartyPermission;
        this.unavailableReason = failure;
    }

    @Nonnull
    @Override
    public Result evaluate(@Nullable String worldName,
                           @Nullable Vector3d position,
                           @Nonnull UUID attackerPlayerUuid,
                           @Nonnull String permissionKey) {
        if (getInstance == null || getPartyById == null || hasPartyPermission == null) {
            return new Result(Status.UNAVAILABLE, null, unavailableReason);
        }
        if (position == null || worldName == null || worldName.isBlank()) {
            return new Result(Status.ERROR, null, "Legacy bypass target context is missing.");
        }
        ClaimLookupResult lookup = bridge.lookupClaimIdentity(worldName, position);
        if (lookup.status() == ClaimLookupResult.Status.NO_CLAIM) {
            return Result.notGranted();
        }
        if (lookup.status() != ClaimLookupResult.Status.CLAIM_FOUND || lookup.key() == null) {
            Status status = lookup.status() == ClaimLookupResult.Status.UNAVAILABLE
                    ? Status.UNAVAILABLE
                    : Status.ERROR;
            return new Result(status, null, lookup.message());
        }
        ClaimPopulationKey key = lookup.key();
        UUID claimPartyId = key.ownerId();
        try {
            Object manager = getInstance.invoke(null);
            Object party = manager != null ? getPartyById.invoke(manager, claimPartyId) : null;
            if (party == null) {
                return new Result(Status.ERROR, claimPartyId, "Legacy bypass claim party was missing.");
            }
            Object granted = hasPartyPermission.invoke(party, attackerPlayerUuid, permissionKey);
            if (!(granted instanceof Boolean allowed)) {
                return new Result(Status.ERROR, claimPartyId, "Legacy bypass result was not boolean.");
            }
            return new Result(allowed ? Status.GRANTED : Status.NOT_GRANTED, claimPartyId, null);
        } catch (Throwable throwable) {
            return new Result(Status.ERROR, claimPartyId, message(throwable));
        }
    }

    @Nonnull
    private static String message(@Nullable Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        if (current == null) {
            return "unknown error";
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
