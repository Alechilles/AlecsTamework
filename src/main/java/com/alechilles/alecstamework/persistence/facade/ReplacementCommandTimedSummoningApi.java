package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.CommandTimedSummoningApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningChangedEvent;
import com.alechilles.alecstamework.api.CommandTimedSummoningRequest;
import com.alechilles.alecstamework.api.CommandTimedSummoningResult;
import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import com.alechilles.alecstamework.api.CommandTimedSummoningView;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonProjectionView;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PublicOperationEvidence;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Timed command API over the canonical lease projection and shared live
 * transition operation.
 *
 * <p>Subscriptions are fed by the checkpointed public event projection, not
 * by the caller's workflow-completion future, so recovery convergence follows
 * the same post-commit path.</p>
 */
public final class ReplacementCommandTimedSummoningApi
        implements CommandTimedSummoningApi {
    private final PublicPersistenceQueries queries;
    private final PublicPersistenceOperations operations;
    private final TransitionAuthor author;
    private final LongSupplier clock;
    private final CopyOnWriteArrayList<
            Consumer<CommandTimedSummoningChangedEvent>> listeners =
            new CopyOnWriteArrayList<>();

    public ReplacementCommandTimedSummoningApi(
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull PublicPersistenceOperations operations,
            @Nonnull TransitionAuthor author,
            @Nonnull LongSupplier clock
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.author = Objects.requireNonNull(author, "author");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Nonnull
    public Optional<CommandTimedSummoningView> get(
            @Nonnull CommandTimedSummoningRequest identity
    ) {
        Objects.requireNonNull(identity, "identity");
        ProfileId profile = profile(identity.profileId());
        if (profile == null) {
            return Optional.empty();
        }
        TimedSummonProjectionView projected =
                queries.projectedTimedSummons().get(profile);
        if (projected == null
                || !projected.membership().familyKey().ownerId().value()
                .equals(identity.ownerUuid())
                || !projected.membership().familyKey().familyId()
                .equals(identity.commandFamilyId())) {
            return Optional.empty();
        }
        return Optional.of(view(projected, clock.getAsLong()));
    }

    @Override
    @Nonnull
    public CompletionStage<CommandTimedSummoningResult> summon(
            @Nonnull CommandTimedSummoningRequest request
    ) {
        return transition(
                Objects.requireNonNull(request, "request"),
                TimedSummonTransitionRequest.Action.START
        );
    }

    @Override
    @Nonnull
    public CompletionStage<CommandTimedSummoningResult> dismiss(
            @Nonnull CommandTimedSummoningRequest request
    ) {
        return transition(
                Objects.requireNonNull(request, "request"),
                TimedSummonTransitionRequest.Action.STORE
        );
    }

    @Override
    @Nonnull
    public AutoCloseable subscribe(
            @Nonnull Consumer<CommandTimedSummoningChangedEvent> listener
    ) {
        Consumer<CommandTimedSummoningChangedEvent> required =
                Objects.requireNonNull(listener, "listener");
        listeners.add(required);
        return () -> listeners.remove(required);
    }

    /**
     * Receives one mapped, checkpointed outbox event. Listener failures are
     * isolated from projection acknowledgement.
     */
    public void publishFromOutbox(
            @Nonnull CommandTimedSummoningChangedEvent event
    ) {
        Objects.requireNonNull(event, "event");
        for (Consumer<CommandTimedSummoningChangedEvent> listener
                : java.util.List.copyOf(listeners)) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // A downstream listener cannot stall canonical publication.
            }
        }
    }

    private CompletionStage<CommandTimedSummoningResult> transition(
            CommandTimedSummoningRequest request,
            TimedSummonTransitionRequest.Action action
    ) {
        CompletionStage<PreparedTransition> prepared;
        try {
            prepared = author.prepare(request, action);
        } catch (RuntimeException failure) {
            return unavailable(failureCode(failure));
        }
        if (prepared == null) {
            return unavailable("timed-summon-author-returned-null");
        }
        return prepared.thenCompose(candidate -> submit(candidate, request))
                .exceptionally(failure -> result(
                        CommandTimedSummoningResult.Status.UNAVAILABLE,
                        failureCode(failure),
                        null
                ));
    }

    private CompletionStage<CommandTimedSummoningResult> submit(
            PreparedTransition prepared,
            CommandTimedSummoningRequest request
    ) {
        if (prepared == null) {
            return unavailable("timed-summon-request-unresolvable");
        }
        return queries.findOperation(
                TimedSummonTransitionDefinition.KIND,
                prepared.idempotencyKey()
        ).thenCompose(existing -> {
            if (existing instanceof PersistenceReadResult.Failed<?>) {
                return unavailable("timed-summon-operation-read-failed");
            }
            PreparedTransition submitted = prepared;
            if (existing instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found) {
                TimedSummonTransitionRequest durable =
                        TimedSummonTransitionDefinition.INSTANCE.decode(
                                found.value().operation().payloadJson()
                        );
                if (!durable.equals(prepared.request())) {
                    return denied("timed-summon-idempotency-conflict");
                }
                submitted = new PreparedTransition(
                        found.value().operation().operationId(),
                        prepared.idempotencyKey(),
                        durable,
                        true
                );
            }
            return execute(submitted, request);
        });
    }

    private CompletionStage<CommandTimedSummoningResult> execute(
            PreparedTransition prepared,
            CommandTimedSummoningRequest request
    ) {
        PublicOperationSubmission submission;
        try {
            submission = operations.transitionTimedSummon(
                    prepared.operationId(),
                    prepared.idempotencyKey(),
                    prepared.request()
            );
        } catch (RuntimeException rejected) {
            return unavailable(failureCode(rejected));
        }
        if (!submission.accepted()) {
            return unavailable(
                    "timed-summon-submission-"
                            + submission.admission().name()
                            .toLowerCase(java.util.Locale.ROOT)
            );
        }
        return submission.completion().thenApply(workflow ->
                completed(prepared, request, workflow));
    }

    private CommandTimedSummoningResult completed(
            PreparedTransition prepared,
            CommandTimedSummoningRequest request,
            OperationWorkflowResult workflow
    ) {
        Optional<CommandTimedSummoningView> current = get(request);
        if (workflow.status() == OperationWorkflowResult.Status.PUBLISHED) {
            return result(
                    prepared.idempotentReplay()
                            ? CommandTimedSummoningResult.Status.IDEMPOTENT
                            : CommandTimedSummoningResult.Status.SUCCESS,
                    prepared.idempotentReplay()
                            ? "timed-summon-idempotent"
                            : "timed-summon-published",
                    current.orElse(null)
            );
        }
        String reason = workflowReason(workflow);
        CommandTimedSummoningResult.Status status =
                reason.contains("cooldown")
                        ? CommandTimedSummoningResult.Status.COOLDOWN
                        : recovering(workflow.status())
                        ? CommandTimedSummoningResult.Status.RECOVERING
                        : CommandTimedSummoningResult.Status.DENIED;
        return result(status, reason, current.orElse(null));
    }

    @Nonnull
    public static CommandTimedSummoningView view(
            @Nonnull TimedSummonProjectionView projected,
            long nowMs
    ) {
        Objects.requireNonNull(projected, "projected");
        var lease = projected.lease();
        Long remaining = remaining(lease, nowMs);
        return new CommandTimedSummoningView(
                projected.membership().familyKey().ownerId().value(),
                projected.membership().familyKey().familyId(),
                lease.profileId().toString(),
                lease.leaseRevision(),
                state(projected.lifecycle().state()),
                lease.sessionId() == null
                        ? null
                        : lease.sessionId().toString(),
                remaining,
                lease.activeSession() && lease.policy().unlimited(),
                lease.cooldownUntilMs() == null
                        ? 0L
                        : lease.cooldownUntilMs(),
                lease.updatedAtMs()
        );
    }

    private static Long remaining(
            com.alechilles.alecstamework.companion.command.timed
                    .TimedSummonLease lease,
            long nowMs
    ) {
        if (!lease.activeSession()) {
            return null;
        }
        if (lease.remainingMs() == null) {
            return null;
        }
        long elapsed;
        try {
            elapsed = Math.max(
                    0L,
                    Math.subtractExact(nowMs, lease.checkpointedAtMs())
            );
        } catch (ArithmeticException overflow) {
            elapsed = nowMs >= lease.checkpointedAtMs()
                    ? Long.MAX_VALUE
                    : 0L;
        }
        return Math.max(0L, lease.remainingMs() - Math.min(
                lease.remainingMs(), elapsed
        ));
    }

    private static CommandTimedSummoningState state(
            LifecycleState state
    ) {
        return switch (state) {
            case ACTIVE -> CommandTimedSummoningState.ACTIVE;
            case UNLOADED, CAPTURED, COOP, RELEASED ->
                    CommandTimedSummoningState.UNLOADED;
            case UNRESOLVED -> CommandTimedSummoningState.UNAVAILABLE;
            case ROSTER_STORED, PROVISIONED_DORMANT ->
                    CommandTimedSummoningState.ROSTER_STORED;
            case DEAD_REVIVABLE ->
                    CommandTimedSummoningState.DEAD_REVIVABLE;
            case LOST -> CommandTimedSummoningState.LOST;
        };
    }

    private boolean recovering(OperationWorkflowResult.Status status) {
        return switch (status) {
            case LIVE_RETRYABLE, LIVE_UNKNOWN, PUBLICATION_PENDING,
                    COMPENSATION_REQUIRED, COMPENSATION_RETRYABLE,
                    COMPENSATION_UNKNOWN, DURABLE_READ_FAILED,
                    DURABLE_COMMIT_FAILED -> true;
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

    private CompletionStage<CommandTimedSummoningResult> unavailable(
            String reason
    ) {
        return CompletableFuture.completedFuture(result(
                CommandTimedSummoningResult.Status.UNAVAILABLE,
                reason,
                null
        ));
    }

    private CompletionStage<CommandTimedSummoningResult> denied(
            String reason
    ) {
        return CompletableFuture.completedFuture(result(
                CommandTimedSummoningResult.Status.DENIED,
                reason,
                null
        ));
    }

    private CommandTimedSummoningResult result(
            CommandTimedSummoningResult.Status status,
            String reason,
            CommandTimedSummoningView view
    ) {
        return new CommandTimedSummoningResult(status, reason, view);
    }

    private ProfileId profile(String value) {
        try {
            return ProfileId.parse(value);
        } catch (RuntimeException failure) {
            return null;
        }
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

    /** Builds exact immutable transition evidence without applying it. */
    @FunctionalInterface
    public interface TransitionAuthor {
        @Nonnull
        CompletionStage<PreparedTransition> prepare(
                @Nonnull CommandTimedSummoningRequest request,
                @Nonnull TimedSummonTransitionRequest.Action action
        );
    }

    /** Complete input for one shared timed transition. */
    public record PreparedTransition(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull TimedSummonTransitionRequest request,
            boolean idempotentReplay
    ) {
        public PreparedTransition {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(request, "request");
        }
    }

}
