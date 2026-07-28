package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.incidents.IncidentRecord;
import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceIncidentEvidence;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Diagnostic-lane reader over the existing incident and operation stores.
 */
public final class SqliteContainmentReader {
    private static final PersistenceReadKind ACTIVE_QUARANTINE =
            new PersistenceReadKind("active_quarantine_by_scope");
    private static final PersistenceReadKind INCIDENT =
            new PersistenceReadKind("incident_by_prefix");

    private final SqliteReadExecutor reads;

    public SqliteContainmentReader(@Nonnull SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException(
                    "Containment read executor is required"
            );
        }
        this.reads = reads;
    }

    /** Returns the first active fence in canonical scope order. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<ScopeQuarantine>>
    findFirstActive(@Nonnull List<OperationScope> candidateScopes) {
        if (candidateScopes == null
                || candidateScopes.stream().anyMatch(
                java.util.Objects::isNull
        )) {
            throw new IllegalArgumentException(
                    "Complete quarantine candidates are required"
            );
        }
        List<OperationScope> candidates = List.copyOf(
                new java.util.TreeSet<>(candidateScopes)
        );
        return reads.execute(new SqliteReadCommand<>(
                ACTIVE_QUARANTINE,
                PersistenceReadPriority.DIAGNOSTIC,
                connection -> {
                    List<ScopeQuarantine> active =
                            new SqliteIncidentStore(connection)
                                    .findActiveQuarantines(candidates);
                    return active.isEmpty()
                            ? PersistenceReadResult.absent()
                            : PersistenceReadResult.found(
                            active.stream().min(
                                    this::compareContainment
                            ).orElseThrow(), 0L
                    );
                }
        ));
    }

    /** Resolves one incident and its exact containment/operation evidence. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<
            PublicPersistenceIncidentEvidence>> findIncident(
            @Nonnull String incidentIdOrUniquePrefix
    ) {
        if (incidentIdOrUniquePrefix == null
                || incidentIdOrUniquePrefix.isBlank()) {
            throw new IllegalArgumentException(
                    "Incident ID or prefix is required"
            );
        }
        String lookup = incidentIdOrUniquePrefix.trim();
        return reads.execute(new SqliteReadCommand<>(
                INCIDENT,
                PersistenceReadPriority.DIAGNOSTIC,
                connection -> {
                    SqlitePersistenceTransactionContext transaction =
                            new SqlitePersistenceTransactionContext(connection);
                    IncidentRecord incident = transaction.incidents()
                            .findIncidentByIdOrUniquePrefix(lookup)
                            .orElse(null);
                    if (incident == null) {
                        return PersistenceReadResult.absent();
                    }
                    List<ScopeQuarantine> quarantines =
                            transaction.incidents().findQuarantines(
                                    incident.incidentId()
                            );
                    Optional<OperationEnvelope> operation =
                            operationId(incident).flatMap(
                                    transaction.operations()::find
                            );
                    return PersistenceReadResult.found(
                            new PublicPersistenceIncidentEvidence(
                                    incident,
                                    quarantines,
                                    operation
                            ),
                            operation.map(OperationEnvelope::attemptCount)
                                    .orElse(0)
                    );
                }
        ));
    }

    private Optional<OperationId> operationId(IncidentRecord incident) {
        try {
            var json = JsonParser.parseString(
                    incident.evidenceJson()
            ).getAsJsonObject();
            if (!json.has("operationId")
                    || json.get("operationId").isJsonNull()) {
                return Optional.empty();
            }
            return Optional.of(OperationId.parse(
                    json.get("operationId").getAsString()
            ));
        } catch (RuntimeException malformedEvidence) {
            return Optional.empty();
        }
    }

    private int compareContainment(
            ScopeQuarantine left,
            ScopeQuarantine right
    ) {
        int priority = Integer.compare(
                containmentPriority(left.scope()),
                containmentPriority(right.scope())
        );
        return priority != 0
                ? priority
                : left.scope().compareTo(right.scope());
    }

    private int containmentPriority(OperationScope scope) {
        return switch (scope.type()) {
            case GLOBAL -> 0;
            case FEATURE -> 1;
            default -> 2;
        };
    }
}
