package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Self-contained semantic evidence for one provisioned companion death. */
public record ProvisionedCompanionDeathOutcome(
        @Nonnull ProvisioningOrigin origin,
        @Nonnull ProfileId profileId,
        @Nonnull OwnerId ownerId,
        @Nonnull String roleId,
        @Nonnull NpcAlias lastAlias,
        @Nonnull LifecycleState lifecycle,
        @Nonnull CompanionProvisioningProjectionStatus projectionStatus,
        @Nonnull LifecycleRevision oldLifecycleRevision,
        @Nonnull LifecycleRevision newLifecycleRevision,
        long diedAtMs
) {
    public ProvisionedCompanionDeathOutcome {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(ownerId, "ownerId");
        roleId = requireText(roleId, "roleId");
        Objects.requireNonNull(lastAlias, "lastAlias");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(projectionStatus, "projectionStatus");
        Objects.requireNonNull(
                oldLifecycleRevision, "oldLifecycleRevision"
        );
        Objects.requireNonNull(
                newLifecycleRevision, "newLifecycleRevision"
        );
        if (!profileId.equals(origin.profileId())
                || lifecycle != LifecycleState.DEAD_REVIVABLE
                || projectionStatus
                != CompanionProvisioningProjectionStatus.UNAVAILABLE
                || newLifecycleRevision.compareTo(
                oldLifecycleRevision
        ) <= 0) {
            throw new IllegalArgumentException(
                    "Provisioned death evidence is inconsistent"
            );
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
