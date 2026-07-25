package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable provenance for one intentionally provisioned canonical profile. */
public record ProvisioningRecord(
        @Nonnull ProfileId profileId,
        @Nonnull ProvisioningOrigin origin,
        @Nullable UUID correlationId,
        long policyRevision,
        @Nonnull OperationId creationOperationId,
        long createdAtMs
) {
    public ProvisioningRecord {
        if (profileId == null || origin == null
                || creationOperationId == null || policyRevision < 0
                || !profileId.equals(origin.profileId())) {
            throw new IllegalArgumentException(
                    "Complete provisioning provenance is required"
            );
        }
    }
}

