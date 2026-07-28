package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotation;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationEventCodec;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationOutcome;
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
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Fenced alias rotation through one database-only operation.
 *
 * <p>Alias observation has no external side effect. Leasing, retiring the
 * previous current alias, promotion, and projection events therefore commit in
 * one writer transaction without a fabricated live-confirmation phase.</p>
 */
public final class SqliteCompanionAliasRotationOperations {
    public static final String FEATURE_SCOPE = "companion_alias";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_alias_rotated");

    private final SqliteDatabaseOperationCoordinator coordinator;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionAliasRotationOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (coordinator == null || requiredConsumers == null) {
            throw new IllegalArgumentException("Alias rotation dependencies are required");
        }
        this.coordinator = coordinator;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact database-local alias rotation. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionAliasRotation rotation
    ) {
        if (operationId == null || idempotencyKey == null
                || rotation == null) {
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
        SqliteDatabaseOperationCoordinator.Submission submission =
                coordinator.execute(
                        CompanionAliasRotationDefinition.INSTANCE,
                        request,
                        new AliasRotationPrecondition(rotation),
                        (transaction, operation) -> rotate(
                                transaction,
                                operation,
                                rotation
                        ),
                        requiredConsumers
                );
        return new Submission(submission.acceptance(), submission.completion());
    }

    private List<ProjectionEventDraft> rotate(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope current,
            CompanionAliasRotation rotation
    ) {
        long promotedAtMs = rotation.requestedAtMs();
        CompanionProfileProjectionState before =
                SqliteCompanionProfileProjectionComposer.compose(
                        transaction,
                        rotation.profileId()
                );
        requireApplied(
                transaction.identities().leaseAlias(
                        rotation.profileId(),
                        rotation.targetAlias(),
                        current.operationId(),
                        promotedAtMs
                ),
                "alias_lease"
        );
        CompanionAlias alias = requireApplied(
                transaction.identities().promoteAlias(
                        rotation.targetAlias(),
                        current.operationId(),
                        promotedAtMs
                ),
                "alias_promote"
        );
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

    /** Read-only preparation check; alias rows are written only by the durable commit. */
    private static final class AliasRotationPrecondition
            implements PreparedOperationDetail {
        private final CompanionAliasRotation rotation;

        private AliasRotationPrecondition(
                CompanionAliasRotation rotation
        ) {
            this.rotation = rotation;
        }

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            if (!matches(transaction, operation)) {
                throw new IllegalStateException(
                        "alias_rotation_precondition_failed"
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
            return transaction.identities()
                    .findProfile(rotation.profileId()).isPresent()
                    && transaction.identities()
                    .resolveAlias(rotation.targetAlias()).isEmpty();
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
