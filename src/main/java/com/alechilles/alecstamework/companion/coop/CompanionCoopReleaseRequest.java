package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable command to restore one exact coop residency under a pre-leased live alias. */
public record CompanionCoopReleaseRequest(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nonnull CoopResidency sourceResidency,
        @Nonnull CompanionSnapshot sourceSnapshot,
        @Nonnull NpcAlias targetAlias,
        @Nonnull CompanionSpawnPlacement placement,
        @Nonnull String spawnReceiptKey,
        long requestedAtMs,
        @Nullable LifecycleAdmissionEvidence admissionEvidence
) {
    /** Creates an ordinary coop release without frozen admission evidence. */
    public CompanionCoopReleaseRequest(
            ProfileId profileId,
            LifecycleRevision expectedLifecycleRevision,
            CoopResidency sourceResidency,
            CompanionSnapshot sourceSnapshot,
            NpcAlias targetAlias,
            CompanionSpawnPlacement placement,
            String spawnReceiptKey,
            long requestedAtMs
    ) {
        this(
                profileId,
                expectedLifecycleRevision,
                sourceResidency,
                sourceSnapshot,
                targetAlias,
                placement,
                spawnReceiptKey,
                requestedAtMs,
                null
        );
    }

    public CompanionCoopReleaseRequest {
        if (profileId == null || expectedLifecycleRevision == null
                || sourceResidency == null || sourceSnapshot == null
                || targetAlias == null || placement == null) {
            throw new IllegalArgumentException("Complete coop release request is required");
        }
        spawnReceiptKey = requireText(spawnReceiptKey, "Coop release spawn receipt");
        if (!profileId.equals(sourceResidency.profileId())
                || !profileId.equals(sourceSnapshot.profileId())
                || !sourceResidency.snapshotId().equals(
                sourceSnapshot.snapshotId()
        )
                || !CompanionCoopCaptureRequest.SNAPSHOT_KIND.equals(
                sourceSnapshot.kind()
        )
                || !sourceSnapshot.current()
                || sourceSnapshot.sourceLifecycleRevision()
                .compareTo(expectedLifecycleRevision) > 0) {
            throw new IllegalArgumentException(
                    "Coop release must reference the exact current residency snapshot"
            );
        }
    }

    /** Returns this request with one immutable provider-admission result. */
    @Nonnull
    public CompanionCoopReleaseRequest withAdmissionEvidence(
            @Nonnull LifecycleAdmissionEvidence evidence
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence is required"
            );
        }
        if (admissionEvidence != null
                && !admissionEvidence.equals(evidence)) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence cannot be replaced"
            );
        }
        return new CompanionCoopReleaseRequest(
                profileId,
                expectedLifecycleRevision,
                sourceResidency,
                sourceSnapshot,
                targetAlias,
                placement,
                spawnReceiptKey,
                requestedAtMs,
                evidence
        );
    }

    /** Returns the canonical target world without storing a second placement authority. */
    @Nonnull
    public String targetWorldKey() {
        return placement.worldKey();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
