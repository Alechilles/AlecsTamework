package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChange;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChangeCodec;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseMutationDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseMutationRequest;
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
import java.util.List;
import javax.annotation.Nonnull;

/** Database-only registration, policy refresh, and checkpoint operations. */
public final class SqliteTimedSummonLeaseOperations {
    public static final String FEATURE_SCOPE = "timed_summon";

    private final SqliteDatabaseOperationCoordinator coordinator;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteTimedSummonLeaseOperations(
            @Nonnull SqliteDatabaseOperationCoordinator coordinator,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (coordinator == null || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Timed lease operation dependencies are required"
            );
        }
        this.coordinator = coordinator;
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    @Nonnull
    public SqliteDatabaseOperationCoordinator.Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull TimedSummonLeaseMutationRequest mutation
    ) {
        if (operationId == null || idempotencyKey == null
                || mutation == null) {
            throw new IllegalArgumentException(
                    "Complete timed lease mutation is required"
            );
        }
        return coordinator.execute(
                TimedSummonLeaseMutationDefinition.INSTANCE,
                new OperationRequest<>(
                        operationId,
                        idempotencyKey,
                        mutation,
                        FEATURE_SCOPE,
                        mutation.lifecycle().revision(),
                        List.of(OperationScope.profile(
                                mutation.after().profileId()
                        )),
                        mutation.requestedAtMs()
                ),
                new ExactLeaseDetail(mutation),
                (transaction, operation) ->
                        commit(transaction, operation, mutation),
                requiredConsumers
        );
    }

    private List<ProjectionEventDraft> commit(
            SqlitePersistenceTransactionContext transaction,
            OperationEnvelope operation,
            TimedSummonLeaseMutationRequest mutation
    ) {
        requireExact(transaction, mutation);
        PersistenceMutationResult<TimedSummonLeaseChange> result =
                transaction.timedSummons().replace(
                        mutation.before() == null
                                ? null
                                : mutation.before().leaseRevision(),
                        mutation.after()
                );
        if (!result.applied()) {
            throw new IllegalStateException(
                    "timed_lease_mutation_"
                            + result.status().name().toLowerCase()
            );
        }
        return List.of(TimedSummonLeaseChangeCodec.draft(
                operation.operationId(),
                result.value(),
                mutation.requestedAtMs()
        ));
    }

    private static void requireExact(
            SqlitePersistenceTransactionContext transaction,
            TimedSummonLeaseMutationRequest mutation
    ) {
        if (!transaction.lifecycles()
                .findByProfile(mutation.after().profileId())
                .filter(mutation.lifecycle()::equals)
                .isPresent()
                || !transaction.timedSummons()
                .find(mutation.after().profileId())
                .equals(java.util.Optional.ofNullable(
                        mutation.before()
                ))) {
            throw new IllegalStateException(
                    "timed_lease_mutation_source_mismatch"
            );
        }
    }

    /** Exact mutation evidence with completed-state replay acceptance. */
    private static final class ExactLeaseDetail
            implements PreparedOperationDetail {
        private final TimedSummonLeaseMutationRequest mutation;

        private ExactLeaseDetail(
                TimedSummonLeaseMutationRequest mutation
        ) {
            this.mutation = mutation;
        }

        @Override
        public void prepare(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            requireExact(transaction, mutation);
        }

        @Override
        public boolean matches(
                SqlitePersistenceTransactionContext transaction,
                OperationEnvelope operation
        ) {
            if (operation.phase() == OperationPhase.DURABLE
                    || operation.phase() == OperationPhase.PUBLISHED) {
                return transaction.timedSummons()
                        .find(mutation.after().profileId())
                        .filter(mutation.after()::equals)
                        .isPresent();
            }
            try {
                requireExact(transaction, mutation);
                return operation.phase() == OperationPhase.PREPARED
                        || operation.phase() == OperationPhase.RETRYABLE;
            } catch (IllegalStateException invalid) {
                return false;
            }
        }
    }
}
