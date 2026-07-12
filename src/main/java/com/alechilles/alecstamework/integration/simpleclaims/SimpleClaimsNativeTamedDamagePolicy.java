package com.alechilles.alecstamework.integration.simpleclaims;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Pure fail-open policy wrapper around SimpleClaims' native tamed-damage access capability.
 */
final class SimpleClaimsNativeTamedDamagePolicy {
    enum Status {
        ALLOWED,
        DENIED,
        SKIPPED,
        ALLOW_FAIL_OPEN
    }

    record Decision(boolean allowed,
                    @Nonnull Status status,
                    @Nullable UUID claimPartyId,
                    @Nullable String message,
                    @Nullable SimpleClaimsNativeDamageAccess.Status accessStatus) {
    }

    @Nonnull
    private final SimpleClaimsNativeDamageAccess access;

    SimpleClaimsNativeTamedDamagePolicy(@Nonnull SimpleClaimsNativeDamageAccess access) {
        this.access = access;
    }

    @Nonnull
    Decision evaluate(@Nullable String worldName,
                      @Nullable Vector3d position,
                      @Nullable UUID attackerPlayerId) {
        if (attackerPlayerId == null) {
            return new Decision(true, Status.SKIPPED, null, "attacker-missing", null);
        }
        if (position == null) {
            return failOpen("Position is missing.", SimpleClaimsNativeDamageAccess.Status.ERROR);
        }
        SimpleClaimsNativeDamageAccess.Result result = access.evaluate(
                worldName,
                position.x,
                position.z,
                attackerPlayerId
        );
        return map(result);
    }

    @Nonnull
    private static Decision map(@Nullable SimpleClaimsNativeDamageAccess.Result result) {
        if (result == null) {
            return failOpen("SimpleClaims native damage result was null.", SimpleClaimsNativeDamageAccess.Status.ERROR);
        }
        return switch (result.status()) {
            case ALLOWED -> new Decision(
                    true,
                    Status.ALLOWED,
                    result.claimPartyId(),
                    result.message(),
                    result.status()
            );
            case DENIED -> new Decision(
                    false,
                    Status.DENIED,
                    result.claimPartyId(),
                    result.message(),
                    result.status()
            );
            case UNAVAILABLE, ERROR -> new Decision(
                    true,
                    Status.ALLOW_FAIL_OPEN,
                    result.claimPartyId(),
                    result.message(),
                    result.status()
            );
        };
    }

    @Nonnull
    private static Decision failOpen(@Nonnull String message,
                                     @Nonnull SimpleClaimsNativeDamageAccess.Status accessStatus) {
        return new Decision(true, Status.ALLOW_FAIL_OPEN, null, message, accessStatus);
    }
}
