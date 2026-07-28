package com.alechilles.alecstamework.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable presentation-safe view of one canonical bonded companion. */
public record BondedCompanionProfileView(
        @Nonnull String profileId,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nonnull String familyId,
        @Nonnull String roleId,
        @Nullable String displayName,
        @Nullable String species,
        @Nullable String gender,
        long revision,
        @Nonnull BondedCompanionStateView state,
        boolean summonAvailable,
        boolean storeAvailable,
        boolean reviveAvailable,
        @Nonnull Map<String, String> snapshotPresentationData,
        @Nullable BondedCompanionLeaseView activeLease,
        long summonCooldownUntilMs,
        @Nullable BondedCompanionReviveQuote reviveQuote
) {
    public BondedCompanionProfileView {
        profileId = requireText(profileId, "profileId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        rosterId = requireText(rosterId, "rosterId");
        familyId = requireText(familyId, "familyId");
        roleId = requireText(roleId, "roleId");
        displayName = normalize(displayName);
        species = normalize(species);
        gender = normalize(gender);
        state = Objects.requireNonNull(state, "state");
        snapshotPresentationData = Map.copyOf(Objects.requireNonNull(
                snapshotPresentationData,
                "snapshotPresentationData"
        ));
        if (revision < 0L) {
            throw new IllegalArgumentException("revision cannot be negative.");
        }
        if ((state == BondedCompanionStateView.ACTIVE) != (activeLease != null)) {
            throw new IllegalArgumentException(
                    "Only active bonded companions carry an active lease."
            );
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
