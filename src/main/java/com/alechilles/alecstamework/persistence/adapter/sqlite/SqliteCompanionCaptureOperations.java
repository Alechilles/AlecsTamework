package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureLiveBoundary;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * One lifecycle-fenced live capture through the shared operation protocol.
 *
 * <p>The operation payload is durable source evidence and the capture snapshot ID is the
 * canonical capture-artifact claim. No independent capture attempt or lifecycle table exists.</p>
 */
public final class SqliteCompanionCaptureOperations {
    public static final String FEATURE_SCOPE = "companion_capture";
    public static final ProjectionEventType EVENT_TYPE =
            SqliteCompanionCaptureCommit.CAPTURED_EVENT_TYPE;

    private final SqliteLiveOperationCoordinator workflow;
    private final SqliteCaptureCompensation compensation;
    private final SqliteCompanionCaptureCommit commit =
            new SqliteCompanionCaptureCommit();
    private final List<ProjectionConsumer> requiredConsumers;

    public SqliteCompanionCaptureOperations(
            @Nonnull SqliteOperationEngine operations,
            @Nonnull SqliteOperationPublisher publisher,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds,
            @Nonnull List<? extends ProjectionConsumer> requiredConsumers
    ) {
        if (operations == null || publisher == null || clock == null
                || refunds == null || requiredConsumers == null) {
            throw new IllegalArgumentException("Companion capture dependencies are required");
        }
        workflow = new SqliteLiveOperationCoordinator(operations, publisher, clock);
        compensation = new SqliteCaptureCompensation(
                operations,
                clock,
                refunds
        );
        this.requiredConsumers = List.copyOf(requiredConsumers);
    }

    /** Starts or resumes one exact source-correlated capture. */
    @Nonnull
    public Submission submit(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCaptureRequest capture,
            @Nonnull CompanionCaptureLiveBoundary liveBoundary
    ) {
        if (operationId == null || idempotencyKey == null
                || capture == null || liveBoundary == null) {
            throw new IllegalArgumentException("Complete companion capture is required");
        }
        OperationRequest<CompanionCaptureRequest> request = new OperationRequest<>(
                operationId,
                idempotencyKey,
                capture,
                FEATURE_SCOPE,
                capture.expectedLifecycleRevision(),
                List.of(
                        OperationScope.profile(capture.profileId()),
                        OperationScope.owner(OwnerId.parse(
                                capture.source().actorUuid().toString()
                        ))
                ),
                capture.requestedAtMs()
        );
        SqliteLiveOperationCoordinator.Submission submission = workflow.execute(
                CompanionCaptureDefinition.INSTANCE,
                request,
                new SqliteCompanionCapturePreparation(capture),
                liveBoundary,
                commit::commit,
                requiredConsumers,
                "companion_capture"
        );
        CompletionStage<OperationWorkflowResult> completion =
                submission.completion().thenCompose(result -> {
                    OperationEnvelope operation = result.operation();
                    if (operation != null
                            && (result.status()
                            == OperationWorkflowResult.Status.COMPENSATION_REQUIRED
                            || operation.phase() == OperationPhase.COMPENSATING
                            || operation.phase() == OperationPhase.COMPENSATED)) {
                        return compensation.resume(operation, capture);
                    }
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            result
                    );
                });
        return new Submission(submission.acceptance(), completion);
    }

    /** Writer admission for atomic preparation plus the eventual exact workflow result. */
    public record Submission(
            @Nonnull SqliteSingleWriter.WriteAcceptance acceptance,
            @Nonnull CompletionStage<OperationWorkflowResult> completion
    ) {
        public Submission {
            if (acceptance == null || completion == null) {
                throw new IllegalArgumentException("Companion capture submission is incomplete");
            }
        }
    }
}
