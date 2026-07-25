package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import com.alechilles.alecstamework.api.PaidCommandRevivalApi;
import com.alechilles.alecstamework.api.PaidCommandRevivalOperationView;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuoteRequest;
import com.alechilles.alecstamework.api.PaidCommandRevivalRequest;
import com.alechilles.alecstamework.api.PaidCommandRevivalResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalEventCodec;
import com.alechilles.alecstamework.companion.revival.PaidRevivalOutcome;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.runtime.PublicOperationEvidence;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Paid revival facade that accepts only live-author-frozen payment and spawn
 * evidence before entering the replacement operation protocol.
 */
public final class ReplacementPaidCommandRevivalApi
        implements PaidCommandRevivalApi {
    private final PublicPersistenceQueries queries;
    private final PublicPersistenceOperations operations;
    private final RequestAuthor author;

    public ReplacementPaidCommandRevivalApi(
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull PublicPersistenceOperations operations,
            @Nonnull RequestAuthor author
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.author = Objects.requireNonNull(author, "author");
    }

    @Override
    @Nonnull
    public CompletionStage<PaidCommandRevivalQuote> quote(
            @Nonnull PaidCommandRevivalQuoteRequest request
    ) {
        Objects.requireNonNull(request, "request");
        try {
            CompletionStage<PaidCommandRevivalQuote> quoted =
                    author.quote(request);
            return quoted == null
                    ? CompletableFuture.completedFuture(unavailableQuote(
                    request, "paid-revival-quote-author-returned-null"
            ))
                    : quoted.exceptionally(failure -> unavailableQuote(
                    request, failureCode(failure)
            ));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(unavailableQuote(
                    request, failureCode(failure)
            ));
        }
    }

    @Override
    @Nonnull
    public CompletionStage<PaidCommandRevivalResult> revive(
            @Nonnull PaidCommandRevivalRequest request
    ) {
        Objects.requireNonNull(request, "request");
        CompletionStage<PreparedRevival> stage;
        try {
            stage = author.prepare(request);
        } catch (RuntimeException failure) {
            return unavailable(request.profileId(), failureCode(failure));
        }
        if (stage == null) {
            return unavailable(
                    request.profileId(),
                    "paid-revival-author-returned-null"
            );
        }
        return stage.thenCompose(prepared -> submit(prepared, request))
                .exceptionally(failure -> PaidCommandRevivalResult.unavailable(
                        request.profileId(), failureCode(failure)
                ));
    }

    @Override
    @Nonnull
    public CompletionStage<Optional<PaidCommandRevivalOperationView>>
    findOperation(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey
    ) {
        Objects.requireNonNull(callerNamespace, "callerNamespace");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        IdempotencyKey operationKey;
        try {
            operationKey = author.operationKey(
                    callerNamespace, idempotencyKey
            );
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (operationKey == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return queries.findOperation(
                PaidRevivalDefinition.KIND, operationKey
        ).thenApply(read -> {
            if (!(read instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found)) {
                return Optional.empty();
            }
            return Optional.of(operationView(found.value().operation()));
        });
    }

    private CompletionStage<PaidCommandRevivalResult> submit(
            PreparedRevival prepared,
            PaidCommandRevivalRequest publicRequest
    ) {
        if (prepared == null || !matches(prepared.request(), publicRequest)) {
            return unavailable(
                    publicRequest.profileId(),
                    "paid-revival-frozen-request-mismatch"
            );
        }
        return queries.findOperation(
                PaidRevivalDefinition.KIND,
                prepared.idempotencyKey()
        ).thenCompose(existing -> {
            if (existing instanceof PersistenceReadResult.Failed<?>) {
                return unavailable(
                        publicRequest.profileId(),
                        "paid-revival-operation-read-failed"
                );
            }
            PreparedRevival submitted = prepared;
            if (existing instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found) {
                var durable = PaidRevivalDefinition.INSTANCE.decode(
                        found.value().operation().payloadJson()
                );
                if (!durable.equals(prepared.request())) {
                    return unavailable(
                            publicRequest.profileId(),
                            "paid-revival-idempotency-conflict"
                    );
                }
                submitted = new PreparedRevival(
                        found.value().operation().operationId(),
                        prepared.idempotencyKey(),
                        durable,
                        true
                );
            }
            return execute(submitted, publicRequest);
        });
    }

    private CompletionStage<PaidCommandRevivalResult> execute(
            PreparedRevival prepared,
            PaidCommandRevivalRequest publicRequest
    ) {
        PublicOperationSubmission submission;
        try {
            submission = operations.reviveCompanion(
                    prepared.operationId(),
                    prepared.idempotencyKey(),
                    prepared.request()
            );
        } catch (RuntimeException rejected) {
            return unavailable(
                    publicRequest.profileId(), failureCode(rejected)
            );
        }
        if (!submission.accepted()) {
            return unavailable(
                    publicRequest.profileId(),
                    "paid-revival-submission-rejected"
            );
        }
        return submission.completion().thenApply(workflow ->
                result(prepared, publicRequest, workflow));
    }

    private PaidCommandRevivalResult result(
            PreparedRevival prepared,
            PaidCommandRevivalRequest request,
            OperationWorkflowResult workflow
    ) {
        PaidRevivalOutcome outcome = outcome(workflow.events());
        List<ItemCostComponentView> cost = outcome == null
                ? costs(prepared.request().exactCost())
                : costs(outcome.exactCost());
        if (workflow.status() == OperationWorkflowResult.Status.PUBLISHED) {
            return new PaidCommandRevivalResult(
                    prepared.operationId().value(),
                    prepared.idempotentReplay()
                            ? PaidCommandRevivalResult.Status.ALREADY_REVIVED
                            : PaidCommandRevivalResult.Status.REVIVED,
                    request.profileId(),
                    cost,
                    null,
                    prepared.idempotentReplay()
            );
        }
        String reason = workflowReason(workflow);
        return new PaidCommandRevivalResult(
                prepared.operationId().value(),
                status(workflow.status(), reason),
                request.profileId(),
                cost,
                reason,
                recovering(workflow.status())
        );
    }

    private PaidCommandRevivalOperationView operationView(
            OperationEnvelope operation
    ) {
        var request = PaidRevivalDefinition.INSTANCE.decode(
                operation.payloadJson()
        );
        return new PaidCommandRevivalOperationView(
                operation.operationId().value(),
                request.callerNamespace(),
                request.callerIdempotencyKey(),
                request.familyKey().ownerId().value(),
                request.groupAdmission().before().profileId().toString(),
                operationState(operation.phase()),
                costs(request.exactCost()),
                operation.failureCode(),
                operation.updatedAtMs()
        );
    }

    private PaidCommandRevivalResult.Status status(
            OperationWorkflowResult.Status status,
            String reason
    ) {
        if (reason.contains("insufficient")) {
            return PaidCommandRevivalResult.Status.INSUFFICIENT_COST;
        }
        if (reason.contains("cooldown")) {
            return PaidCommandRevivalResult.Status.COOLDOWN;
        }
        if (reason.contains("conflict")) {
            return PaidCommandRevivalResult.Status.CONFLICT;
        }
        return switch (status) {
            case COMPENSATED -> PaidCommandRevivalResult.Status.REFUNDED;
            case COMPENSATION_REQUIRED, COMPENSATION_PREPARE_FAILED,
                    COMPENSATION_RETRYABLE, COMPENSATION_UNKNOWN,
                    COMPENSATION_COMMIT_FAILED ->
                    PaidCommandRevivalResult.Status.REFUND_PENDING;
            case LIVE_RETRYABLE, LIVE_UNKNOWN, PUBLICATION_PENDING,
                    DURABLE_READ_FAILED, DURABLE_COMMIT_FAILED ->
                    PaidCommandRevivalResult.Status.RECOVERY_PENDING;
            default -> PaidCommandRevivalResult.Status.DENIED;
        };
    }

    private PaidCommandRevivalOperationView.State operationState(
            OperationPhase phase
    ) {
        return switch (phase) {
            case PREPARED -> PaidCommandRevivalOperationView.State.PREPARED;
            case LIVE_APPLYING ->
                    PaidCommandRevivalOperationView.State.APPLYING;
            case DURABLE, PUBLISHED ->
                    PaidCommandRevivalOperationView.State.SUCCEEDED;
            case COMPENSATING ->
                    PaidCommandRevivalOperationView.State.REFUND_REQUIRED;
            case COMPENSATED ->
                    PaidCommandRevivalOperationView.State.REFUNDED;
            case RETRYABLE, UNKNOWN ->
                    PaidCommandRevivalOperationView.State.QUARANTINED;
            case FAILED -> PaidCommandRevivalOperationView.State.CANCELED;
        };
    }

    private PaidRevivalOutcome outcome(List<ProjectionEvent> events) {
        for (ProjectionEvent event : events) {
            if (PaidRevivalEventCodec.EVENT_TYPE.equals(event.eventType())) {
                return PaidRevivalEventCodec.decode(
                        event.payloadVersion(), event.payloadJson()
                );
            }
        }
        return null;
    }

    private boolean matches(
            com.alechilles.alecstamework.companion.revival
                    .PaidRevivalRequest frozen,
            PaidCommandRevivalRequest request
    ) {
        return frozen.callerNamespace().equals(request.callerNamespace())
                && frozen.callerIdempotencyKey()
                .equals(request.idempotencyKey())
                && frozen.familyKey().ownerId().value()
                .equals(request.ownerUuid())
                && frozen.familyKey().familyId()
                .equals(request.commandFamilyId())
                && frozen.groupAdmission().before().profileId().toString()
                .equals(request.profileId());
    }

    private List<ItemCostComponentView> costs(
            List<RevivalCostItem> costs
    ) {
        return costs.stream()
                .map(cost -> new ItemCostComponentView(
                        cost.itemId(), cost.quantity()
                ))
                .toList();
    }

    private boolean recovering(OperationWorkflowResult.Status status) {
        return switch (status) {
            case LIVE_RETRYABLE, LIVE_UNKNOWN, PUBLICATION_PENDING,
                    DURABLE_READ_FAILED, DURABLE_COMMIT_FAILED,
                    COMPENSATION_REQUIRED, COMPENSATION_PREPARE_FAILED,
                    COMPENSATION_RETRYABLE, COMPENSATION_UNKNOWN,
                    COMPENSATION_COMMIT_FAILED -> true;
            default -> false;
        };
    }

    private String workflowReason(OperationWorkflowResult workflow) {
        return workflow.operation() != null
                && workflow.operation().failureCode() != null
                ? workflow.operation().failureCode()
                : workflow.status().name()
                .toLowerCase(java.util.Locale.ROOT);
    }

    private CompletionStage<PaidCommandRevivalResult> unavailable(
            String profileId,
            String reason
    ) {
        return CompletableFuture.completedFuture(
                PaidCommandRevivalResult.unavailable(profileId, reason)
        );
    }

    private PaidCommandRevivalQuote unavailableQuote(
            PaidCommandRevivalQuoteRequest request,
            String reason
    ) {
        return new PaidCommandRevivalQuote(
                request.ownerUuid(),
                request.profileId(),
                request.commandFamilyId(),
                PaidCommandRevivalQuote.Status.UNAVAILABLE,
                0L,
                List.of(),
                "unavailable",
                null,
                reason
        );
    }

    private String failureCode(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent
                .CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message.trim();
    }

    /**
     * Hytale-owned live preparation boundary. It must freeze config, snapshots,
     * placement, exact reservations, receipt keys, and stack fingerprints on
     * the authoritative world/inventory thread before returning.
     */
    public interface RequestAuthor {
        @Nonnull
        CompletionStage<PaidCommandRevivalQuote> quote(
                @Nonnull PaidCommandRevivalQuoteRequest request
        );

        @Nonnull
        CompletionStage<PreparedRevival> prepare(
                @Nonnull PaidCommandRevivalRequest request
        );

        @Nonnull
        IdempotencyKey operationKey(
                @Nonnull String callerNamespace,
                @Nonnull String idempotencyKey
        );
    }

    /** Fully frozen request ready for the shared operation protocol. */
    public record PreparedRevival(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull com.alechilles.alecstamework.companion.revival
                    .PaidRevivalRequest request,
            boolean idempotentReplay
    ) {
        public PreparedRevival {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(request, "request");
        }
    }
}
