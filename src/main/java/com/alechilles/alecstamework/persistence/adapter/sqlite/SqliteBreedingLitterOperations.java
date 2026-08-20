package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.npc.actions.BreedingLitterLiveBoundary;
import com.alechilles.alecstamework.npc.actions.BreedingLitterLiveResult;
import com.alechilles.alecstamework.npc.actions.BreedingLitterOperation;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationBoundary;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable discovery and replay wrapper for one admitted breeding litter. */
public final class SqliteBreedingLitterOperations {
    public static final String FEATURE_SCOPE = "population_domains";
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("breeding_litter_settled");

    private final SqliteOperationEngine operations;
    private final SqliteLiveOperationCoordinator workflow;
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteBreedingLitterOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || clock == null
                || requiredConsumers == null) {
            throw new IllegalArgumentException(
                    "Breeding litter operation dependencies are required"
            );
        }
        this.operations = operations;
        workflow = new SqliteLiveOperationCoordinator(
                operations, publisher, clock
        );
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Persists one pending litter before released pairing effects begin. */
    @Nonnull
    public SqliteUnitOfWorkRunner.Submission<OperationEnvelope> prepare(
            @Nonnull BreedingLitterOperation litter
    ) {
        if (litter == null) {
            throw new IllegalArgumentException(
                    "Breeding litter operation is required"
            );
        }
        return operations.prepare(
                BreedingLitterOperation.DEFINITION,
                request(litter),
                PreparedOperationDetail.none()
        );
    }

    /** Starts or resumes one exact durable litter job. */
    @Nonnull
    public Submission submit(
            @Nonnull BreedingLitterOperation litter,
            @Nonnull BreedingLitterLiveBoundary liveBoundary
    ) {
        if (litter == null || liveBoundary == null) {
            throw new IllegalArgumentException(
                    "Complete breeding litter operation is required"
            );
        }
        LitterLiveAdapter live = new LitterLiveAdapter(liveBoundary);
        SqliteLiveOperationCoordinator.Submission submitted = workflow.execute(
                BreedingLitterOperation.DEFINITION,
                request(litter),
                PreparedOperationDetail.none(),
                live,
                (transaction, operation, payload, committedAtMs) ->
                        events(operation, payload, live.latest(), committedAtMs),
                requiredConsumers,
                "breeding_litter"
        );
        return new Submission(
                submitted.acceptance(), submitted.completion()
        );
    }

    private static OperationRequest<BreedingLitterOperation> request(
            BreedingLitterOperation litter
    ) {
        return new OperationRequest<>(
                new OperationId(
                        BreedingLitterOperation.jobOperationId(
                                litter.litterId()
                        )
                ),
                new IdempotencyKey(
                        "breeding-litter:" + litter.litterId()
                ),
                litter,
                FEATURE_SCOPE,
                null,
                List.of(),
                litter.requestedAtMs()
        );
    }

    private static List<ProjectionEventDraft> events(
            OperationEnvelope operation,
            BreedingLitterOperation litter,
            BreedingLitterLiveResult live,
            long committedAtMs
    ) {
        if (live == null
                || live.status() != BreedingLitterLiveResult.Status.CONFIRMED) {
            throw new IllegalStateException(
                    "breeding_litter_confirmed_receipts_missing"
            );
        }
        Map<Integer, UUID> receipts = live.receipts();
        String payload = litter.encodeReceipts(receipts);
        return List.of(new ProjectionEventDraft(
                operation.operationId(),
                EVENT_TYPE,
                litter.litterId().toString(),
                1L,
                1,
                payload,
                committedAtMs
        ));
    }

    private static final class LitterLiveAdapter
            implements LiveOperationBoundary<BreedingLitterOperation> {
        private final BreedingLitterLiveBoundary delegated;
        private volatile BreedingLitterLiveResult latest;

        private LitterLiveAdapter(BreedingLitterLiveBoundary delegated) {
            this.delegated = delegated;
        }

        @Override
        public CompletionStage<LiveOperationResult> applyOrResolve(
                BreedingLitterOperation litter,
                OperationEnvelope operation
        ) {
            CompletionStage<BreedingLitterLiveResult> resolution;
            try {
                resolution = delegated.reconcileAndSpawn(litter, operation);
                if (resolution == null) {
                    throw new IllegalStateException(
                            "breeding_litter_boundary_returned_null"
                    );
                }
            } catch (Throwable failure) {
                resolution = BreedingLitterLiveResult.retryable(
                        "breeding_litter_boundary_failed", failure
                ).completed();
            }
            return resolution.handle((result, failure) ->
                    failure == null && result != null
                            ? result
                            : BreedingLitterLiveResult.retryable(
                                    "breeding_litter_boundary_failed",
                                    failure
                            )
            ).thenApply(result -> {
                latest = result;
                return result.sharedResult();
            });
        }

        @Nullable
        private BreedingLitterLiveResult latest() {
            return latest;
        }
    }

    /** Writer admission plus eventual shared workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException(
                        "Breeding litter submission is incomplete"
                );
            }
        }
    }
}
