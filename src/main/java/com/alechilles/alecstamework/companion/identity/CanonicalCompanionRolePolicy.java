package com.alechilles.alecstamework.companion.identity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Keeps temporary live parking roles out of durable companion identity. */
public final class CanonicalCompanionRolePolicy {
    private static final String TEMPORARY_PARKING_ROLE = "Empty_Role";

    private CanonicalCompanionRolePolicy() {
    }

    /** Returns whether the role is Hytale's temporary inert parking role. */
    public static boolean isTemporaryParkingRole(@Nullable String roleId) {
        return TEMPORARY_PARKING_ROLE.equals(clean(roleId));
    }

    /** Replaces only the temporary role with a separate real canonical role. */
    @Nonnull
    public static String repairTemporaryRole(
            @Nullable String observedRoleId,
            @Nullable String canonicalRoleId
    ) {
        String observed = clean(observedRoleId);
        String canonical = clean(canonicalRoleId);
        return isTemporaryParkingRole(observed)
                && !canonical.isEmpty()
                && !isTemporaryParkingRole(canonical)
                ? canonical : observed;
    }

    @Nonnull
    private static String clean(@Nullable String roleId) {
        return roleId == null ? "" : roleId.trim();
    }
}
