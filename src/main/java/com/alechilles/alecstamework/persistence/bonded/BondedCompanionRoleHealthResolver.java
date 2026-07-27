package com.alechilles.alecstamework.persistence.bonded;

import javax.annotation.Nullable;

/** Resolves the configured maximum health for a bonded companion role. */
@FunctionalInterface
interface BondedCompanionRoleHealthResolver {

    /** Returns the validated configured maximum health, or {@code null} when unavailable. */
    @Nullable
    Double resolveMaximumHealth(String roleId);
}
