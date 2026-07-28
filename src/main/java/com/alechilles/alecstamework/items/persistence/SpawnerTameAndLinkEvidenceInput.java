package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;

/** Complete canonical and frozen input for one tame/link evidence authoring pass. */
public record SpawnerTameAndLinkEvidenceInput(
        @Nonnull OperationId operationId,
        long requestedAtMs,
        @Nonnull CompanionIdentity currentIdentity,
        @Nonnull CompanionLifecycle currentLifecycle,
        @Nonnull String expectedLiveRoleId,
        @Nonnull SpawnerTameAndLinkIntentEvidence intentEvidence
) {
    public SpawnerTameAndLinkEvidenceInput {
        expectedLiveRoleId = expectedLiveRoleId == null
                || expectedLiveRoleId.isBlank()
                ? null : expectedLiveRoleId.trim();
        if (operationId == null || currentIdentity == null
                || currentLifecycle == null || expectedLiveRoleId == null
                || intentEvidence == null) {
            throw new IllegalArgumentException(
                    "Complete tame/link authoring input is required"
            );
        }
    }
}
