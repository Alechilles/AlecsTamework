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
        @Nonnull BondedCompanionCaptureAttemptEvidence attemptEvidence,
        @Nonnull String roleId,
        @Nullable String species,
        @Nonnull String rosterId,
        long rosterRevision,
        @Nullable BondedCompanionSnapshot snapshot,
        @Nullable SpawnerPublishedEffect completionEffect,
        boolean targetValid,
        boolean chanceSuccessful,
        boolean tranquilized,
        boolean toolAccess,
        boolean ownerAllowed,
        boolean roleAllowed,
        @Nullable String familyId,
        @Nonnull FamilySelection familySelection
) {
    public BondedCompanionCaptureIntent {
        callerNamespace = text(callerNamespace, "callerNamespace");
        idempotencyKey = text(idempotencyKey, "idempotencyKey");
        actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        worldKey = text(worldKey, "worldKey");
        sourceFingerprint = text(sourceFingerprint, "sourceFingerprint");
        sourceNpcUuid = Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        attemptEvidence = Objects.requireNonNull(
                attemptEvidence, "attemptEvidence");
        roleId = text(roleId, "roleId");
        species = optional(species);
        rosterId = text(rosterId, "rosterId");
        familyId = optional(familyId);
        familySelection = Objects.requireNonNull(
                familySelection, "familySelection");
        if (familySelection == FamilySelection.EXPLICIT && familyId == null) {
            throw new IllegalArgumentException(
                    "explicit family selection requires familyId");
        }
        if (hotbarSlot < 0 || rosterRevision < 0L) {
            throw new IllegalArgumentException("invalid bonded capture fence");
        }
        boolean evidenceSuccessful = attemptEvidence.outcome()
                == com.alechilles.alecstamework.api.CaptureAttemptOutcome
                .CAPTURED;
        if (chanceSuccessful != evidenceSuccessful) {
            throw new IllegalArgumentException(
                    "bonded chance and outcome evidence must agree");
        }
    }

    /** Source-compatible full constructor predating explicit family selection. */
    public BondedCompanionCaptureIntent(
            String callerNamespace, String idempotencyKey, UUID actorUuid,
            String worldKey, int hotbarSlot, String sourceFingerprint,
            UUID sourceNpcUuid,
            BondedCompanionCaptureAttemptEvidence attemptEvidence,
            String roleId, String species, String rosterId,
            long rosterRevision, BondedCompanionSnapshot snapshot,
            SpawnerPublishedEffect completionEffect, boolean targetValid,
            boolean chanceSuccessful, boolean tranquilized, boolean toolAccess,
            boolean ownerAllowed, boolean roleAllowed
    ) {
        this(callerNamespace, idempotencyKey, actorUuid, worldKey, hotbarSlot,
                sourceFingerprint, sourceNpcUuid, attemptEvidence, roleId,
                species, rosterId, rosterRevision, snapshot, completionEffect,
                targetValid, chanceSuccessful, tranquilized, toolAccess,
                ownerAllowed, roleAllowed, null, FamilySelection.ROLE_INFERRED);
    }

    /** Source-compatible constructor for callers predating completion evidence. */
    public BondedCompanionCaptureIntent(
            String callerNamespace, String idempotencyKey, UUID actorUuid,
            String worldKey, int hotbarSlot, String sourceFingerprint,
            UUID sourceNpcUuid, String roleId, String species, String rosterId,
            long rosterRevision, BondedCompanionSnapshot snapshot,
            SpawnerPublishedEffect completionEffect, boolean targetValid,
            boolean chanceSuccessful, boolean tranquilized, boolean toolAccess,
            boolean ownerAllowed, boolean roleAllowed
    ) {
        this(callerNamespace, idempotencyKey, actorUuid, worldKey, hotbarSlot,
                sourceFingerprint, sourceNpcUuid,
                legacyEvidence(idempotencyKey, chanceSuccessful), roleId,
                species, rosterId, rosterRevision, snapshot, completionEffect,
                targetValid, chanceSuccessful, tranquilized, toolAccess,
                ownerAllowed, roleAllowed, null, FamilySelection.ROLE_INFERRED);
    }

    public BondedCompanionCaptureIntent(
            String callerNamespace, String idempotencyKey, UUID actorUuid,
            String worldKey, int hotbarSlot, String sourceFingerprint,
            UUID sourceNpcUuid, String roleId, String rosterId,
            long rosterRevision, BondedCompanionSnapshot snapshot,
            SpawnerPublishedEffect completionEffect, boolean targetValid,
            boolean chanceSuccessful, boolean tranquilized, boolean toolAccess,
            boolean ownerAllowed, boolean roleAllowed
    ) {
        this(callerNamespace, idempotencyKey, actorUuid, worldKey, hotbarSlot,
                sourceFingerprint, sourceNpcUuid,
                legacyEvidence(idempotencyKey, chanceSuccessful), roleId, null,
                rosterId,
                rosterRevision, snapshot, completionEffect, targetValid,
                chanceSuccessful, tranquilized, toolAccess, ownerAllowed,
                roleAllowed, null, FamilySelection.ROLE_INFERRED);
    }

    /** Freezes an exact family selected from the live target role. */
    public BondedCompanionCaptureIntent(
            String callerNamespace, String idempotencyKey, UUID actorUuid,
            String worldKey, int hotbarSlot, String sourceFingerprint,
            UUID sourceNpcUuid, String roleId, String rosterId,
            long rosterRevision, BondedCompanionSnapshot snapshot,
            SpawnerPublishedEffect completionEffect, boolean targetValid,
            boolean chanceSuccessful, boolean tranquilized, boolean toolAccess,
            boolean ownerAllowed, boolean roleAllowed, String familyId
    ) {
        this(callerNamespace, idempotencyKey, actorUuid, worldKey, hotbarSlot,
                sourceFingerprint, sourceNpcUuid,
                legacyEvidence(idempotencyKey, chanceSuccessful), roleId, null,
                rosterId, rosterRevision, snapshot, completionEffect,
                targetValid, chanceSuccessful, tranquilized, toolAccess,
                ownerAllowed, roleAllowed, familyId,
                FamilySelection.ROLE_INFERRED);
    }

    /** Records whether family identity was caller-selected or role-derived. */
    public enum FamilySelection { ROLE_INFERRED, EXPLICIT }

    /** Stable profile identity shared by retries of this exact source capture. */
    @Nonnull
    public String profileId() {
        return UUID.nameUUIDFromBytes((callerNamespace + "\0" + actorUuid
                + "\0" + rosterId + "\0" + sourceNpcUuid)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    static BondedCompanionCaptureAttemptEvidence legacyEvidence(
            String idempotencyKey,
            boolean successful
    ) {
        UUID attempt = UUID.nameUUIDFromBytes(
                ("tamework:legacy-bonded-capture:" + idempotencyKey)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new BondedCompanionCaptureAttemptEvidence(
                attempt, "legacy:unknown", "legacy:unknown", 0L,
                null, -1L,
                com.alechilles.alecstamework.api.CaptureSourceConsumption
                        .SUCCESS_ONLY,
                com.alechilles.alecstamework.api.CaptureSuccessDisposition
                        .STORE_BONDED_COMPANION,
                successful
                        ? com.alechilles.alecstamework.api
                        .CaptureAttemptOutcome.CAPTURED
                        : com.alechilles.alecstamework.api
                        .CaptureAttemptOutcome.FAILED_ROLL,
                successful ? "legacy-bonded-capture-success"
                        : "legacy-bonded-capture-failed"
        );
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
