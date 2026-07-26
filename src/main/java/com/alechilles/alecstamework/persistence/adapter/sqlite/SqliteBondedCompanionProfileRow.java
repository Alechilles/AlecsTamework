package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable bonded profile row with no Hytale world-object dependencies. */
public record SqliteBondedCompanionProfileRow(
        @Nonnull String profileId,
        @Nonnull UUID ownerUuid,
        @Nonnull String rosterId,
        @Nonnull String familyId,
        @Nonnull String roleId,
        @Nonnull BondedCompanionState state,
        long revision,
        @Nonnull String snapshotJson,
        long createdAtMs,
        long updatedAtMs,
        @Nonnull String policyJson,
        @Nullable String displayName,
        @Nullable String species,
        @Nullable String gender,
        @Nullable Long diedAtMs,
        long reviveCooldownUntilMs,
        long reviveCount,
        @Nullable String quarantineReason,
        @Nullable Long quarantinedAtMs
) {
    public SqliteBondedCompanionProfileRow {
        profileId = requireText(profileId, "profileId");
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        rosterId = requireText(rosterId, "rosterId");
        familyId = requireText(familyId, "familyId");
        roleId = requireText(roleId, "roleId");
        state = Objects.requireNonNull(state, "state");
        snapshotJson = requireJson(snapshotJson, "snapshotJson", false);
        policyJson = requireJson(policyJson, "policyJson", true);
        displayName = normalize(displayName);
        species = normalize(species);
        gender = normalize(gender);
        quarantineReason = normalize(quarantineReason);
        if (revision < 0 || reviveCount < 0) {
            throw new IllegalArgumentException(
                    "Profile revision and revive count cannot be negative"
            );
        }
        if ((quarantineReason == null) != (quarantinedAtMs == null)) {
            throw new IllegalArgumentException(
                    "Quarantine reason and timestamp must be present together"
            );
        }
    }

    private static String requireJson(String value, String field, boolean emptyAllowed) {
        String normalized = requireText(value, field);
        if ((!emptyAllowed && !SqliteBondedJson.isNonEmptyObject(normalized))
                || (emptyAllowed && !SqliteBondedJson.isJson(normalized))) {
            throw new IllegalArgumentException(field + " must be JSON");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
