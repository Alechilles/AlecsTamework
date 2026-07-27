package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.util.Objects;

/** Evaluates owner/family capacity before a new bonded profile is inserted. */
final class SqliteBondedCompanionCapacityPolicy {
    private final SqliteBondedCompanionProfileReader profiles;

    SqliteBondedCompanionCapacityPolicy(
            SqliteBondedCompanionProfileReader profiles
    ) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    SqliteBondedCompanionStore.MutationResult<SqliteBondedCompanionProfileRow>
            denial(SqliteBondedCompanionProfileRow profile, int maximumOwned) {
        if (maximumOwned < 0) {
            return new SqliteBondedCompanionStore.MutationResult<>(
                    SqliteBondedCompanionStore.MutationCode.VALIDATION_FAILED,
                    null, "bonded-capacity-invalid");
        }
        if (maximumOwned == 0) return null;
        long owned = profiles.countFamily(
                profile.ownerUuid(), profile.rosterId(), profile.familyId());
        return owned >= maximumOwned
                ? new SqliteBondedCompanionStore.MutationResult<>(
                SqliteBondedCompanionStore.MutationCode.CONFLICT, null,
                "bonded-family-capacity-reached")
                : null;
    }
}
