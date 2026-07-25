package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import com.alechilles.alecstamework.persistence.incidents.IncidentRecord;
import com.alechilles.alecstamework.persistence.incidents.IncidentState;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/** Resolves one exact UNKNOWN incident inside its proven durable commit. */
final class SqliteUnknownOperationContainmentRecovery {
    private SqliteUnknownOperationContainmentRecovery() {
    }

    static void resolve(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            long resolvedAtMs
    ) {
        if (transaction == null || operation == null
                || operation.phase() != OperationPhase.UNKNOWN) {
            throw new IllegalArgumentException(
                    "UNKNOWN containment recovery context is required"
            );
        }
        OperationScope operationScope = OperationScope.operation(
                operation.operationId()
        );
        ScopeQuarantine operationFence = transaction.incidents()
                .findQuarantine(operationScope)
                .orElse(null);
        if (operationFence == null) {
            return;
        }
        IncidentRecord incident = transaction.incidents()
                .findIncident(operationFence.incidentId())
                .orElseThrow(() -> new IllegalStateException(
                        "unknown_recovery_incident_missing"
                ));
        if (operationFence.state() == QuarantineState.RELEASED) {
            if (incident.state() != IncidentState.RESOLVED) {
                throw new IllegalStateException(
                        "unknown_recovery_release_incomplete"
                );
            }
            return;
        }
        requireExactIncident(operation, incident, operationFence);
        List<ScopeQuarantine> quarantines = transaction.incidents()
                .findQuarantines(incident.incidentId());
        TreeSet<OperationScope> allowed = new TreeSet<>(
                operation.participants()
        );
        allowed.add(operationScope);
        if (quarantines.isEmpty()) {
            throw new IllegalStateException(
                    "unknown_recovery_quarantines_missing"
            );
        }
        for (ScopeQuarantine quarantine : quarantines) {
            if (quarantine.state() != QuarantineState.ACTIVE
                    || !allowed.contains(quarantine.scope())
                    || !incident.failureCode().equals(
                    quarantine.reasonCode()
            )) {
                throw new IllegalStateException(
                        "unknown_recovery_quarantine_mismatch"
                );
            }
            requireApplied(
                    transaction.incidents().release(
                            quarantine.scope(),
                            incident.incidentId(),
                            resolvedAtMs
                    ),
                    "unknown_recovery_quarantine_release"
            );
        }
        requireApplied(
                transaction.incidents().resolveIncident(
                        incident.incidentId(), resolvedAtMs
                ),
                "unknown_recovery_incident_resolve"
        );
    }

    private static void requireExactIncident(
            OperationEnvelope operation,
            IncidentRecord incident,
            ScopeQuarantine operationFence
    ) {
        if (incident.state() != IncidentState.OPEN
                || !incident.incidentId().equals(expectedIncidentId(operation))
                || !"LIVE_OUTCOME_UNKNOWN".equals(
                incident.failureKind()
        )
                || operation.failureCode() == null
                || !operation.failureCode().equals(
                incident.failureCode()
        )
                || !incident.failureCode().equals(
                operationFence.reasonCode()
        )) {
            throw new IllegalStateException(
                    "unknown_recovery_incident_mismatch"
            );
        }
    }

    private static IncidentId expectedIncidentId(
            OperationEnvelope operation
    ) {
        return new IncidentId(UUID.nameUUIDFromBytes(
                ("operation-unknown:" + operation.operationId()
                        + ":" + operation.failureCode())
                        .getBytes(StandardCharsets.UTF_8)
        ));
    }

    private static <T> T requireApplied(
            PersistenceMutationResult<T> result,
            String operation
    ) {
        if (result == null || !result.applied()) {
            throw new IllegalStateException(
                    operation + "_" + (result == null
                            ? "null"
                            : result.status().name().toLowerCase())
            );
        }
        return result.value();
    }
}
