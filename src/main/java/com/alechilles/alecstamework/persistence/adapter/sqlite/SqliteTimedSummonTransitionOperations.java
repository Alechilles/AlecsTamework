package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChange;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChangeCodec;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLiveBoundary;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleProjectionChangeCodec;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleTransition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.operation.TimedDurableOperationWork;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** One lifecycle-fenced shared live protocol for timed summon and store. */
public final class SqliteTimedSummonTransitionOperations {
    public static final String FEATURE_SCOPE = "timed_summon";

    private final SqliteLiveOperationCoordinator workflow;
    private final SqliteOperationEngine operations;
    private final LongSupplier clock;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteTimedSummonTransitionOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || clock == null
                || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Timed transition dependencies are required"
            );
        }
        this.workflow = new SqliteLiveOperationCoordinator(
                operations, publisher, clock
        );
        this.operations = operations;
        this.clock = clock;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes the exact receipt-correlated transition. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull TimedSummonTransitionRequest transition,
            @Nonnull TimedSummonLiveBoundary liveBoundary
    ) {
        if (operationId == null || idempotencyKey == null
                || transition == null || liveBoundary == null) {
            throw new IllegalArgumentException(
                    "Complete timed summon transition is required"
            );
        }
        SqlitePopulationGroupTransitionParticipant groups =
                transition.starting()
                        ? new SqlitePopulationGroupTransitionParticipant(
                                transition.groupAdmission()
                        )
                        : null;
        PreparedOperationDetail detail = groups == null
                ? new SqliteTimedSummonTransitionPreparation(transition)
                : PreparedOperationDetail.compose(
                        groups,
                        new SqliteTimedSummonTransitionPreparation(
                                transition
                        )
                );
        TimedDurableOperationWork<TimedSummonTransitionRequest> durable =
                groups == null
                        ? this::commit
                        : (transaction, operation, payload, committedAtMs) ->
                        groups.decorate((current, envelope) -> commit(
                                current,
                                envelope,
                                payload,
                                committedAtMs
                        )).execute(transaction, operation);
        SqliteLiveOperationCoordinator.Submission submitted =
                workflow.execute(
                        TimedSummonTransitionDefinition.INSTANCE,
                        new OperationRequest<>(
                                operationId,
                                idempotencyKey,
                                transition,
                                FEATURE_SCOPE,
                                transition.groupAdmission()
                                        .before().revision(),
                                List.of(
                                        OperationScope.profile(
                                                transition.beforeLease()
                                                        .profileId()
                                        ),
                                        OperationScope.owner(
                                                transition.familyKey()
                                                        .ownerId()
                                        ),
                                        OperationScope.commandFamily(
                                                transition.familyKey()
                                        )
                                ),
                                transition.requestedAtMs()
                        ),
                        detail,
                        liveBoundary,
                        durable,
                        requiredConsumers,
                        "timed_summon_transition"
                );
        CompletionStage<OperationWorkflowResult> completion =
                submitted.completion().thenCompose(result ->
                        containUnknown(result, transition));
        return new Submission(submitted.acceptance(), completion);
    }

    private List<ProjectionEventDraft> commit(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            TimedSummonTransitionRequest request,
            long committedAtMs
    ) {
        CompanionLifecycle fenced = requireFenced(
                transaction, operation, request
        );
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, fenced.profileId()
                );
        if (request.starting()) {
            requireApplied(
                    transaction.identities().promoteAlias(
                            request.liveAlias(),
                            operation.operationId(),
                            request.requestedAtMs()
                    ),
                    "timed_summon_alias_promotion"
            );
            requireApplied(
                    transaction.snapshots().retireCurrent(
                            request.snapshot().snapshotId()
                    ),
                    "timed_summon_snapshot_retirement"
            );
        } else {
            requireApplied(
                    transaction.snapshots().replaceCurrent(
                            request.snapshot()
                    ),
                    "timed_summon_snapshot_install"
            );
            requireApplied(
                    transaction.identities().retireAlias(
                            request.liveAlias(),
                            request.requestedAtMs()
                    ),
                    "timed_summon_alias_retirement"
            );
        }
        CompanionLifecycle after = request.finalLifecycle();
        requireApplied(
                transaction.lifecycles().transition(
                        new LifecycleTransition(
                                fenced.revision(),
                                operation.operationId(),
                                after
                        )
                ),
                "timed_summon_lifecycle"
        );
        TimedSummonLeaseChange leaseChange = requireApplied(
                transaction.timedSummons().replace(
                        request.beforeLease().leaseRevision(),
                        request.afterLease()
                ),
                "timed_summon_lease"
        );
        CompanionProfileProjectionState afterProfile =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction, after.profileId()
                );
        CompanionProfileProjectionChange profileChange =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.LIFECYCLE,
                        after.profileId(),
                        after.revision().value(),
                        before,
                        afterProfile,
                        request.requestedAtMs()
                );
        return List.of(
                SqliteCompanionProfileProjectionComposer.event(
                        operation.operationId(), profileChange
                ),
                CompanionLifecycleProjectionChangeCodec.draft(
                        operation.operationId(),
                        fenced,
                        after,
                        request.requestedAtMs()
                ),
                TimedSummonLeaseChangeCodec.draft(
                        operation.operationId(),
                        leaseChange,
                        request.requestedAtMs()
                )
        );
    }

    private CompanionLifecycle requireFenced(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            TimedSummonTransitionRequest request
    ) {
        CompanionLifecycle source = request.groupAdmission().before();
        CompanionLifecycle lifecycle = transaction.lifecycles()
                .findByProfile(source.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "timed_summon_lifecycle_missing"
                ));
        SqliteCommandRosterEvidence.requireExact(
                transaction,
                source.profileId(),
                request.familyKey(),
                request.slotId(),
                request.expectedMembershipRevision()
        );
        if (lifecycle.revision().equals(source.revision().next())
                && lifecycle.state() == source.state()
                && lifecycle.location().equals(source.location())
                && operation.operationId().equals(
                lifecycle.activeOperationId()
        )
                && transaction.timedSummons()
                .find(source.profileId())
                .filter(request.beforeLease()::equals)
                .isPresent()) {
            return lifecycle;
        }
        throw new IllegalStateException(
                "timed_summon_lifecycle_fence_mismatch"
        );
    }

    private CompletionStage<OperationWorkflowResult> containUnknown(
            OperationWorkflowResult result,
            TimedSummonTransitionRequest request
    ) {
        if (result.status() != OperationWorkflowResult.Status.LIVE_UNKNOWN
                || result.operation() == null) {
            return CompletableFuture.completedFuture(result);
        }
        OperationEnvelope operation = result.operation();
        return operations.containUnknown(
                operation,
                operation.failureCode() == null
                        ? "timed_summon_live_outcome_unknown"
                        : operation.failureCode(),
                "Timed summon could not prove the exact world receipt",
                List.of(
                        OperationScope.operation(operation.operationId()),
                        OperationScope.profile(
                                request.beforeLease().profileId()
                        ),
                        OperationScope.owner(
                                request.familyKey().ownerId()
                        ),
                        OperationScope.commandFamily(
                                request.familyKey()
                        )
                ),
                clock.getAsLong()
        ).completion().thenApply(containment ->
                containment instanceof
                        com.alechilles.alecstamework.persistence.kernel
                        .PersistenceTransactionResult.Committed<?>
                        ? result
                        : new OperationWorkflowResult(
                                OperationWorkflowResult.Status.LIVE_UNKNOWN,
                                operation,
                                List.of(),
                                new IllegalStateException(
                                        "timed_summon_unknown_containment_failed",
                                        result.failure()
                                )
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

    /** Writer admission plus eventual shared workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException(
                        "Timed transition submission is incomplete"
                );
            }
        }
    }
}

