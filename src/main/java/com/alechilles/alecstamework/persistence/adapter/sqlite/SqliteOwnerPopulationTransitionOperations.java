package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlanner;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionEventCodec;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionOutcome;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlan;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainConvergencePlanner;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainPort;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Existing-profile ownership transitions through the shared database operation protocol.
 */
public final class SqliteOwnerPopulationTransitionOperations {
    public static final String FEATURE_SCOPE = "owner_population";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("owner_population_transition_committed");

    private final SqliteDatabaseOperationCoordinator coordinator;
    @Nullable private final SqliteOperationReader reader;
    @Nullable
    private final SqliteManagedOwnerPopulationAdmission admission;
    private final SqliteLifecycleAdmissionSingleFlight singleFlight =
            new SqliteLifecycleAdmissionSingleFlight();
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteOwnerPopulationTransitionOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        this(coordinator, null, null, null, requiredConsumers);
    }

    SqliteOwnerPopulationTransitionOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nullable SqliteOperationReader reader,
            @Nullable SqliteLifecycleAdmissionBinding lifecycleAdmission,
            @Nullable SqliteLifecycleAdmissionSourceReader sourceReader,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (coordinator == null || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Population transition dependencies are required"
            );
        }
        this.coordinator = coordinator;
        this.reader = reader;
        admission = reader == null || lifecycleAdmission == null
                || sourceReader == null
                ? null
                : new SqliteManagedOwnerPopulationAdmission(
                        reader, lifecycleAdmission, sourceReader
                );
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact owner and owner-world transition. */
    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull OwnerPopulationTransitionRequest transition
    ) {
        if (operationId == null || idempotencyKey == null
                || transition == null) {
            throw new IllegalArgumentException(
                    "Complete population transition is required"
            );
        }
        if (!positiveTarget(transition)
                && transition.admissionEvidence() != null) {
            return rejected(
                    "owner_population_nonpositive_admission_evidence_forbidden"
            );
        }
        if (!positiveTarget(transition)) {
            return release(operationId, idempotencyKey, transition);
        }
        if (admission == null) {
            return rejected("owner_population_lifecycle_admission_unbound");
        }
        CompletionStage<OperationWorkflowResult> completion =
                singleFlight.submit(
                        OwnerPopulationTransitionDefinition.KIND,
                        operationId,
                        idempotencyKey,
                        OwnerPopulationTransitionDefinition.INSTANCE.encode(
                                transition
                        ),
                        () -> admission.resolve(
                                        operationId,
                                        idempotencyKey,
                                        transition
                                )
                                .thenCompose(value -> execute(
                                        operationId, idempotencyKey, value
                                ).completion())
                );
        return new SqliteDatabaseOperationCoordinator.Submission(
                SqliteSingleWriter.WriteAcceptance.ACCEPTED,
                completion.exceptionally(failure ->
                        SqliteOperationResults.failed(
                                OperationWorkflowResult.Status.PREPARE_FAILED,
                                null,
                                List.of(),
                                failure instanceof java.util.concurrent
                                .CompletionException
                                && failure.getCause() != null
                                        ? failure.getCause() : failure
                        )
                )
        );
    }

    private SqliteDatabaseOperationCoordinator.Submission execute(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            OwnerPopulationTransitionRequest transition
    ) {
        return execute(operationId, idempotencyKey, transition, participants(transition));
    }

    private SqliteDatabaseOperationCoordinator.Submission release(
            OperationId operationId, IdempotencyKey key, OwnerPopulationTransitionRequest transition
    ) {
        if (reader == null) return execute(operationId, key, transition);
        // Older saved releases include OWNER. Preserve their exact envelope on replay.
        var completion = reader.findByIdempotency(OwnerPopulationTransitionDefinition.KIND, key)
                .thenCompose(read -> {
                    if (read instanceof PersistenceReadResult.Found<
                            SqliteOperationReader.OperationReadModel> found) {
                        var saved = found.value().operation();
                        if (!saved.operationId().equals(operationId)
                                || !saved.kind().equals(OwnerPopulationTransitionDefinition.KIND)
                                || !saved.idempotencyKey().equals(key)
                                || !saved.featureScope().equals(FEATURE_SCOPE)
                                || saved.payloadVersion() != OwnerPopulationTransitionDefinition.INSTANCE.payloadVersion()
                                || !OwnerPopulationTransitionDefinition.INSTANCE.decode(saved.payloadJson()).equals(transition)) {
                            return rejected("owner_population_release_replay_conflict").completion();
                        }
                        var semanticScopes = saved.participants().stream()
                                .filter(scope -> scope.type() != OperationScopeType.OPERATION
                                        && scope.type() != OperationScopeType.FEATURE).toList();
                        var expectedScopes = new TreeSet<>(participants(transition));
                        if (semanticScopes.stream().anyMatch(scope -> scope.type() == OperationScopeType.OWNER)) {
                            addOwner(expectedScopes, transition.expectedOwnerId());
                        }
                        if (!semanticScopes.equals(List.copyOf(expectedScopes))) {
                            return rejected("owner_population_release_replay_scopes_conflict").completion();
                        }
                        return execute(operationId, key, transition,
                                semanticScopes).completion();
                    }
                    if (read instanceof PersistenceReadResult.Absent<?>) {
                        return execute(operationId, key, transition).completion();
                    }
                    return rejected("owner_population_release_read_failed").completion();
                });
        return new SqliteDatabaseOperationCoordinator.Submission(
                SqliteSingleWriter.WriteAcceptance.ACCEPTED,
                completion.exceptionally(failure -> SqliteOperationResults.failed(
                        OperationWorkflowResult.Status.PREPARE_FAILED, null, List.of(), failure)));
    }

    private SqliteDatabaseOperationCoordinator.Submission execute(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            OwnerPopulationTransitionRequest transition,
            List<OperationScope> scopes
    ) {
        Optional<OwnerPopulationAdmissionPlan> plan =
                OwnerPopulationAdmissionPlanner.plan(transition);
        SqliteOwnerPopulationParticipant population = needsExternalOwner(
                transition
        ) ? plan
                .map(SqliteOwnerPopulationParticipant::new)
                .orElse(null) : null;
        SqliteManagedAdmissionParticipant managed =
                transition.admissionEvidence() != null
                && transition.admissionEvidence().status()
                == LifecycleAdmissionEvidence.Status.MANAGED
                        ? SqliteManagedAdmissionParticipant.from(
                        operationId, transition.admissionEvidence()
                ) : null;
        PreparedOperationDetail detail = PreparedOperationDetail.compose(
                new ExactSourceDetail(transition),
                population == null
                        ? PreparedOperationDetail.none()
                        : population,
                managed == null
                        ? PreparedOperationDetail.none()
                        : managed
        );
        DurableOperationWork work =
                (transaction, operation) -> commit(
                        transaction,
                        operation,
                        transition
                );
        if (population != null) {
            work = population.decorate(work);
        }
        if (managed != null) {
            work = managed.decorate(work);
        }
        return coordinator.execute(
                OwnerPopulationTransitionDefinition.INSTANCE,
                request(operationId, idempotencyKey, transition, scopes),
                detail,
                work,
                requiredConsumers
        );
    }

    private SqliteDatabaseOperationCoordinator.Submission rejected(
            String code
    ) {
        return new SqliteDatabaseOperationCoordinator.Submission(
                SqliteSingleWriter.WriteAcceptance.ACCEPTED,
                java.util.concurrent.CompletableFuture.completedFuture(
                        SqliteOperationResults.failed(
                                OperationWorkflowResult.Status.PREPARE_FAILED,
                                null,
                                List.of(),
                                new IllegalArgumentException(code)
                        )
                )
        );
    }

    private boolean positiveTarget(OwnerPopulationTransitionRequest request) {
        return request.targetOwnerId() != null;
    }

    private boolean needsExternalOwner(
            OwnerPopulationTransitionRequest request
    ) {
        return request.admissionEvidence() == null
                || request.admissionEvidence().status()
                != LifecycleAdmissionEvidence.Status.MANAGED
                || request.admissionEvidence().composition() == null
                || request.admissionEvidence().composition().ownerPlan() == null;
    }

    private OperationRequest<OwnerPopulationTransitionRequest> request(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            OwnerPopulationTransitionRequest transition,
            List<OperationScope> scopes
    ) {
        return new OperationRequest<>(
                operationId,
                idempotencyKey,
                transition,
                FEATURE_SCOPE,
                transition.expectedLifecycleRevision(),
                scopes,
                transition.requestedAtMs()
        );
    }

    private List<OperationScope> participants(
            OwnerPopulationTransitionRequest transition
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.profile(transition.profileId()));
        // Terminal cleanup only decreases capacity for this profile. Its exact
        // lifecycle and pending-domain checks still protect uncertain profiles.
        if (positiveTarget(transition)) {
            addOwner(scopes, transition.expectedOwnerId());
            addOwner(scopes, transition.targetOwnerId());
        }
        return List.copyOf(scopes);
    }

    private void addOwner(TreeSet<OperationScope> scopes, OwnerId ownerId) {
        if (ownerId != null) {
            scopes.add(OperationScope.owner(ownerId));
        }
    }

    private List<ProjectionEventDraft> commit(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            OwnerPopulationTransitionRequest request
    ) {
        CompanionLifecycle source = requireExactSource(transaction, request);
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        request.profileId()
                );
        boolean terminalRelease = request.targetOwnerId() == null;
        CompanionLifecycle committed = new CompanionLifecycle(
                source.profileId(),
                request.targetOwnerId(),
                terminalRelease ? LifecycleState.RELEASED : source.state(),
                terminalRelease ? LifecycleLocation.none() : source.location(),
                source.revision().next(),
                null,
                request.requestedAtMs(),
                source.lastReconciledGeneration(),
                source.quarantineIncidentId(),
                request.targetOwnerWorldKey()
        );
        if (terminalRelease) {
            releaseDurableClaims(transaction, operation, source);
        }
        requireApplied(
                transaction.lifecycles().transition(new LifecycleTransition(
                        source.revision(),
                        null,
                        committed
                )),
                "owner_population_lifecycle"
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        request.profileId()
                );
        return events(operation, request, source, committed, before, after);
    }

    private void releaseDurableClaims(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionLifecycle source
    ) {
        PopulationDomainPort.ProfileEvidence evidence = transaction
                .populationDomains().profileEvidence(
                        source.profileId(), operation.operationId()
                );
        if (!evidence.currentOperationPending().isEmpty()
                || !evidence.foreignPending().isEmpty()) {
            throw new IllegalStateException(
                    "owner_population_domain_claim_pending"
            );
        }
        if (!evidence.committed().isEmpty()) {
            PopulationDomainConvergencePlan plan =
                    PopulationDomainConvergencePlanner.plan(
                            source.profileId(),
                            source.revision(),
                            source.ownerId(),
                            source.ownerWorldKey(),
                            source.state(),
                            null,
                            null,
                            LifecycleState.RELEASED,
                            evidence.committed()
                    );
            if (!transaction.populationDomains().convergeExact(plan)) {
                throw new IllegalStateException(
                        "owner_population_domain_release_failed"
                );
            }
        }
        requireApplied(
                transaction.toolLinks().replace(source.profileId(), List.of()),
                "owner_population_tool_links"
        );
    }

    private List<ProjectionEventDraft> events(
            OperationEnvelope operation,
            OwnerPopulationTransitionRequest request,
            CompanionLifecycle source,
            CompanionLifecycle committed,
            CompanionProfileProjectionState before,
            CompanionProfileProjectionState after
    ) {
        OwnerPopulationTransitionOutcome outcome =
                new OwnerPopulationTransitionOutcome(
                        request.profileId(),
                        source.revision(),
                        committed.revision(),
                        committed.ownerId(),
                        committed.ownerWorldKey(),
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
                        "owner-population:" + request.profileId(),
                        committed.revision().value(),
                        OwnerPopulationTransitionEventCodec.VERSION,
                        OwnerPopulationTransitionEventCodec.encode(outcome),
                        request.requestedAtMs()
                ),
                SqliteCompanionProfileProjectionComposer.event(
                        operation.operationId(),
                        profileChange
                ),
                CompanionLifecycleProjectionChangeCodec.draft(
                        operation.operationId(),
                        source,
                        committed,
                        request.requestedAtMs()
                )
        );
    }

    private static CompanionLifecycle requireExactSource(
            SqlitePersistenceTransactionContext transaction,
            OwnerPopulationTransitionRequest request
    ) {
        CompanionLifecycle source = transaction.lifecycles()
                .findByProfile(request.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "owner_population_profile_lifecycle_missing"
                ));
        // Membership updates need not change lifecycle revision. Check inside the
        // transaction so a concurrent roster enrollment cannot survive terminal release.
        if (request.targetOwnerId() == null
                && transaction.commandRosters().findByProfile(request.profileId()).isPresent()) {
            throw new IllegalStateException("owner_population_release_managed_roster");
        }
        if (!source.revision().equals(request.expectedLifecycleRevision())
                || !java.util.Objects.equals(
                source.ownerId(),
                request.expectedOwnerId()
        )
                || !java.util.Objects.equals(
                source.ownerWorldKey(),
                request.expectedOwnerWorldKey()
        )
                || source.activeOperationId() != null
                || source.quarantined()) {
            throw new IllegalStateException(
                    "owner_population_source_mismatch"
            );
        }
        return source;
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

    /** Validation participant without a separate lifecycle or recovery state. */
    private static final class ExactSourceDetail
            implements PreparedOperationDetail {
        private final OwnerPopulationTransitionRequest request;

        private ExactSourceDetail(
                OwnerPopulationTransitionRequest request
        ) {
            this.request = request;
        }

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            if (!matches(transaction, operation)) {
                throw new IllegalStateException(
                        "owner_population_source_mismatch"
                );
            }
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
                requireExactSource(transaction, request);
                return operation.phase() == OperationPhase.PREPARED
                        || operation.phase() == OperationPhase.RETRYABLE;
            } catch (IllegalStateException invalid) {
                return false;
            }
        }
    }
}

