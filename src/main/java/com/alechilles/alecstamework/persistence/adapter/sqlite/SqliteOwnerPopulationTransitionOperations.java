package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlanner;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionEventCodec;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionOutcome;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
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
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import javax.annotation.Nonnull;

/**
 * Existing-profile ownership transitions through the shared database operation protocol.
 */
public final class SqliteOwnerPopulationTransitionOperations {
    public static final String FEATURE_SCOPE = "owner_population";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("owner_population_transition_committed");

    private final SqliteDatabaseOperationCoordinator coordinator;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteOwnerPopulationTransitionOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (coordinator == null || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Population transition dependencies are required"
            );
        }
        this.coordinator = coordinator;
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
        Optional<OwnerPopulationAdmissionPlan> plan =
                OwnerPopulationAdmissionPlanner.plan(transition);
        SqliteOwnerPopulationParticipant population = plan
                .map(SqliteOwnerPopulationParticipant::new)
                .orElse(null);
        PreparedOperationDetail detail = PreparedOperationDetail.compose(
                new ExactSourceDetail(transition),
                population == null
                        ? PreparedOperationDetail.none()
                        : population
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
        return coordinator.execute(
                OwnerPopulationTransitionDefinition.INSTANCE,
                request(operationId, idempotencyKey, transition),
                detail,
                work,
                requiredConsumers
        );
    }

    private OperationRequest<OwnerPopulationTransitionRequest> request(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            OwnerPopulationTransitionRequest transition
    ) {
        return new OperationRequest<>(
                operationId,
                idempotencyKey,
                transition,
                FEATURE_SCOPE,
                transition.expectedLifecycleRevision(),
                participants(transition),
                transition.requestedAtMs()
        );
    }

    private List<OperationScope> participants(
            OwnerPopulationTransitionRequest transition
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.profile(transition.profileId()));
        addOwner(scopes, transition.expectedOwnerId());
        addOwner(scopes, transition.targetOwnerId());
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
        CompanionLifecycle committed = new CompanionLifecycle(
                source.profileId(),
                request.targetOwnerId(),
                source.state(),
                source.location(),
                source.revision().next(),
                null,
                request.requestedAtMs(),
                source.lastReconciledGeneration(),
                source.quarantineIncidentId(),
                request.targetOwnerWorldKey()
        );
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

