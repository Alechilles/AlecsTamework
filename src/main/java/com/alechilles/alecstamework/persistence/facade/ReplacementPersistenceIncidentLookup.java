package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.PersistenceIncidentSummaryView;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.incidents.IncidentRecord;
import com.alechilles.alecstamework.persistence.incidents.IncidentState;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceIncidentEvidence;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/**
 * Sanitizes bounded replacement incident evidence for the public API.
 */
public final class ReplacementPersistenceIncidentLookup
        implements ReplacementPersistenceDiagnosticsApi.IncidentLookup {
    private final PersistenceFeatureRegistry registry;
    private final PersistenceScopeFactory scopeHashes;
    private final EvidenceLookup evidence;
    private final long timeoutNanos;

    public ReplacementPersistenceIncidentLookup(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull PersistenceScopeFactory scopeHashes,
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull Duration timeout
    ) {
        this(registry, scopeHashes, queries::findIncidentEvidence, timeout);
    }

    public ReplacementPersistenceIncidentLookup(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull PersistenceScopeFactory scopeHashes,
            @Nonnull EvidenceLookup evidence,
            @Nonnull Duration timeout
    ) {
        if (registry == null || scopeHashes == null || evidence == null
                || timeout == null || timeout.isZero()
                || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Complete incident lookup dependencies are required"
            );
        }
        this.registry = registry;
        this.scopeHashes = scopeHashes;
        this.evidence = evidence;
        timeoutNanos = timeout.toNanos();
    }

    @Override
    @Nonnull
    public Optional<PersistenceIncidentSummaryView> find(
            @Nonnull String incidentIdOrUniquePrefix
    ) {
        if (incidentIdOrUniquePrefix == null
                || incidentIdOrUniquePrefix.isBlank()) {
            return Optional.empty();
        }
        try {
            CompletionStage<PersistenceReadResult<
                    PublicPersistenceIncidentEvidence>> stage =
                    evidence.find(incidentIdOrUniquePrefix.trim());
            if (stage == null) {
                return Optional.empty();
            }
            PersistenceReadResult<PublicPersistenceIncidentEvidence> read =
                    stage.toCompletableFuture().get(
                            timeoutNanos, TimeUnit.NANOSECONDS
                    );
            return read instanceof PersistenceReadResult.Found<
                    PublicPersistenceIncidentEvidence> found
                    ? Optional.of(map(found.value()))
                    : Optional.empty();
        } catch (Exception unavailable) {
            return Optional.empty();
        }
    }

    private PersistenceIncidentSummaryView map(
            PublicPersistenceIncidentEvidence evidence
    ) {
        IncidentRecord incident = evidence.incident();
        Optional<OperationEnvelope> operation = evidence.operation();
        long lastSeen = incident.resolvedAtMs() == null
                ? incident.createdAtMs()
                : incident.resolvedAtMs();
        return new PersistenceIncidentSummaryView(
                incident.incidentId().toString(),
                incident.state().name(),
                domain(incident, operation),
                phase(incident, operation),
                incident.failureCode(),
                incident.failureKind(),
                disposition(incident, evidence.quarantines()),
                incident.createdAtMs(),
                lastSeen,
                1L,
                operation.map(OperationEnvelope::attemptCount).orElse(0),
                null,
                null,
                scopes(evidence.quarantines())
        );
    }

    private String domain(
            IncidentRecord incident,
            Optional<OperationEnvelope> operation
    ) {
        if (operation.isPresent()) {
            try {
                return registry.requireOperation(
                        operation.orElseThrow().kind()
                ).domain().name();
            } catch (RuntimeException unknownKind) {
                return "UNKNOWN";
            }
        }
        return switch (incident.failureKind()) {
            case "IMPORT_CONFLICT" -> "LIFECYCLE";
            case "RECONCILIATION" -> "POPULATION";
            default -> "UNKNOWN";
        };
    }

    private String phase(
            IncidentRecord incident,
            Optional<OperationEnvelope> operation
    ) {
        try {
            var json = JsonParser.parseString(
                    incident.evidenceJson()
            ).getAsJsonObject();
            return OperationPhase.valueOf(
                    json.get("phase").getAsString()
            ).name();
        } catch (RuntimeException absentOrInvalid) {
            return operation.map(row -> row.phase().name())
                    .orElse("UNKNOWN");
        }
    }

    private String disposition(
            IncidentRecord incident,
            List<ScopeQuarantine> quarantines
    ) {
        if (incident.state() == IncidentState.RESOLVED) {
            return "RESOLVED";
        }
        return quarantines.stream().anyMatch(row ->
                row.state() == QuarantineState.ACTIVE)
                ? "SCOPED_QUARANTINE"
                : "MANUAL_REVIEW";
    }

    private List<PersistenceIncidentSummaryView.ScopeView> scopes(
            List<ScopeQuarantine> quarantines
    ) {
        ArrayList<PersistenceIncidentSummaryView.ScopeView> result =
                new ArrayList<>();
        quarantines.stream()
                .sorted(Comparator.comparing(
                        ScopeQuarantine::scope
                ))
                .forEach(row -> {
                    String kind = row.scope().type().name();
                    result.add(
                            new PersistenceIncidentSummaryView.ScopeView(
                                    kind,
                                    scopeHashes.hashLocalScope(
                                            kind, row.scope().key()
                                    ),
                                    authorityDimension(row.scope().type())
                            )
                    );
                });
        return List.copyOf(result);
    }

    private String authorityDimension(OperationScopeType type) {
        return switch (type) {
            case OPERATION -> "operation_envelope";
            case PROFILE -> "companion_profile";
            case OWNER -> "owner_population";
            case COOP -> "coop_residency";
            case TOOL -> "companion_tool_link";
            case COMMAND_FAMILY -> "command_roster";
            case FEATURE -> "persistence_feature";
            case GLOBAL -> "persistence_engine";
        };
    }

    /** Existing bounded incident evidence reader. */
    @FunctionalInterface
    public interface EvidenceLookup {
        @Nonnull
        CompletionStage<PersistenceReadResult<
                PublicPersistenceIncidentEvidence>> find(
                @Nonnull String incidentIdOrUniquePrefix
        );
    }
}
