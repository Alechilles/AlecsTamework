package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact existing-profile owner and owner-world transition with snapshotted limits.
 *
 * <p>Zero limits disable denial without suppressing positive reservation evidence.
 * The canonical lifecycle revision, owner, and owner world must all match before
 * preparation can succeed.</p>
 */
public record OwnerPopulationTransitionRequest(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nullable OwnerId expectedOwnerId,
        @Nullable String expectedOwnerWorldKey,
        @Nullable OwnerId targetOwnerId,
        @Nullable String targetOwnerWorldKey,
        int globalLimit,
        int perWorldLimit,
        long requestedAtMs,
        @Nullable LifecycleAdmissionEvidence admissionEvidence
) {
    public OwnerPopulationTransitionRequest(
            @Nonnull ProfileId profileId,
            @Nonnull LifecycleRevision expectedLifecycleRevision,
            @Nullable OwnerId expectedOwnerId,
            @Nullable String expectedOwnerWorldKey,
            @Nullable OwnerId targetOwnerId,
            @Nullable String targetOwnerWorldKey,
            int globalLimit,
            int perWorldLimit,
            long requestedAtMs
    ) {
        this(
                profileId,
                expectedLifecycleRevision,
                expectedOwnerId,
                expectedOwnerWorldKey,
                targetOwnerId,
                targetOwnerWorldKey,
                globalLimit,
                perWorldLimit,
                requestedAtMs,
                null
        );
    }

    public OwnerPopulationTransitionRequest {
        if (profileId == null || expectedLifecycleRevision == null) {
            throw new IllegalArgumentException(
                    "Population transition profile and revision are required"
            );
        }
        expectedOwnerWorldKey = normalize(expectedOwnerWorldKey);
        targetOwnerWorldKey = normalize(targetOwnerWorldKey);
        if ((expectedOwnerId == null && expectedOwnerWorldKey != null)
                || (targetOwnerId == null && targetOwnerWorldKey != null)) {
            throw new IllegalArgumentException(
                    "Only owned population states can carry an owner world"
            );
        }
        if (globalLimit < 0 || perWorldLimit < 0) {
            throw new IllegalArgumentException(
                    "Population limits cannot be negative"
            );
        }
        if (perWorldLimit > 0 && targetOwnerId != null
                && targetOwnerWorldKey == null) {
            throw new IllegalArgumentException(
                    "A capped per-world transition requires a target owner world"
            );
        }
        if (java.util.Objects.equals(expectedOwnerId, targetOwnerId)
                && java.util.Objects.equals(
                expectedOwnerWorldKey,
                targetOwnerWorldKey
        )) {
            throw new IllegalArgumentException(
                    "Population transition must change owner or owner world"
            );
        }
    }

    /** Returns this request with its one frozen managed admission result. */
    @Nonnull
    public OwnerPopulationTransitionRequest withAdmissionEvidence(
            @Nonnull LifecycleAdmissionEvidence evidence
    ) {
        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence is required"
            );
        }
        if (admissionEvidence != null && !admissionEvidence.equals(evidence)) {
            throw new IllegalArgumentException(
                    "Lifecycle admission evidence cannot be replaced"
            );
        }
        return new OwnerPopulationTransitionRequest(
                profileId,
                expectedLifecycleRevision,
                expectedOwnerId,
                expectedOwnerWorldKey,
                targetOwnerId,
                targetOwnerWorldKey,
                globalLimit,
                perWorldLimit,
                requestedAtMs,
                evidence
        );
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

