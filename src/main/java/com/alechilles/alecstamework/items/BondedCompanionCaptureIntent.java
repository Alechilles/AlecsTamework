package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.items.persistence.SpawnerPublishedEffect;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Frozen input for the explicit bonded-companion capture disposition. */
public record BondedCompanionCaptureIntent(
        @Nonnull String callerNamespace,
        @Nonnull String idempotencyKey,
        @Nonnull UUID actorUuid,
        @Nonnull String worldKey,
        int hotbarSlot,
        @Nonnull String sourceFingerprint,
        @Nonnull UUID sourceNpcUuid,
        @Nonnull String roleId,
        @Nonnull String rosterId,
        long rosterRevision,
        @Nullable BondedCompanionSnapshot snapshot,
        @Nullable SpawnerPublishedEffect completionEffect,
        boolean targetValid,
        boolean chanceSuccessful,
        boolean tranquilized,
        boolean toolAccess,
        boolean ownerAllowed,
        boolean roleAllowed
) {
    public BondedCompanionCaptureIntent {
        callerNamespace = text(callerNamespace, "callerNamespace");
        idempotencyKey = text(idempotencyKey, "idempotencyKey");
        actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        worldKey = text(worldKey, "worldKey");
        sourceFingerprint = text(sourceFingerprint, "sourceFingerprint");
        sourceNpcUuid = Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        roleId = text(roleId, "roleId");
        rosterId = text(rosterId, "rosterId");
        if (hotbarSlot < 0 || rosterRevision < 0L) {
            throw new IllegalArgumentException("invalid bonded capture fence");
        }
    }

    /** Stable profile identity shared by retries of this exact source capture. */
    @Nonnull
    public String profileId() {
        return UUID.nameUUIDFromBytes((callerNamespace + "\0" + actorUuid
                + "\0" + rosterId + "\0" + sourceNpcUuid)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
