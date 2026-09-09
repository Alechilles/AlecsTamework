package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionDefinition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.persistence.incidents.IncidentState;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import java.sql.Connection;
import java.util.concurrent.CompletionStage;

/** Narrows legacy admission locks before startup loads them; never settles an unknown live effect. */
final class SqlitePopulationAdmissionContainmentRepair {
    static final String REASON = "domain_admission_live_effect_unknown";
    private final SqliteUnitOfWorkRunner units;

    SqlitePopulationAdmissionContainmentRepair(SqliteUnitOfWorkRunner units) {
        this.units = units;
    }

    CompletionStage<Void> repair(long now) {
        var command = new SqliteTransactionCommand<Integer>(
                OperationId.parse("00000000-0000-0000-0000-000000000058"),
                new OperationKind("population_admission_containment_repair"),
                TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                connection -> repair(connection, now));
        return units.execute(new SqliteUnitOfWork<>(command,
                new PersistenceReadKind("population_admission_containment_repair"),
                connection -> remaining(connection) == 0
                        ? PersistenceReadResult.found(0, 0) : PersistenceReadResult.absent()))
                .completion().thenApply(result -> {
                    if (!(result instanceof PersistenceTransactionResult.Committed<?>)) {
                        throw new IllegalStateException("population_admission_containment_repair_failed");
                    }
                    return null;
                });
    }

    private static int repair(Connection connection, long now) {
        var transaction = new SqlitePersistenceTransactionContext(connection);
        int released = 0;
        for (var quarantine : transaction.incidents().findAllActiveQuarantines()) {
            if (!repairable(transaction, quarantine)) continue;
            if (!transaction.incidents().release(quarantine.scope(), quarantine.incidentId(), now).applied()) {
                throw new IllegalStateException("population_admission_scope_release_failed");
            }
            released++;
        }
        return released;
    }

    private static long remaining(Connection connection) {
        var transaction = new SqlitePersistenceTransactionContext(connection);
        return transaction.incidents().findAllActiveQuarantines().stream()
                .filter(quarantine -> repairable(transaction, quarantine)).count();
    }

    private static boolean repairable(SqlitePersistenceTransactionContext transaction,
            com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine quarantine) {
        if (!REASON.equals(quarantine.reasonCode())
                || (quarantine.scope().type() != OperationScopeType.OWNER
                && quarantine.scope().type() != OperationScopeType.FEATURE)) return false;
        var incident = transaction.incidents().findIncident(quarantine.incidentId()).orElse(null);
        if (incident == null || incident.state() != IncidentState.OPEN
                || !REASON.equals(incident.failureCode())) return false;
        var fences = transaction.incidents().findQuarantines(incident.incidentId());
        var operationFence = fences.stream().filter(fence ->
                fence.state() == QuarantineState.ACTIVE && REASON.equals(fence.reasonCode())
                        && fence.scope().type() == OperationScopeType.OPERATION).findFirst().orElse(null);
        if (operationFence == null) return false;
        var operation = transaction.operations().find(OperationId.parse(operationFence.scope().key())).orElse(null);
        if (operation == null || operation.phase() != OperationPhase.UNKNOWN
                || !operation.participants().contains(quarantine.scope())
                || !PopulationDomainAdmissionOperation.supportsNarrowContainment(operation)
                || !intactReservations(transaction, operation)) return false;
        return PopulationDomainAdmissionOperation.containmentScopes(operation).stream().allMatch(scope ->
                fences.stream().anyMatch(fence -> fence.scope().equals(scope)
                        && fence.state() == QuarantineState.ACTIVE && REASON.equals(fence.reasonCode())));
    }

    static boolean intactReservations(SqlitePersistenceTransactionContext transaction, OperationEnvelope operation) {
        var payload = PopulationDomainAdmissionDefinition.INSTANCE.decode(operation.payloadJson());
        return new SqlitePopulationDomainParticipant(payload.reservations(operation.operationId()))
                .matches(transaction, operation);
    }
}
