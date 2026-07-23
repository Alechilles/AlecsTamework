package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import com.alechilles.alecstamework.persistence.incidents.IncidentRecord;
import com.alechilles.alecstamework.persistence.incidents.IncidentState;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/** Shared idempotent incident and narrow-scope quarantine writer for unknown operations. */
final class SqliteOperationContainmentEngine {
    private static final PersistenceReadKind CONTAINMENT_READBACK =
            new PersistenceReadKind("operation_unknown_containment_readback");

    private final SqliteUnitOfWorkRunner units;

    SqliteOperationContainmentEngine(SqliteUnitOfWorkRunner units) {
        if (units == null) {
            throw new IllegalArgumentException("Containment unit runner is required");
        }
        this.units = units;
    }

    SqliteUnitOfWorkRunner.Submission<IncidentRecord> contain(
            OperationEnvelope operation,
            String failureCode,
            String summary,
            List<OperationScope> scopes,
            long containedAtMs
    ) {
        if (operation == null || scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Operation and containment scopes are required"
            );
        }
        String code = requireText(failureCode, "Containment failure code");
        String boundedSummary = requireText(summary, "Containment summary");
        if (boundedSummary.length() > 500) {
            boundedSummary = boundedSummary.substring(0, 500);
        }
        List<OperationScope> exactScopes = exactScopes(operation, scopes);
        IncidentRecord incident = incident(
                operation, code, boundedSummary, exactScopes, containedAtMs
        );
        SqliteTransactionCommand<IncidentRecord> command =
                new SqliteTransactionCommand<>(
                        operation.operationId(),
                        operation.kind(),
                        TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                        connection -> write(
                                new SqliteIncidentStore(connection),
                                incident,
                                exactScopes,
                                containedAtMs
                        )
                );
        return units.execute(new SqliteUnitOfWork<>(
                command,
                CONTAINMENT_READBACK,
                connection -> readback(
                        new SqliteIncidentStore(connection),
                        incident,
                        exactScopes
                )
        ));
    }

    private IncidentRecord write(
            SqliteIncidentStore incidents,
            IncidentRecord incident,
            List<OperationScope> scopes,
            long containedAtMs
    ) {
        IncidentRecord stored = requireApplied(
                incidents.createIncident(incident),
                "operation_unknown_incident"
        );
        for (OperationScope scope : scopes) {
            requireApplied(
                    incidents.quarantine(new ScopeQuarantine(
                            scope,
                            incident.incidentId(),
                            QuarantineState.ACTIVE,
                            incident.failureCode(),
                            containedAtMs,
                            null
                    )),
                    "operation_unknown_quarantine"
            );
        }
        return stored;
    }

    private PersistenceReadResult<IncidentRecord> readback(
            SqliteIncidentStore incidents,
            IncidentRecord expected,
            List<OperationScope> scopes
    ) {
        IncidentRecord stored = incidents.findIncident(expected.incidentId())
                .orElse(null);
        if (!expected.equals(stored)) {
            return PersistenceReadResult.absent();
        }
        for (OperationScope scope : scopes) {
            ScopeQuarantine quarantine = incidents.findQuarantine(scope)
                    .orElse(null);
            if (quarantine == null
                    || quarantine.state() != QuarantineState.ACTIVE
                    || !quarantine.incidentId().equals(
                    expected.incidentId()
            )) {
                return PersistenceReadResult.absent();
            }
        }
        return PersistenceReadResult.found(stored, scopes.size());
    }

    private IncidentRecord incident(
            OperationEnvelope operation,
            String failureCode,
            String summary,
            List<OperationScope> scopes,
            long containedAtMs
    ) {
        IncidentId id = new IncidentId(UUID.nameUUIDFromBytes(
                ("operation-unknown:" + operation.operationId()
                        + ":" + failureCode).getBytes(StandardCharsets.UTF_8)
        ));
        JsonObject evidence = new JsonObject();
        evidence.addProperty("operationId", operation.operationId().toString());
        evidence.addProperty("operationKind", operation.kind().toString());
        evidence.addProperty("phase", operation.phase().name());
        JsonArray scopeJson = new JsonArray();
        for (OperationScope scope : scopes) {
            scopeJson.add(scope.type().name() + ":" + scope.key());
        }
        evidence.add("scopes", scopeJson);
        return new IncidentRecord(
                id,
                "LIVE_OUTCOME_UNKNOWN",
                failureCode,
                IncidentState.OPEN,
                summary,
                evidence.toString(),
                containedAtMs,
                null
        );
    }

    private List<OperationScope> exactScopes(
            OperationEnvelope operation,
            List<OperationScope> requested
    ) {
        TreeSet<OperationScope> available =
                new TreeSet<>(operation.participants());
        available.add(OperationScope.operation(operation.operationId()));
        TreeSet<OperationScope> exact = new TreeSet<>();
        for (OperationScope scope : requested) {
            if (scope == null || !available.contains(scope)) {
                throw new IllegalArgumentException(
                        "Containment scope is not an operation participant"
                );
            }
            exact.add(scope);
        }
        if (exact.isEmpty()) {
            throw new IllegalArgumentException("Containment scopes are required");
        }
        return List.copyOf(new ArrayList<>(exact));
    }

    private <T> T requireApplied(
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

    private String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
