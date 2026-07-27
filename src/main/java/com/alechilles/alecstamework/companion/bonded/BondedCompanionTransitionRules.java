package com.alechilles.alecstamework.companion.bonded;

import java.util.UUID;

/** Focused validation and signed-time rules shared by lifecycle transitions. */
final class BondedCompanionTransitionRules {
    private BondedCompanionTransitionRules() {
    }

    static BondedCompanionTransitionService.ResultCode snapshotIdentity(
            BondedCompanionPolicy policy,
            UUID expectedOwner,
            String expectedRole,
            BondedCompanionSnapshot snapshot
    ) {
        var state = snapshot.fullState();
        if (state.owner() == null
                || !expectedOwner.equals(state.owner().getOwnerId())) {
            return BondedCompanionTransitionService.ResultCode
                    .SNAPSHOT_OWNER_MISMATCH;
        }
        String snapshotRole = state.roleId();
        if (snapshotRole == null) {
            return BondedCompanionTransitionService.ResultCode
                    .SNAPSHOT_ROLE_MISMATCH;
        }
        if (!expectedRole.equals(snapshotRole)) {
            return BondedCompanionTransitionService.ResultCode
                    .SNAPSHOT_ROLE_MISMATCH;
        }
        return policy.allowedRoles().contains(snapshotRole) ? null
                : BondedCompanionTransitionService.ResultCode.ROLE_NOT_ALLOWED;
    }

    static long timeAfterSeconds(long nowMs, long seconds) {
        if (seconds == 0L) {
            return 0L;
        }
        long result = Math.addExact(
                nowMs, Math.multiplyExact(seconds, 1_000L)
        );
        if (result == 0L) {
            throw new ArithmeticException(
                    "finite time collides with zero sentinel"
            );
        }
        return result;
    }

    static boolean atCapacity(int count, int configuredLimit) {
        return configuredLimit != 0 && count >= configuredLimit;
    }
}
