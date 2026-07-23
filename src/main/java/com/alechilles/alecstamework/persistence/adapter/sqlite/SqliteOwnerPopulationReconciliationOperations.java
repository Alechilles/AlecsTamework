package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationEvidenceClaim;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationEventCodec;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationOutcome;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationRequest;
import com.alechilles.alecstamework.companion.population.PopulationEvidenceAssessment;
import com.alechilles.alecstamework.companion.population.PopulationEvidenceObservation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import com.alechilles.alecstamework.persistence.incidents.IncidentRecord;
import com.alechilles.alecstamework.persistence.incidents.IncidentState;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Applies exact population evidence through the shared operation and containment protocols.
 */
public final class SqliteOwnerPopulationReconciliationOperations {
    public static final String FEATURE_SCOPE = "owner_population";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType(
                    "owner_population_reconciliation_committed"
            );

    private final SqliteDatabaseOperationCoordinator coordinator;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteOwnerPopulationReconciliationOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (coordinator == null || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Population reconciliation dependencies are required"
            );
        }
        this.coordinator = coordinator;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact evidence-based reconciliation. */
    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull OwnerPopulationReconciliationRequest reconciliation
    ) {
        if (operationId == null || idempotencyKey == null
                || reconciliation == null) {
            throw new IllegalArgumentException(
                    "Complete population reconciliation is required"
            );
        }
        return coordinator.execute(
                OwnerPopulationReconciliationDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        reconciliation,
                        FEATURE_SCOPE,
                        reconciliation.expectedLifecycleRevision(),
                        participants(reconciliation),
                        reconciliation.requestedAtMs()
                ),
                new ExactEvidenceDetail(reconciliation),
                (transaction, operation) -> commit(
                        transaction, operation, reconciliation
                ),
                requiredConsumers
        );
    }

    private List<OperationScope> participants(
            OwnerPopulationReconciliationRequest request
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.profile(request.profileId()));
        addOwner(scopes, request.expectedOwnerId());
        addOwner(scopes, request.evidence().observedOwnerId());
        return List.copyOf(scopes);
    }

    private void addOwner(
            TreeSet<OperationScope> scopes,
            OwnerId ownerId
    ) {
        if (ownerId != null) {
            scopes.add(OperationScope.owner(ownerId));
        }
    }

    private List<ProjectionEventDraft> commit(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            OwnerPopulationReconciliationRequest request
    ) {
        Evaluation evaluation = requireEvaluation(transaction, request);
        CompanionLifecycle source = evaluation.source();
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, request.profileId()
                );
        IncidentId incidentId = null;
        String reason = outcomeReason(evaluation.assessment().status());
        if (evaluation.assessment().status()
                != PopulationEvidenceAssessment.Status.PRESENT_MATCH) {
            incidentId = contain(
                    transaction,
                    operation,
                    request,
                    evaluation,
                    reason
            );
        }
        CompanionLifecycle committed = new CompanionLifecycle(
                source.profileId(),
                source.ownerId(),
                source.state(),
                source.location(),
                source.revision().next(),
                null,
                source.stateChangedAtMs(),
                incidentId == null
                        ? request.evidence().generation()
                        : source.lastReconciledGeneration(),
                incidentId,
                source.ownerWorldKey()
        );
        requireApplied(
                transaction.lifecycles().transition(
                        new LifecycleTransition(
                                source.revision(), null, committed
                        )
                ),
                "owner_population_reconciliation_lifecycle"
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, request.profileId()
                );
        return events(
                operation,
                request,
                source,
                committed,
                before,
                after,
                reason,
                incidentId
        );
    }

    private IncidentId contain(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            OwnerPopulationReconciliationRequest request,
            Evaluation evaluation,
            String reason
    ) {
        IncidentId incidentId = new IncidentId(UUID.nameUUIDFromBytes(
                ("owner-population-reconciliation:"
                        + operation.operationId() + ":" + reason)
                        .getBytes(StandardCharsets.UTF_8)
        ));
        IncidentRecord incident = new IncidentRecord(
                incidentId,
                "RECONCILIATION",
                reason,
                IncidentState.OPEN,
                "Owner population evidence contradicts canonical profile "
                        + request.profileId(),
                evidenceJson(operation, request, evaluation),
                request.requestedAtMs(),
                null
        );
        requireApplied(
                transaction.incidents().createIncident(incident),
                "owner_population_reconciliation_incident"
        );
        for (OperationScope scope : containmentScopes(request)) {
            requireApplied(
                    transaction.incidents().quarantine(
                            new ScopeQuarantine(
                                    scope,
                                    incidentId,
                                    QuarantineState.ACTIVE,
                                    reason,
                                    request.requestedAtMs(),
                                    null
                            )
                    ),
                    "owner_population_reconciliation_quarantine"
            );
        }
        return incidentId;
    }

    private List<OperationScope> containmentScopes(
            OwnerPopulationReconciliationRequest request
    ) {
        return participants(request);
    }

    private String evidenceJson(
            OperationEnvelope operation,
            OwnerPopulationReconciliationRequest request,
            Evaluation evaluation
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("operationId", operation.operationId().toString());
        json.addProperty("profileId", request.profileId().toString());
        json.addProperty("claimKind", request.evidence().kind().name());
        json.addProperty("bootId", request.evidence().bootId());
        json.addProperty("worldKey", request.evidence().worldKey());
        json.addProperty(
                "generation",
                request.evidence().generation().value()
        );
        json.addProperty(
                "assessment",
                evaluation.assessment().status().name()
        );
        nullable(json, "canonicalOwnerId", evaluation.source().ownerId());
        nullable(
                json,
                "canonicalOwnerWorldKey",
                evaluation.source().ownerWorldKey()
        );
        nullable(
                json,
                "observedOwnerId",
                request.evidence().observedOwnerId()
        );
        nullable(
                json,
                "observedOwnerWorldKey",
                request.evidence().observedOwnerWorldKey()
        );
        return json.toString();
    }

    private List<ProjectionEventDraft> events(
            OperationEnvelope operation,
            OwnerPopulationReconciliationRequest request,
            CompanionLifecycle source,
            CompanionLifecycle committed,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after,
            String reason,
            IncidentId incidentId
    ) {
        OwnerPopulationReconciliationOutcome outcome =
                new OwnerPopulationReconciliationOutcome(
                        request.profileId(),
                        source.revision(),
                        committed.revision(),
                        request.evidence().generation(),
                        incidentId == null
                                ? OwnerPopulationReconciliationOutcome.Status
                                .RECONCILED
                                : OwnerPopulationReconciliationOutcome.Status
                                .QUARANTINED,
                        reason,
                        incidentId,
                        request.requestedAtMs()
                );
        CompanionProfileProjectionChange profileChange =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.LIFECYCLE,
                        request.profileId(),
                        committed.revision().value(),
                        before,
                        after,
                        request.requestedAtMs()
                );
        return List.of(
                new ProjectionEventDraft(
                        operation.operationId(),
                        EVENT_TYPE,
                        "owner-population-reconciliation:"
                                + request.profileId(),
                        committed.revision().value(),
                        OwnerPopulationReconciliationEventCodec.VERSION,
                        OwnerPopulationReconciliationEventCodec.encode(
                                outcome
                        ),
                        request.requestedAtMs()
                ),
                SqliteCompanionProfileProjectionComposer.event(
                        operation.operationId(), profileChange
                ),
                CompanionLifecycleProjectionChangeCodec.draft(
                        operation.operationId(),
                        source,
                        committed,
                        request.requestedAtMs()
                )
        );
    }

    private static Evaluation requireEvaluation(
            SqlitePersistenceTransactionContext transaction,
            OwnerPopulationReconciliationRequest request
    ) {
        CompanionLifecycle source = transaction.lifecycles()
                .findByProfile(request.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "owner_population_reconciliation_profile_missing"
                ));
        if (!source.revision().equals(
                request.expectedLifecycleRevision()
        )
                || !Objects.equals(
                source.ownerId(), request.expectedOwnerId()
        )
                || !Objects.equals(
                source.ownerWorldKey(),
                request.expectedOwnerWorldKey()
        )
                || source.activeOperationId() != null
                || source.quarantined()
                || request.evidence().generation().compareTo(
                source.lastReconciledGeneration()
        ) <= 0) {
            throw new IllegalStateException(
                    "owner_population_reconciliation_source_mismatch"
            );
        }
        PopulationEvidenceAssessment assessment =
                assess(transaction, request, source);
        if (!assessment.actionable()) {
            throw new IllegalStateException(
                    assessment.reasonCode()
            );
        }
        return new Evaluation(source, assessment);
    }

    private static PopulationEvidenceAssessment assess(
            SqlitePersistenceTransactionContext transaction,
            OwnerPopulationReconciliationRequest request,
            CompanionLifecycle source
    ) {
        OwnerPopulationEvidenceClaim claim = request.evidence();
        if (claim.kind() == OwnerPopulationEvidenceClaim.Kind.ABSENCE) {
            return transaction.populationEvidence().assessAbsence(
                    claim.bootId(),
                    claim.worldKey(),
                    claim.generation(),
                    request.profileId()
            );
        }
        PopulationEvidenceObservation observation =
                transaction.populationEvidence().findObservation(
                        claim.positiveBatchKey(),
                        request.profileId()
                ).orElse(null);
        if (!claim.matches(observation)) {
            return new PopulationEvidenceAssessment(
                    PopulationEvidenceAssessment.Status.INCOMPLETE,
                    "population_positive_claim_mismatch",
                    null
            );
        }
        return transaction.populationEvidence().assessPositive(
                claim.positiveBatchKey(),
                request.profileId(),
                source.ownerId(),
                source.ownerWorldKey()
        );
    }

    private String outcomeReason(
            PopulationEvidenceAssessment.Status status
    ) {
        return switch (status) {
            case PRESENT_MATCH ->
                    "owner_population_positive_reconciled";
            case PRESENT_CONTRADICTION ->
                    "owner_population_evidence_contradiction";
            case ABSENT_PROVEN ->
                    "owner_population_absence_conflict";
            case PRESENT_INCOMPLETE, INCOMPLETE ->
                    throw new IllegalArgumentException(
                            "Incomplete evidence is not actionable"
                    );
        };
    }

    private void nullable(
            JsonObject json,
            String name,
            Object value
    ) {
        if (value == null) {
            json.add(name, null);
        } else {
            json.addProperty(name, value.toString());
        }
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

    /** Validation participant with no independent phase or recovery state. */
    private record ExactEvidenceDetail(
            OwnerPopulationReconciliationRequest request
    ) implements PreparedOperationDetail {
        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            requireEvaluation(transaction, request);
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            if (operation.phase() == OperationPhase.DURABLE
                    || operation.phase() == OperationPhase.PUBLISHED) {
                return true;
            }
            try {
                requireEvaluation(transaction, request);
                return operation.phase() == OperationPhase.PREPARED
                        || operation.phase() == OperationPhase.RETRYABLE;
            } catch (IllegalStateException invalid) {
                return false;
            }
        }
    }

    private record Evaluation(
            CompanionLifecycle source,
            PopulationEvidenceAssessment assessment
    ) {
    }
}
