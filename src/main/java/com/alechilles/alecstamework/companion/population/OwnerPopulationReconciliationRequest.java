package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact canonical lifecycle revision and durable evidence claim to reconcile. */
public record OwnerPopulationReconciliationRequest(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision expectedLifecycleRevision,
        @Nullable OwnerId expectedOwnerId,
        @Nullable String expectedOwnerWorldKey,
        @Nonnull OwnerPopulationEvidenceClaim evidence,
        long requestedAtMs
) {
    public OwnerPopulationReconciliationRequest {
        if (profileId == null || expectedLifecycleRevision == null
                || evidence == null) {
            throw new IllegalArgumentException(
                    "Complete owner-population reconciliation is required"
            );
        }
        expectedOwnerWorldKey = expectedOwnerWorldKey == null
                || expectedOwnerWorldKey.isBlank()
                ? null
                : expectedOwnerWorldKey.trim();
        if (expectedOwnerId == null && expectedOwnerWorldKey != null) {
            throw new IllegalArgumentException(
                    "Expected owner world requires an owner"
            );
        }
    }
}

