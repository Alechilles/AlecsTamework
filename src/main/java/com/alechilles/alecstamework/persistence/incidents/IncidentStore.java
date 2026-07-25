package com.alechilles.alecstamework.persistence.incidents;

import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Transaction-local store for replacement incidents and scoped quarantine fences. */
public interface IncidentStore {
    @Nonnull
    Optional<IncidentRecord> findIncident(@Nonnull IncidentId incidentId);

    /** Resolves an exact incident ID or one unambiguous canonical UUID prefix. */
    @Nonnull
    Optional<IncidentRecord> findIncidentByIdOrUniquePrefix(
            @Nonnull String incidentIdOrUniquePrefix
    );

    /** Returns every quarantine row attached to one incident in scope order. */
    @Nonnull
    List<ScopeQuarantine> findQuarantines(
            @Nonnull IncidentId incidentId
    );

    @Nonnull
    PersistenceMutationResult<IncidentRecord> createIncident(@Nonnull IncidentRecord incident);

    @Nonnull
    PersistenceMutationResult<IncidentRecord> resolveIncident(
            @Nonnull IncidentId incidentId,
            long resolvedAtMs
    );

    @Nonnull
    Optional<ScopeQuarantine> findQuarantine(@Nonnull OperationScope scope);

    @Nonnull
    List<ScopeQuarantine> findActiveQuarantines(
            @Nonnull List<OperationScope> candidateScopes
    );

    @Nonnull
    List<ScopeQuarantine> findAllActiveQuarantines();

    @Nonnull
    PersistenceMutationResult<ScopeQuarantine> quarantine(@Nonnull ScopeQuarantine quarantine);

    @Nonnull
    PersistenceMutationResult<ScopeQuarantine> release(
            @Nonnull OperationScope scope,
            @Nonnull IncidentId expectedIncidentId,
            long releasedAtMs
    );
}
