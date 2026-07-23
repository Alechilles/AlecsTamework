package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionAliasLiveBoundary;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotation;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationEventCodec;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationOutcome;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionChange;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
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
 * Fenced alias-rotation use case built on the shared live-operation workflow.
 *
 * <p>This adapter owns only alias preparation and promotion rules. Phase transitions, retry,
 * unknown containment, durable publication, and resume behavior are shared with other live
 * operations.</p>
 */
public final class SqliteCompanionAliasRotationOperations {
    public static final String FEATURE_SCOPE = "companion_alias";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_alias_rotated");

    private final SqliteLiveOperationCoordinator workflow;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionAliasRotationOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || clock == null
                || requiredConsumers == null) {
            throw new IllegalArgumentException("Alias rotation dependencies are required");
        }
        workflow = new SqliteLiveOperationCoordinator(operations, publisher, clock);
        this.requiredConsumers = List.copyOf(requiredConsumers);
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
        SqliteLiveOperationCoordinator.Submission submission = workflow.execute(
                CompanionAliasRotationDefinition.INSTANCE,
                request,
                new AliasLeaseDetail(rotation),
                (payload, operation) -> liveResult(
                        liveBoundary.applyOrResolve(payload, operation)
                ),
                this::promote,
                requiredConsumers,
                "alias_rotation"
        );
        return new Submission(submission.acceptance(), submission.completion());
    }

    private List<ProjectionEventDraft> promote(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope current,
            CompanionAliasRotation rotation,
            long promotedAtMs
    ) {
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        rotation.profileId()
                );
        PersistenceMutationResult<CompanionAlias> promoted =
                transaction.identities().promoteAlias(
                        rotation.targetAlias(),
                        current.operationId(),
                        promotedAtMs
                );
        if (!promoted.applied()) {
            throw new IllegalStateException(
                    "alias_promote_" + promoted.status().name().toLowerCase()
            );
        }
        CompanionAlias alias = promoted.value();
        CompanionAliasRotationOutcome outcome = new CompanionAliasRotationOutcome(
                alias.profileId(),
                alias.alias(),
                alias.generation(),
                promotedAtMs
        );
        CompanionProfileProjectionState after =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        rotation.profileId()
                );
        CompanionProfileProjectionChange change =
                new CompanionProfileProjectionChange(
                        CompanionProfileProjectionChange.Source.ALIAS,
                        alias.profileId(),
                        alias.generation(),
                        before,
                        after,
                        promotedAtMs
                );
        return List.of(
                new ProjectionEventDraft(
                        current.operationId(),
                        EVENT_TYPE,
                        "alias-rotation-result:" + alias.profileId(),
                        alias.generation(),
                        CompanionAliasRotationEventCodec.VERSION,
                        CompanionAliasRotationEventCodec.encode(outcome),
                        promotedAtMs
                ),
                SqliteCompanionProfileProjectionComposer.event(
                        current.operationId(),
                        change
                )
        );
    }

    private LiveOperationResult liveResult(CompanionAliasLiveBoundary.Result result) {
        if (result == null) {
            throw new IllegalStateException("alias_live_boundary_returned_null");
        }
        return switch (result.status()) {
            case CONFIRMED -> LiveOperationResult.confirmed(result.code());
            case RETRYABLE -> LiveOperationResult.retryable(
                    result.code(),
                    result.cause()
            );
            case UNKNOWN -> LiveOperationResult.unknown(
                    result.code(),
                    result.cause()
            );
        };
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
