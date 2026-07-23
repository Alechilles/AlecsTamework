package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonical result of applying one exact owner-population evidence claim. */
public record OwnerPopulationReconciliationOutcome(
        @Nonnull ProfileId profileId,
        @Nonnull LifecycleRevision sourceRevision,
        @Nonnull LifecycleRevision committedRevision,
        @Nonnull ReconciliationGeneration evidenceGeneration,
        @Nonnull Status status,
        @Nonnull String reasonCode,
        @Nullable IncidentId quarantineIncidentId,
        long committedAtMs
) {
    public OwnerPopulationReconciliationOutcome {
        if (profileId == null || sourceRevision == null
                || committedRevision == null
                || evidenceGeneration == null || status == null
                || reasonCode == null || reasonCode.isBlank()
                || !sourceRevision.next().equals(committedRevision)) {
            throw new IllegalArgumentException(
                    "Complete single-revision reconciliation outcome is required"
            );
        }
        reasonCode = reasonCode.trim();
        if ((status == Status.QUARANTINED)
                != (quarantineIncidentId != null)) {
            throw new IllegalArgumentException(
                    "Only quarantined reconciliation carries an incident"
            );
        }
    }

    public enum Status {
        RECONCILED,
        QUARANTINED
    }
}
