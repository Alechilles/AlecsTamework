package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionDefinition;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionEventCodec;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionOutcome;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionRequest;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * One database-only lifecycle transition shared by death and authoritative lost events.
 *
 * <p>The immutable snapshot and canonical lifecycle row are the whole durable state. There is no
 * death record, lost record, or feature-specific state machine.</p>
 */
public final class SqliteCompanionDormantOperations {
    public static final String FEATURE_SCOPE = "companion_dormant_transition";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_dormant");

    private final SqliteDatabaseOperationCoordinator workflow;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionDormantOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationEvidenceReader evidence,
            @Nonnull ProjectionCoordinator projections,
            @Nonnull LongSupplier clock,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || evidence == null || projections == null
                || clock == null || requiredConsumers == null) {
            throw new IllegalArgumentException("Companion dormant dependencies are required");
        }
        workflow = new SqliteDatabaseOperationCoordinator(
                operations, evidence, projections, clock
        );
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact positive-evidence death or lost transition. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionDormantTransitionRequest dormant
    ) {
        if (operationId == null || idempotencyKey == null || dormant == null) {
            throw new IllegalArgumentException("Complete companion dormant request is required");
        }
        OperationRequest<CompanionDormantTransitionRequest> request =
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        dormant,
                        FEATURE_SCOPE,
                        dormant.expectedLifecycleRevision(),
                        List.of(OperationScope.profile(dormant.profileId())),
                        dormant.requestedAtMs()
                );
        SqliteDatabaseOperationCoordinator.Submission submission =
                workflow.execute(
                        CompanionDormantTransitionDefinition.INSTANCE,
                        request,
                        new DormantPreparationDetail(dormant),
                        (transaction, operation) ->
                                commitDormant(transaction, operation, dormant),
                        requiredConsumers
                );
        return new Submission(submission.acceptance(), submission.completion());
    }

    private List<ProjectionEventDraft> commitDormant(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionDormantTransitionRequest dormant
    ) {
        CompanionLifecycle current = requireExactLive(
                transaction, dormant
        );
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, dormant.profileId()
                );
        requireApplied(
                transaction.snapshots().replaceCurrent(dormant.snapshot()),
                "dormant_snapshot"
        );
        requireApplied(
                transaction.identities().retireAlias(
                        dormant.source().sourceAlias(),
                        dormant.source().observedAtMs()
                ),
                "dormant_alias_retirement"
        );
        CompanionLifecycle transitioned = new CompanionLifecycle(
                dormant.profileId(),
                current.ownerId(),
                dormant.targetState(),
                LifecycleLocation.none(),
                current.revision().next(),
                null,
                dormant.source().observedAtMs(),
                latest(
                        current.lastReconciledGeneration(),
                        dormant.source().observedGeneration()
                ),
                current.quarantineIncidentId()
        );
        requireApplied(
                transaction.lifecycles().transition(new LifecycleTransition(
                        current.revision(), null, transitioned
                )),
                "dormant_lifecycle"
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, dormant.profileId()
                );
        CompanionDormantTransitionOutcome outcome =
                new CompanionDormantTransitionOutcome(
                        dormant.profileId(),
                        transitioned.state(),
                        dormant.snapshot().snapshotId(),
                        transitioned.revision(),
                        dormant.source().receiptKey(),
                        dormant.source().observedAtMs()
                );
        CompanionProfileProjectionChange change =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.LIFECYCLE,
                        dormant.profileId(),
                        transitioned.revision().value(),
                        before,
                        after,
                        dormant.source().observedAtMs()
                );
        return List.of(
                new ProjectionEventDraft(
                        operation.operationId(),
                        EVENT_TYPE,
                        "dormant-result:" + dormant.profileId(),
                        transitioned.revision().value(),
                        CompanionDormantTransitionEventCodec.VERSION,
                        CompanionDormantTransitionEventCodec.encode(outcome),
                        dormant.source().observedAtMs()
                ),
                SqliteCompanionProfileProjectionComposer.event(
                        operation.operationId(), change
                )
        );
    }

    private static CompanionLifecycle requireExactLive(
            SqlitePersistenceTransactionContext transaction,
            CompanionDormantTransitionRequest dormant
    ) {
        CompanionLifecycle current = transaction.lifecycles()
                .findByProfile(dormant.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "dormant_profile_lifecycle_missing"
                ));
        CompanionAlias alias = transaction.identities()
                .resolveAlias(dormant.source().sourceAlias())
                .orElse(null);
        boolean exactLocation = current.state() == LifecycleState.ACTIVE
                && current.location().equals(LifecycleLocation.liveEntity(
                dormant.source().sourceAlias().toString(),
                dormant.source().sourceWorldKey()
        ));
        if (!current.revision().equals(
                dormant.expectedLifecycleRevision()
        ) || current.activeOperationId() != null || current.quarantined()
                || !exactLocation || alias == null
                || !alias.profileId().equals(dormant.profileId())
                || alias.state() != CompanionAlias.State.CURRENT
                || dormant.source().observedGeneration().compareTo(
                current.lastReconciledGeneration()
        ) < 0) {
            throw new IllegalStateException(
                    "dormant_transition_not_exact_positive_live_profile"
            );
        }
        return current;
    }

    private static boolean matchesCompleted(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionDormantTransitionRequest dormant
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(dormant.profileId())
                .orElse(null);
        CompanionAlias alias = transaction.identities()
                .resolveAlias(dormant.source().sourceAlias())
                .orElse(null);
        return (operation.phase() == OperationPhase.DURABLE
                || operation.phase() == OperationPhase.PUBLISHED)
                && lifecycle != null
                && lifecycle.revision().equals(
                dormant.expectedLifecycleRevision().next()
        )
                && lifecycle.state() == dormant.targetState()
                && lifecycle.location().equals(LifecycleLocation.none())
                && lifecycle.activeOperationId() == null
                && alias != null
                && alias.state() == CompanionAlias.State.RETIRED
                && transaction.snapshots()
                .findById(dormant.snapshot().snapshotId())
                .filter(dormant.snapshot()::equals)
                .isPresent();
    }

    private static ReconciliationGeneration latest(
            ReconciliationGeneration left,
            ReconciliationGeneration right
    ) {
        return left.compareTo(right) >= 0 ? left : right;
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

    /** Validates the exact source inside the same transaction that creates the operation. */
    private static final class DormantPreparationDetail
            implements PreparedOperationDetail {
        private final CompanionDormantTransitionRequest dormant;

        private DormantPreparationDetail(
                CompanionDormantTransitionRequest dormant
        ) {
            this.dormant = dormant;
        }

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            // Validation-only detail: the database-only commit needs no intermediate fence.
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            if (matchesCompleted(transaction, operation, dormant)) {
                return true;
            }
            try {
                requireExactLive(transaction, dormant);
                return operation.phase() == OperationPhase.PREPARED;
            } catch (IllegalStateException invalid) {
                return false;
            }
        }
    }

    /** Writer admission for atomic validation plus the eventual exact workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException("Dormant submission is incomplete");
            }
        }
    }
}
