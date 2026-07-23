package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionAliasLiveBoundary;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotation;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationEventCodec;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationOutcome;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.DurableCommitEvidence;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * One pre-lease/live-confirm/promote operation for runtime NPC alias rotation.
 *
 * <p>The live callback runs only after the preparation transaction completes. A restart from
 * {@code LIVE_APPLYING} invokes the same idempotent resolver, which must return unknown rather than
 * infer live absence from incomplete evidence.</p>
 */
public final class SqliteCompanionAliasRotationOperations {
    public static final String FEATURE_SCOPE = "companion_alias";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_alias_rotated");

    private final SqliteOperationEngine operations;
    private final SqliteOperationPublisher publisher;
    private final LongSupplier clock;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionAliasRotationOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || clock == null) {
            throw new IllegalArgumentException("Alias rotation dependencies are required");
        }
        this.operations = operations;
        this.publisher = publisher;
        this.clock = clock;
        this.requiredConsumers = publisher.validateConsumers(requiredConsumers);
    }

    /** Starts or resumes one exact fenced alias rotation. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionAliasRotation rotation,
            @Nonnull CompanionAliasLiveBoundary liveBoundary
    ) {
        if (operationId == null || idempotencyKey == null
                || rotation == null || liveBoundary == null) {
            throw new IllegalArgumentException("Complete alias rotation request is required");
        }
        OperationRequest<CompanionAliasRotation> request = new OperationRequest<>(
                operationId,
                idempotencyKey,
                rotation,
                FEATURE_SCOPE,
                null,
                List.of(OperationScope.profile(rotation.profileId())),
                rotation.requestedAtMs()
        );
        SqliteUnitOfWorkRunner.Submission<OperationEnvelope> prepared =
                operations.prepare(
                        CompanionAliasRotationDefinition.INSTANCE,
                        request,
                        new AliasLeaseDetail(rotation)
                );
        CompletionStage<OperationWorkflowResult> completion =
                prepared.completion().thenCompose(result -> {
                    if (!(result instanceof PersistenceTransactionResult.Committed<?> committed)
                            || !(committed.value() instanceof OperationEnvelope operation)) {
                        return SqliteOperationResults.completed(
                                SqliteOperationResults.failed(
                                        OperationWorkflowResult.Status.PREPARE_FAILED,
                                        null,
                                        List.of(),
                                        SqliteOperationResults.transactionFailure(
                                                result,
                                                "alias_rotation_prepare_failed"
                                        )
                                )
                        );
                    }
                    return continueFrom(operation, rotation, liveBoundary);
                });
        return new Submission(prepared.acceptance(), completion);
    }

    private CompletionStage<OperationWorkflowResult> continueFrom(
            OperationEnvelope operation,
            CompanionAliasRotation rotation,
            CompanionAliasLiveBoundary liveBoundary
    ) {
        return switch (operation.phase()) {
            case PUBLISHED, DURABLE ->
                    publisher.resume(operation, requiredConsumers);
            case PREPARED, RETRYABLE ->
                    transitionToLive(operation, rotation, liveBoundary);
            case LIVE_APPLYING ->
                    applyOrResolveLive(operation, rotation, liveBoundary);
            case UNKNOWN -> SqliteOperationResults.completed(
                    SqliteOperationResults.failed(
                            OperationWorkflowResult.Status.LIVE_UNKNOWN,
                            operation,
                            List.of(),
                            failure(operation, "alias_live_outcome_unknown")
                    )
            );
            default -> SqliteOperationResults.completed(
                    SqliteOperationResults.failed(
                            OperationWorkflowResult.Status.INVALID_PHASE,
                            operation,
                            List.of(),
                            new IllegalStateException(
                                    "alias_rotation_phase_"
                                            + operation.phase().name().toLowerCase()
                            )
                    )
            );
        };
    }

    private CompletionStage<OperationWorkflowResult> transitionToLive(
            OperationEnvelope operation,
            CompanionAliasRotation rotation,
            CompanionAliasLiveBoundary liveBoundary
    ) {
        return operations.transition(
                operation,
                OperationPhase.LIVE_APPLYING,
                null,
                null,
                clock.getAsLong()
        ).completion().thenCompose(result -> {
            if (result instanceof PersistenceTransactionResult.Committed<?> committed
                    && committed.value() instanceof OperationEnvelope applying) {
                return applyOrResolveLive(applying, rotation, liveBoundary);
            }
            return SqliteOperationResults.completed(
                    SqliteOperationResults.failed(
                            OperationWorkflowResult.Status.TRANSITION_FAILED,
                            operation,
                            List.of(),
                            SqliteOperationResults.transactionFailure(
                                    result,
                                    "alias_live_transition_failed"
                            )
                    )
            );
        });
    }

    private CompletionStage<OperationWorkflowResult> applyOrResolveLive(
            OperationEnvelope operation,
            CompanionAliasRotation rotation,
            CompanionAliasLiveBoundary liveBoundary
    ) {
        CompanionAliasLiveBoundary.Result result;
        try {
            result = liveBoundary.applyOrResolve(rotation, operation);
            if (result == null) {
                throw new IllegalStateException("alias_live_boundary_returned_null");
            }
        } catch (Throwable failure) {
            result = CompanionAliasLiveBoundary.Result.retryable(
                    "alias_live_boundary_failed",
                    failure
            );
        }
        return switch (result.status()) {
            case CONFIRMED -> commitPromotion(operation, rotation);
            case RETRYABLE -> transitionLiveFailure(
                    operation,
                    OperationPhase.RETRYABLE,
                    OperationWorkflowResult.Status.LIVE_RETRYABLE,
                    result
            );
            case UNKNOWN -> transitionLiveFailure(
                    operation,
                    OperationPhase.UNKNOWN,
                    OperationWorkflowResult.Status.LIVE_UNKNOWN,
                    result
            );
        };
    }

    private CompletionStage<OperationWorkflowResult> transitionLiveFailure(
            OperationEnvelope operation,
            OperationPhase phase,
            OperationWorkflowResult.Status status,
            CompanionAliasLiveBoundary.Result live
    ) {
        return operations.transition(
                operation,
                phase,
                "live",
                live.code(),
                clock.getAsLong()
        ).completion().thenApply(result -> {
            if (result instanceof PersistenceTransactionResult.Committed<?> committed
                    && committed.value() instanceof OperationEnvelope transitioned) {
                return SqliteOperationResults.failed(
                        status,
                        transitioned,
                        List.of(),
                        live.cause() == null
                                ? new IllegalStateException(live.code())
                                : live.cause()
                );
            }
            return SqliteOperationResults.failed(
                    OperationWorkflowResult.Status.TRANSITION_FAILED,
                    operation,
                    List.of(),
                    SqliteOperationResults.transactionFailure(
                            result,
                            "alias_live_failure_transition_failed"
                    )
            );
        });
    }

    private CompletionStage<OperationWorkflowResult> commitPromotion(
            OperationEnvelope operation,
            CompanionAliasRotation rotation
    ) {
        long promotedAtMs = clock.getAsLong();
        return operations.commitDurable(
                operation,
                (transaction, current) -> {
                    PersistenceMutationResult<CompanionAlias> promoted =
                            transaction.identities().promoteAlias(
                                    rotation.targetAlias(),
                                    current.operationId(),
                                    promotedAtMs
                            );
                    if (!promoted.applied()) {
                        throw new IllegalStateException(
                                "alias_promote_"
                                        + promoted.status().name().toLowerCase()
                        );
                    }
                    CompanionAlias alias = promoted.value();
                    CompanionAliasRotationOutcome outcome =
                            new CompanionAliasRotationOutcome(
                                    alias.profileId(),
                                    alias.alias(),
                                    alias.generation(),
                                    promotedAtMs
                            );
                    return List.of(new ProjectionEventDraft(
                            current.operationId(),
                            EVENT_TYPE,
                            alias.profileId().toString(),
                            alias.generation(),
                            CompanionAliasRotationEventCodec.VERSION,
                            CompanionAliasRotationEventCodec.encode(outcome),
                            promotedAtMs
                    ));
                },
                promotedAtMs
        ).completion().thenCompose(result -> {
            if (result instanceof PersistenceTransactionResult.Committed<?> committed
                    && committed.value() instanceof DurableCommitEvidence durable) {
                return publisher.publish(durable, requiredConsumers);
            }
            return SqliteOperationResults.completed(
                    SqliteOperationResults.failed(
                            OperationWorkflowResult.Status.DURABLE_COMMIT_FAILED,
                            operation,
                            List.of(),
                            SqliteOperationResults.transactionFailure(
                                    result,
                                    "alias_promotion_commit_failed"
                            )
                    )
            );
        });
    }

    private Throwable failure(OperationEnvelope operation, String fallback) {
        return operation.failureCode() == null
                ? new IllegalStateException(fallback)
                : new IllegalStateException(operation.failureCode());
    }

    /** Preparation detail that proves the target alias remains owned by this operation fence. */
    private static final class AliasLeaseDetail implements PreparedOperationDetail {
        private final CompanionAliasRotation rotation;

        private AliasLeaseDetail(CompanionAliasRotation rotation) {
            this.rotation = rotation;
        }

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            PersistenceMutationResult<CompanionAlias> lease =
                    transaction.identities().leaseAlias(
                            rotation.profileId(),
                            rotation.targetAlias(),
                            operation.operationId(),
                            rotation.requestedAtMs()
                    );
            if (!lease.applied()) {
                throw new IllegalStateException(
                        "alias_lease_" + lease.status().name().toLowerCase()
                );
            }
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            CompanionAlias alias = transaction.identities()
                    .resolveAlias(rotation.targetAlias())
                    .orElse(null);
            return alias != null
                    && alias.profileId().equals(rotation.profileId())
                    && operation.operationId().equals(alias.leaseOperationId())
                    && alias.state() != CompanionAlias.State.RETIRED;
        }
    }

    /** Writer admission for atomic preparation plus the eventual exact workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException("Alias rotation submission is incomplete");
            }
        }
    }
}
