package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Self-contained semantic evidence for one provisioned companion revival. */
public record ProvisionedCompanionRevivalOutcome(
        @Nonnull ProvisioningOrigin origin,
        @Nonnull ProfileId profileId,
        @Nonnull OwnerId ownerId,
        @Nonnull String roleId,
        @Nullable NpcAlias newAlias,
        @Nonnull LifecycleState lifecycle,
        @Nonnull CompanionProvisioningProjectionStatus projectionStatus,
        @Nonnull LifecycleRevision oldLifecycleRevision,
        @Nonnull LifecycleRevision newLifecycleRevision,
        long revivedAtMs
) {
    public ProvisionedCompanionRevivalOutcome {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(ownerId, "ownerId");
        roleId = requireText(roleId, "roleId");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(projectionStatus, "projectionStatus");
        Objects.requireNonNull(
                oldLifecycleRevision, "oldLifecycleRevision"
        );
        Objects.requireNonNull(
                newLifecycleRevision, "newLifecycleRevision"
        );
        boolean active = lifecycle == LifecycleState.ACTIVE
                && projectionStatus
                == CompanionProvisioningProjectionStatus.ACTIVE
                && newAlias != null;
        boolean dormant =
                lifecycle == LifecycleState.PROVISIONED_DORMANT
                        && projectionStatus
                        == CompanionProvisioningProjectionStatus.NOT_REQUESTED
                        && newAlias == null;
        if (!profileId.equals(origin.profileId())
                || !active && !dormant
                || newLifecycleRevision.compareTo(
                oldLifecycleRevision
        ) <= 0) {
            throw new IllegalArgumentException(
                    "Provisioned revival evidence is inconsistent"
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
