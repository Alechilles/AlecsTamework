package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveBoundary;
import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalReleaseBoundary;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.operation
        .DurableOperationCleanupBoundary;
import com.alechilles.alecstamework.persistence.operation.DurableOperationWork;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One exact charge-and-spawn revival through the shared live operation protocol. */
public final class SqlitePaidRevivalOperations {
    public static final String FEATURE_SCOPE = "paid_revival";

    private final SqliteLiveOperationCoordinator workflow;
    private final SqlitePaidRevivalCompensation compensation;
    private final SqliteOperationEngine operations;
    private final LongSupplier clock;
    @Nullable
    private final SqliteManagedPaidRevivalAdmission admission;
    private final SqliteLifecycleAdmissionSingleFlight singleFlight =
            new SqliteLifecycleAdmissionSingleFlight();
    private final List<ProjectionConsumer> requiredConsumers;

    public SqlitePaidRevivalOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull SqliteReadExecutor reads,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        this(
                operations,
                publisher,
                reads,
                clock,
                refunds,
                null,
                null,
                null,
                requiredConsumers
        );
    }

    SqlitePaidRevivalOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull SqliteReadExecutor reads,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds,
            @Nullable SqliteOperationReader operationReader,
            @Nullable SqliteLifecycleAdmissionBinding lifecycleAdmission,
            @Nullable SqliteLifecycleAdmissionSourceReader sourceReader,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || reads == null
                || clock == null || refunds == null
                || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Paid revival operation dependencies are required"
            );
        }
        workflow = new SqliteLiveOperationCoordinator(
                operations, publisher, clock
        );
        compensation = new SqlitePaidRevivalCompensation(
                operations, reads, clock, refunds
        );
        this.operations = operations;
        this.clock = clock;
        admission = operationReader == null || lifecycleAdmission == null
                || sourceReader == null
                ? null
                : new SqliteManagedPaidRevivalAdmission(
                        operationReader, lifecycleAdmission, sourceReader
                );
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one immutable quote, source plan, and target. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull PaidRevivalRequest request,
            @Nonnull PaidRevivalLiveBoundary liveBoundary,
            @Nonnull PaidRevivalReleaseBoundary releaseBoundary,
            @Nonnull DurableOperationCleanupBoundary<
                    PaidRevivalRequest> cleanupBoundary
    ) {
        if (operationId == null || idempotencyKey == null
                || request == null || liveBoundary == null
                || releaseBoundary == null || cleanupBoundary == null) {
            throw new IllegalArgumentException(
                    "Complete paid revival operation is required"
            );
        }
        if (!positiveRevival(request) && request.admissionEvidence() != null) {
            return rejected(
                    "paid_revival_admission_evidence_forbidden"
            );
        }
        if (positiveRevival(request) && admission != null) {
            CompletionStage<OperationWorkflowResult> completion =
                    singleFlight.submit(
                            PaidRevivalDefinition.KIND,
                            operationId,
                            idempotencyKey,
                            PaidRevivalDefinition.INSTANCE.encode(request),
                            () -> admission.resolve(
                                            operationId,
                                            idempotencyKey,
                                            request
                                    )
                                    .thenCompose(value -> execute(
                                            operationId,
                                            idempotencyKey,
                                            value,
                                            liveBoundary,
                                            releaseBoundary,
                                            cleanupBoundary
                                    ).completion())
                    );
            return new Submission(
                    SqliteSingleWriter.WriteAcceptance.ACCEPTED,
                    completion.exceptionally(failure ->
                            SqliteOperationResults.failed(
                                    OperationWorkflowResult.Status.PREPARE_FAILED,
                                    null,
                                    List.of(),
                                    unwrap(failure)
                            ))
            );
        }
        return execute(
                operationId,
                idempotencyKey,
                request,
                liveBoundary,
                releaseBoundary,
                cleanupBoundary
        );
    }

    private Submission execute(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            PaidRevivalRequest request,
            PaidRevivalLiveBoundary liveBoundary,
            PaidRevivalReleaseBoundary releaseBoundary,
            DurableOperationCleanupBoundary<PaidRevivalRequest> cleanupBoundary
    ) {
        SqlitePopulationGroupTransitionParticipant groups =
                needsExternalGroups(request)
                        ? new SqlitePopulationGroupTransitionParticipant(
                        request.groupAdmission()
                ) : null;
        SqliteManagedAdmissionParticipant managed =
                request.admissionEvidence() != null
                        && request.admissionEvidence().status()
                        == LifecycleAdmissionEvidence.Status.MANAGED
                        ? SqliteManagedAdmissionParticipant.from(
                        operationId, request.admissionEvidence()
                ) : null;
        PaidLiveAdapter live = new PaidLiveAdapter(liveBoundary);
        SqlitePaidRevivalCommit commit = new SqlitePaidRevivalCommit();
        PreparedOperationDetail detail = preparationDetail(
                request, groups, managed
        );
        SqliteLiveOperationCoordinator.Submission submitted =
                workflow.execute(
                        PaidRevivalDefinition.INSTANCE,
                        new OperationRequest<>(
                                operationId,
                                idempotencyKey,
                                request,
                                FEATURE_SCOPE,
                                request.groupAdmission()
                                        .before().revision(),
                                participants(request),
                                request.requestedAtMs()
                        ),
                        detail,
                        live,
                        cleanupBoundary,
                        (transaction, operation, payload, committedAtMs) -> {
                            DurableOperationWork durable =
                                    (current, envelope) -> commit.execute(
                                            current,
                                            envelope,
                                            payload,
                                            committedAtMs
                                    );
                            if (managed != null) {
                                durable = managed.decorate(durable);
                            }
                            if (groups != null) {
                                durable = groups.decorate(durable);
                            }
                            return durable.execute(transaction, operation);
                        },
                        requiredConsumers,
                        "paid_revival"
                );
        CompletionStage<OperationWorkflowResult> completion =
                submitted.completion().thenCompose(result ->
                        continueResult(
                                result,
                                request,
                                live.latest(),
                                releaseBoundary,
                                cleanupBoundary
                        ));
        return new Submission(submitted.acceptance(), completion);
    }

    private static PreparedOperationDetail preparationDetail(
            PaidRevivalRequest request,
            @Nullable SqlitePopulationGroupTransitionParticipant groups,
            @Nullable SqliteManagedAdmissionParticipant managed
    ) {
        PreparedOperationDetail exact =
                new SqlitePaidRevivalPreparation(request);
        if (groups == null && managed == null) {
            return exact;
        }
        if (groups == null) {
            return PreparedOperationDetail.compose(managed, exact);
        }
        if (managed == null) {
            return PreparedOperationDetail.compose(groups, exact);
        }
        return PreparedOperationDetail.compose(groups, exact, managed);
    }

    private static boolean positiveRevival(PaidRevivalRequest request) {
        return request.groupAdmission().before().state()
                == LifecycleState.DEAD_REVIVABLE
                && request.groupAdmission().after().state()
                == LifecycleState.ACTIVE;
    }

    private static boolean needsExternalGroups(PaidRevivalRequest request) {
        return request.admissionEvidence() == null
                || request.admissionEvidence().status()
                != LifecycleAdmissionEvidence.Status.MANAGED
                || request.admissionEvidence().composition() == null
                || request.admissionEvidence().composition().groupRequest()
                == null;
    }

    private static Submission rejected(String code) {
        return new Submission(
                SqliteSingleWriter.WriteAcceptance.REJECTED,
                CompletableFuture.completedFuture(
                        SqliteOperationResults.failed(
                                OperationWorkflowResult.Status.PREPARE_FAILED,
                                null,
                                List.of(),
                                new IllegalStateException(code)
                        )
                )
        );
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException
                && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    private CompletionStage<OperationWorkflowResult> continueResult(
            OperationWorkflowResult result,
            PaidRevivalRequest request,
            PaidRevivalLiveResult disposition,
            PaidRevivalReleaseBoundary releases,
            DurableOperationCleanupBoundary<PaidRevivalRequest> cleanup
    ) {
        OperationEnvelope operation = result.operation();
        if (operation != null
                && (result.status()
                == OperationWorkflowResult.Status.COMPENSATION_REQUIRED
                || operation.phase() == OperationPhase.COMPENSATING
                || operation.phase() == OperationPhase.COMPENSATED)) {
            return compensation.resume(
                    operation, request, disposition, releases, cleanup
            );
        }
        if (result.status()
                == OperationWorkflowResult.Status.LIVE_UNKNOWN
                && operation != null) {
            return containUnknown(result, request);
        }
        return CompletableFuture.completedFuture(result);
    }

    private CompletionStage<OperationWorkflowResult> containUnknown(
            OperationWorkflowResult result,
            PaidRevivalRequest request
    ) {
        OperationEnvelope operation = result.operation();
        return operations.containUnknown(
                operation,
                operation.failureCode() == null
                        ? "paid_revival_live_outcome_unknown"
                        : operation.failureCode(),
                "Paid revival could not prove the exact charge and spawn receipts",
                containmentScopes(operation, request),
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
                                        "paid_revival_unknown_"
                                                + "containment_failed",
                                        result.failure()
                                )
                        ));
    }

    private List<OperationScope> participants(
            PaidRevivalRequest request
    ) {
        TreeSet<OperationScope> scopes = new TreeSet<>();
        scopes.add(OperationScope.profile(
                request.sourceSnapshot().profileId()
        ));
        scopes.add(OperationScope.owner(
                request.familyKey().ownerId()
        ));
        scopes.add(OperationScope.commandFamily(request.familyKey()));
        return List.copyOf(scopes);
    }

    private List<OperationScope> containmentScopes(
            OperationEnvelope operation,
            PaidRevivalRequest request
    ) {
        ArrayList<OperationScope> scopes = new ArrayList<>();
        scopes.add(OperationScope.operation(operation.operationId()));
        scopes.addAll(participants(request));
        return List.copyOf(scopes);
    }

    private static final class PaidLiveAdapter
            implements LiveOperationBoundary<PaidRevivalRequest> {
        private final PaidRevivalLiveBoundary delegated;
        private volatile PaidRevivalLiveResult latest;

        private PaidLiveAdapter(PaidRevivalLiveBoundary delegated) {
            this.delegated = delegated;
        }

        @Override
        public CompletionStage<LiveOperationResult> applyOrResolve(
                PaidRevivalRequest request,
                OperationEnvelope operation
        ) {
            CompletionStage<PaidRevivalLiveResult> resolution;
            try {
                resolution = delegated.applyOrResolve(request, operation);
                if (resolution == null) {
                    throw new IllegalStateException(
                            "paid_revival_live_boundary_returned_null"
                    );
                }
            } catch (Throwable failure) {
                resolution = PaidRevivalLiveResult.retryable(
                        "paid_revival_live_boundary_failed", failure
                ).completed();
            }
            return resolution.handle((result, failure) ->
                    failure == null && result != null
                            ? result
                            : PaidRevivalLiveResult.retryable(
                                    "paid_revival_live_boundary_failed",
                                    failure
                            )
            ).thenApply(result -> {
                latest = result;
                return result.sharedResult();
            });
        }

        private PaidRevivalLiveResult latest() {
            return latest;
        }
    }

    /** Writer admission plus eventual exact shared workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException(
                        "Paid revival submission is incomplete"
                );
            }
        }
    }
}
