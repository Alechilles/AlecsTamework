package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupTransitionAdmissionRequest;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteManagedAdmissionParticipant;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOwnerPopulationParticipant;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePopulationDomainParticipant;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePopulationGroupTransitionParticipant;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteUnitOfWorkRunner;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/** Retains composed participants for retries in one process and supports restart-safe fallback. */
final class PopulationDomainAdmissionParticipantRegistry {
    private final ConcurrentMap<UUID, SqliteManagedAdmissionParticipant> values =
            new ConcurrentHashMap<>();

    SqliteManagedAdmissionParticipant getOrCreate(
            OperationId operationId,
            PopulationDomainAdmissionOperation.Payload payload,
            @Nullable OwnerPopulationAdmissionPlan ownerPlan,
            @Nullable PopulationGroupTransitionAdmissionRequest groupRequest
    ) {
        return values.computeIfAbsent(operationId.value(), ignored -> create(
                operationId, payload, ownerPlan, groupRequest
        ));
    }

    /** Releases participant evidence after the operation has published terminally. */
    void evict(OperationId operationId) {
        if (operationId != null) {
            values.remove(operationId.value());
        }
    }

    SqliteUnitOfWorkRunner.Submission<OperationEnvelope> wrapPreparation(
            OperationId operationId,
            SqliteUnitOfWorkRunner.Submission<OperationEnvelope> submission
    ) {
        return new SqliteUnitOfWorkRunner.Submission<>(
                submission.acceptance(),
                submission.completion().thenApply(result -> {
                    if (result instanceof PersistenceTransactionResult.RolledBack<?>
                            || result instanceof PersistenceTransactionResult.Rejected<?>) {
                        evict(operationId);
                    }
                    return result;
                })
        );
    }

    private SqliteManagedAdmissionParticipant create(
            OperationId operationId,
            PopulationDomainAdmissionOperation.Payload payload,
            OwnerPopulationAdmissionPlan ownerPlan,
            PopulationGroupTransitionAdmissionRequest groupRequest
    ) {
        SqliteOwnerPopulationParticipant owner = ownerPlan == null
                ? null : new SqliteOwnerPopulationParticipant(
                ownerPlan, payload.requestedCount()
        );
        SqlitePopulationGroupTransitionParticipant groups = groupRequest == null
                ? null : new SqlitePopulationGroupTransitionParticipant(
                groupRequest, payload.requestedCount()
        );
        return new SqliteManagedAdmissionParticipant(
                new SqlitePopulationDomainParticipant(
                        payload.reservations(operationId), true
                ),
                owner,
                groups
        );
    }
}
