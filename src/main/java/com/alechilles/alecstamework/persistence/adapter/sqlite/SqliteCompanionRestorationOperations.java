package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationEventCodec;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationLiveBoundary;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationOutcome;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * One alias-leased, lifecycle-fenced live protocol for death and lost restoration.
 *
 * <p>The shared operation envelope is the only workflow state. The source snapshot remains
 * current until entity insertion is positively confirmed, then alias promotion, snapshot
 * retirement, lifecycle activation, and outbox evidence commit atomically.</p>
 */
public final class SqliteCompanionRestorationOperations {
    public static final String FEATURE_SCOPE = "companion_restoration";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_restored");

    private final SqliteLiveOperationCoordinator workflow;
    private final SqliteOperationEngine operations;
    private final LongSupplier clock;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionRestorationOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || clock == null
                || requiredConsumers == null) {
            throw new IllegalArgumentException("Companion restoration dependencies are required");
        }
        workflow = new SqliteLiveOperationCoordinator(
                operations, publisher, clock
        );
        this.operations = operations;
        this.clock = clock;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact receipt-correlated restoration. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionRestorationRequest restoration,
            @Nonnull CompanionRestorationLiveBoundary liveBoundary
    ) {
        if (operationId == null || idempotencyKey == null
                || restoration == null || liveBoundary == null) {
            throw new IllegalArgumentException("Complete companion restoration is required");
        }
        OperationRequest<CompanionRestorationRequest> request =
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        restoration,
                        FEATURE_SCOPE,
                        restoration.expectedLifecycleRevision(),
                        List.of(OperationScope.profile(
                                restoration.profileId()
                        )),
                        restoration.requestedAtMs()
                );
        SqliteLiveOperationCoordinator.Submission submission =
                workflow.execute(
                        CompanionRestorationDefinition.INSTANCE,
                        request,
                        new SqliteCompanionRestorationPreparation(restoration),
                        restoration.restoresLive()
                                ? liveBoundary
                                : (payload, operation) ->
                                LiveOperationResult.confirmed(
                                        "provisioned_dormant_revived"
                                ).completed(),
                        this::commitRestoration,
                        requiredConsumers,
                        "companion_restoration"
                );
        CompletionStage<OperationWorkflowResult> completion =
                submission.completion().thenCompose(result -> {
                    if (result.status()
                            != OperationWorkflowResult.Status.LIVE_UNKNOWN
                            || result.operation() == null) {
                        return CompletableFuture.completedFuture(result);
                    }
                    OperationEnvelope operation = result.operation();
                    return operations.containUnknown(
                            operation,
                            operation.failureCode() == null
                                    ? "restoration_live_outcome_unknown"
                                    : operation.failureCode(),
                            "Restoration could not prove whether the exact "
                                    + "entity insertion completed",
                            List.of(
                                    OperationScope.operation(
                                            operation.operationId()
                                    ),
                                    OperationScope.profile(
                                            restoration.profileId()
                                    )
                            ),
                            clock.getAsLong()
                    ).completion().thenApply(containment -> {
                        if (containment instanceof
                                com.alechilles.alecstamework.persistence.kernel
                                .PersistenceTransactionResult.Committed<?>) {
                            return result;
                        }
                        return new OperationWorkflowResult(
                                OperationWorkflowResult.Status.LIVE_UNKNOWN,
                                operation,
                                List.of(),
                                new IllegalStateException(
                                        "restoration_unknown_containment_failed",
                                        result.failure()
                                )
                        );
                    });
                });
        return new Submission(submission.acceptance(), completion);
    }

    private List<ProjectionEventDraft> commitRestoration(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionRestorationRequest restoration,
            long restoredAtMs
    ) {
        return restoration.restoresLive()
                ? commitActiveRestoration(
                transaction, operation, restoration, restoredAtMs
        )
                : commitDormantRestoration(
                transaction, operation, restoration, restoredAtMs
        );
    }

    private List<ProjectionEventDraft> commitActiveRestoration(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionRestorationRequest restoration,
            long restoredAtMs
    ) {
        CompanionLifecycle fenced = requireFencedLifecycle(
                transaction, operation, restoration
        );
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, restoration.profileId()
                );
        requireApplied(
                transaction.identities().promoteAlias(
                        restoration.targetAlias(),
                        operation.operationId(),
                        restoredAtMs
                ),
                "restoration_alias_promotion"
        );
        requireApplied(
                transaction.snapshots().retireCurrent(
                        restoration.sourceSnapshot().snapshotId()
                ),
                "restoration_snapshot_retirement"
        );
        CompanionLifecycle active = new CompanionLifecycle(
                restoration.profileId(),
                fenced.ownerId(),
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        restoration.targetAlias().toString(),
                        restoration.targetWorldKey()
                ),
                fenced.revision().next(),
                null,
                restoredAtMs,
                fenced.lastReconciledGeneration(),
                fenced.quarantineIncidentId(),
                fenced.ownerId() == null ? null : restoration.targetWorldKey()
        );
        requireApplied(
                transaction.lifecycles().transition(new LifecycleTransition(
                        fenced.revision(),
                        operation.operationId(),
                        active
                )),
                "restoration_lifecycle"
        );
        return activeEvents(
                transaction,
                operation,
                restoration,
                fenced,
                active,
                before,
                restoredAtMs
        );
    }

    private List<ProjectionEventDraft> activeEvents(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionRestorationRequest restoration,
            CompanionLifecycle fenced,
            CompanionLifecycle active,
            CompanionProfileProjectionState before,
            long restoredAtMs
    ) {
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, restoration.profileId()
                );
        CompanionRestorationOutcome outcome = new CompanionRestorationOutcome(
                restoration.profileId(),
                restoration.sourceSnapshot().snapshotId(),
                restoration.targetAlias(),
                restoration.targetWorldKey(),
                active.revision(),
                restoration.spawnReceiptKey(),
                restoredAtMs
        );
        CompanionProfileProjectionChange change =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.LIFECYCLE,
                        restoration.profileId(),
                        active.revision().value(),
                        before,
                        after,
                        restoredAtMs
                );
        ArrayList<ProjectionEventDraft> events = new ArrayList<>(4);
        events.add(new ProjectionEventDraft(
                operation.operationId(),
                EVENT_TYPE,
                "restoration-result:" + restoration.profileId(),
                active.revision().value(),
                CompanionRestorationEventCodec.VERSION,
                CompanionRestorationEventCodec.encode(outcome),
                restoredAtMs
        ));
        SqliteProvisionedCompanionLifecycleEvents.revival(
                transaction,
                operation.operationId(),
                active,
                restoration.expectedLifecycleRevision(),
                restoration.targetAlias(),
                restoredAtMs
        ).ifPresent(events::add);
        events.add(SqliteCompanionProfileProjectionComposer.event(
                operation.operationId(), change
        ));
        events.add(CompanionLifecycleProjectionChangeCodec.draft(
                operation.operationId(),
                fenced,
                active,
                restoredAtMs
        ));
        return List.copyOf(events);
    }

    private List<ProjectionEventDraft> commitDormantRestoration(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionRestorationRequest restoration,
            long restoredAtMs
    ) {
        CompanionLifecycle dead =
                SqliteCompanionRestorationPreparation
                        .requireRestorableSource(
                                transaction, restoration
                        );
        ProvisioningRecord provisioning =
                SqliteCompanionRestorationPreparation
                        .requireProvisioning(
                                transaction, restoration
                        );
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, restoration.profileId()
                );
        CompanionLifecycle dormant = new CompanionLifecycle(
                restoration.profileId(),
                dead.ownerId(),
                LifecycleState.PROVISIONED_DORMANT,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.PROVISIONING,
                        provisioning.origin().stableKey()
                ),
                dead.revision().next(),
                null,
                restoredAtMs,
                dead.lastReconciledGeneration(),
                dead.quarantineIncidentId(),
                dead.ownerWorldKey()
        );
        requireApplied(
                transaction.lifecycles().transition(
                        new LifecycleTransition(
                                dead.revision(), null, dormant
                        )
                ),
                "restoration_dormant_lifecycle"
        );
        return dormantEvents(
                transaction,
                operation,
                restoration,
                dead,
                dormant,
                before,
                restoredAtMs
        );
    }

    private List<ProjectionEventDraft> dormantEvents(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionRestorationRequest restoration,
            CompanionLifecycle dead,
            CompanionLifecycle dormant,
            CompanionProfileProjectionState before,
            long restoredAtMs
    ) {
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, restoration.profileId()
                );
        CompanionRestorationOutcome outcome =
                new CompanionRestorationOutcome(
                        restoration.profileId(),
                        restoration.sourceSnapshot().snapshotId(),
                        LifecycleState.PROVISIONED_DORMANT,
                        null,
                        null,
                        dormant.revision(),
                        null,
                        restoredAtMs
                );
        CompanionProfileProjectionChange change =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.LIFECYCLE,
                        restoration.profileId(),
                        dormant.revision().value(),
                        before,
                        after,
                        restoredAtMs
                );
        ArrayList<ProjectionEventDraft> events = new ArrayList<>(4);
        events.add(restorationEvent(
                operation, outcome, dormant.revision(), restoredAtMs
        ));
        SqliteProvisionedCompanionLifecycleEvents.revival(
                transaction,
                operation.operationId(),
                dormant,
                restoration.expectedLifecycleRevision(),
                null,
                restoredAtMs
        ).ifPresent(events::add);
        events.add(SqliteCompanionProfileProjectionComposer.event(
                operation.operationId(), change
        ));
        events.add(CompanionLifecycleProjectionChangeCodec.draft(
                operation.operationId(),
                dead,
                dormant,
                restoredAtMs
        ));
        return List.copyOf(events);
    }

    private ProjectionEventDraft restorationEvent(
            OperationEnvelope operation,
            CompanionRestorationOutcome outcome,
            com.alechilles.alecstamework.companion.lifecycle
                    .LifecycleRevision revision,
            long restoredAtMs
    ) {
        return new ProjectionEventDraft(
                operation.operationId(),
                EVENT_TYPE,
                "restoration-result:" + outcome.profileId(),
                revision.value(),
                CompanionRestorationEventCodec.VERSION,
                CompanionRestorationEventCodec.encode(outcome),
                restoredAtMs
        );
    }

    private CompanionLifecycle requireFencedLifecycle(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            CompanionRestorationRequest restoration
    ) {
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(restoration.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "restoration_lifecycle_missing"
                ));
        if (!lifecycle.revision().equals(
                restoration.expectedLifecycleRevision().next()
        ) || lifecycle.state() != restoration.sourceState()
                || !lifecycle.location().equals(LifecycleLocation.none())
                || !operation.operationId().equals(
                lifecycle.activeOperationId()
        ) || lifecycle.quarantined()
                || transaction.snapshots()
                .findById(restoration.sourceSnapshot().snapshotId())
                .filter(restoration.sourceSnapshot()::equals)
                .isEmpty()) {
            throw new IllegalStateException(
                    "restoration_lifecycle_fence_mismatch"
            );
        }
        return lifecycle;
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

    /** Writer admission for atomic preparation plus the eventual exact workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException("Restoration submission is incomplete");
            }
        }
    }
}
