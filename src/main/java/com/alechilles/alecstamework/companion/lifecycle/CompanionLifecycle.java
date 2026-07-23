package com.alechilles.alecstamework.companion.lifecycle;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import com.alechilles.alecstamework.persistence.operation.OperationGeneration;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable canonical lifecycle row for one companion.
 *
 * @param profileId stable companion identity
 * @param ownerId current owner, when one is proven
 * @param state sole durable lifecycle state
 * @param location sole durable location
 * @param revision optimistic lifecycle revision
 * @param activeOperationId operation currently fencing mutation
 * @param stateChangedAtMs signed persisted transition time
 * @param lastReconciledGeneration latest completed reconciliation generation
 * @param quarantineIncidentId active lifecycle quarantine association
 */
public record CompanionLifecycle(@Nonnull ProfileId profileId,
                                 @Nullable OwnerId ownerId,
                                 @Nonnull LifecycleState state,
                                 @Nonnull LifecycleLocation location,
                                 @Nonnull LifecycleRevision revision,
                                 @Nullable OperationId activeOperationId,
                                 long stateChangedAtMs,
                                 @Nonnull OperationGeneration lastReconciledGeneration,
                                 @Nullable IncidentId quarantineIncidentId) {
    public CompanionLifecycle {
        if (profileId == null || state == null || location == null
                || revision == null || lastReconciledGeneration == null) {
            throw new IllegalArgumentException("Complete lifecycle state is required");
        }
        state.requireCompatible(location);
    }

    /** Returns whether mutation is denied by durable quarantine evidence. */
    public boolean quarantined() {
        return quarantineIncidentId != null;
    }
}
