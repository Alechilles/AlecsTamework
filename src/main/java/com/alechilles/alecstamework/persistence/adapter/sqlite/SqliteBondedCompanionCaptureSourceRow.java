package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionCaptureEvidence;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable profile-lifetime authority for one captured source NPC. */
record SqliteBondedCompanionCaptureSourceRow(
        @Nonnull BondedCompanionCaptureEvidence evidence,
        @Nonnull SqliteBondedCompanionProfileRow capturedProfile,
        @Nonnull String requestHash,
        @Nullable Long eventPublishedAtMs
) {
    SqliteBondedCompanionCaptureSourceRow {
        evidence = Objects.requireNonNull(evidence, "evidence");
        capturedProfile = Objects.requireNonNull(
                capturedProfile, "capturedProfile");
        requestHash = requireHash(requestHash);
        if (!capturedProfile.profileId().equals(evidence.profileId())
                || !capturedProfile.ownerUuid().equals(evidence.ownerUuid())
                || !capturedProfile.rosterId().equals(evidence.rosterId())
                || !capturedProfile.familyId().equals(evidence.familyId())
                || !capturedProfile.roleId().equals(evidence.roleId())) {
            throw new IllegalArgumentException(
                    "capture authority profile does not match evidence");
        }
    }

    private static String requireHash(String value) {
        String normalized = Objects.requireNonNull(value, "requestHash").trim();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be lowercase SHA-256");
        }
        return normalized;
    }
}
