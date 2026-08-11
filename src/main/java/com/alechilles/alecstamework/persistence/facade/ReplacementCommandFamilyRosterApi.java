package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.CommandFamilyRosterApi;
import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipView;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationResult;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationStatus;
import com.alechilles.alecstamework.api.CommandFamilyRosterView;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterActionView;
import com.alechilles.alecstamework.companion.command.CommandRosterHome;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipChangeCodec;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterMutationOutcome;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.runtime.PublicOperationEvidence;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Public command-roster facade over the replacement query projection and the
 * one shared operation protocol.
 */
public final class ReplacementCommandFamilyRosterApi
        implements CommandFamilyRosterApi {
    private final PublicPersistenceQueries queries;
    private final PublicPersistenceOperations operations;
    private final MutationAuthor author;

    public ReplacementCommandFamilyRosterApi(
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull PublicPersistenceOperations operations,
            @Nonnull MutationAuthor author
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.author = Objects.requireNonNull(author, "author");
    }

    @Override
    @Nonnull
    public Optional<CommandFamilyRosterView> get(
            @Nonnull UUID ownerUuid,
            @Nonnull String commandFamilyId
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(commandFamilyId, "commandFamilyId");
        String normalized = commandFamilyId.trim();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        CommandFamilyKey family = new CommandFamilyKey(
                new OwnerId(ownerUuid), normalized
        );
        Map<CommandFamilyKey, Long> revisions =
                queries.projectedCommandRosterRevisions();
        Long revision = revisions.get(family);
        if (revision == null) {
            return Optional.empty();
        }
        ArrayList<CommandFamilyRosterMembershipView> members =
                new ArrayList<>();
        queries.projectedCommandRosterActions().values().stream()
                .filter(view -> family.equals(
                        view.membership().familyKey()
                ))
                .sorted(Comparator.comparing(
                        (CommandRosterActionView view) ->
                                view.membership().profileId().value()
                ))
                .map(this::membership)
                .forEach(members::add);
        long updatedAt = members.stream()
                .mapToLong(CommandFamilyRosterMembershipView::updatedAtMs)
                .max()
                .orElse(0L);
        return Optional.of(new CommandFamilyRosterView(
                ownerUuid,
                normalized,
                revision,
                members,
                updatedAt
        ));
    }

    @Override
    @Nonnull
    public Optional<CommandFamilyRosterMembershipView> getMembership(
            @Nonnull UUID ownerUuid,
            @Nonnull String commandFamilyId,
            @Nonnull String profileId
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(commandFamilyId, "commandFamilyId");
        Objects.requireNonNull(profileId, "profileId");
        ProfileId profile = profile(profileId);
        if (profile == null) {
            return Optional.empty();
        }
        CommandRosterActionView action = queries
                .projectedCommandRosterActions().get(profile);
        if (action == null
                || !action.membership().familyKey().ownerId().value()
                .equals(ownerUuid)
                || !action.membership().familyKey().familyId()
                .equals(commandFamilyId.trim())) {
            return Optional.empty();
        }
        return Optional.of(membership(action));
    }

    @Override
    @Nonnull
    public CompletionStage<CommandFamilyRosterMutationResult> upsert(
            @Nonnull CommandFamilyRosterMutationRequest request
    ) {
        return mutate(
                Objects.requireNonNull(request, "request"),
                CommandRosterMembershipRequest.Action.UPSERT
        );
    }

    @Override
    @Nonnull
    public CompletionStage<CommandFamilyRosterMutationResult> remove(
            @Nonnull CommandFamilyRosterMutationRequest request
    ) {
        return mutate(
                Objects.requireNonNull(request, "request"),
                CommandRosterMembershipRequest.Action.REMOVE
        );
    }

    private CompletionStage<CommandFamilyRosterMutationResult> mutate(
            CommandFamilyRosterMutationRequest request,
            CommandRosterMembershipRequest.Action action
    ) {
        CompletionStage<PreparedMutation> prepared;
        try {
            prepared = author.prepare(request, action);
        } catch (RuntimeException failure) {
            return completed(
                    CommandFamilyRosterMutationStatus.UNAVAILABLE,
                    failureCode(failure)
            );
        }
        if (prepared == null) {
            return completed(
                    CommandFamilyRosterMutationStatus.UNAVAILABLE,
                    "command-roster-author-returned-null"
            );
        }
        return prepared.thenCompose(candidate -> submit(candidate, request))
                .exceptionally(failure -> new CommandFamilyRosterMutationResult(
                        CommandFamilyRosterMutationStatus.FAILED,
                        failureCode(failure),
                        null,
                        null,
                        false
                ));
    }

    private CompletionStage<CommandFamilyRosterMutationResult> submit(
            PreparedMutation prepared,
            CommandFamilyRosterMutationRequest publicRequest
    ) {
        if (prepared == null) {
            return completed(
                    CommandFamilyRosterMutationStatus.UNAVAILABLE,
                    "command-roster-request-unresolvable"
            );
        }
        return queries.findOperation(
                CommandRosterMembershipDefinition.KIND,
                prepared.idempotencyKey()
        ).thenCompose(existing -> {
            if (existing instanceof PersistenceReadResult.Failed<?>) {
                return completed(
                        CommandFamilyRosterMutationStatus.UNAVAILABLE,
                        "command-roster-operation-read-failed"
                );
            }
            PreparedMutation submitted = prepared;
            if (existing instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found) {
                CommandRosterMembershipRequest durable =
                        CommandRosterMembershipDefinition.INSTANCE.decode(
                                found.value().operation().payloadJson()
                        );
                if (!durable.equals(prepared.request())) {
                    return completed(
                            CommandFamilyRosterMutationStatus.CONFLICT,
                            "command-roster-idempotency-conflict"
                    );
                }
                submitted = new PreparedMutation(
                        found.value().operation().operationId(),
                        prepared.idempotencyKey(),
                        durable,
                        prepared.previousMembership()
                );
            }
            return execute(submitted, publicRequest);
        });
    }

    private CompletionStage<CommandFamilyRosterMutationResult> execute(
            PreparedMutation prepared,
            CommandFamilyRosterMutationRequest publicRequest
    ) {
        PublicOperationSubmission submission;
        try {
            submission = operations.mutateCommandRoster(
                    prepared.operationId(),
                    prepared.idempotencyKey(),
                    prepared.request()
            );
        } catch (RuntimeException rejected) {
            return completed(
                    CommandFamilyRosterMutationStatus.UNAVAILABLE,
                    failureCode(rejected)
            );
        }
        if (!submission.accepted()) {
            return completed(
                    CommandFamilyRosterMutationStatus.UNAVAILABLE,
                    "command-roster-submission-"
                            + submission.admission().name()
                            .toLowerCase(java.util.Locale.ROOT)
            );
        }
        return submission.completion().thenApply(result ->
                result(
                        prepared,
                        publicRequest,
                        result
                ));
    }

    private CommandFamilyRosterMutationResult result(
            PreparedMutation prepared,
            CommandFamilyRosterMutationRequest request,
            OperationWorkflowResult workflow
    ) {
        if (workflow.status() != OperationWorkflowResult.Status.PUBLISHED) {
            return new CommandFamilyRosterMutationResult(
                    status(workflow),
                    workflowReason(workflow),
                    null,
                    null,
                    false
            );
        }
        Optional<CommandFamilyRosterView> roster = get(
                request.ownerUuid(), request.commandFamilyId()
        );
        Optional<CommandFamilyRosterMembershipView> current = getMembership(
                request.ownerUuid(),
                request.commandFamilyId(),
                request.profileId()
        );
        CommandRosterMutationOutcome outcome = outcome(workflow.events());
        boolean replay = outcome != null
                && Objects.equals(outcome.before(), outcome.after());
        return new CommandFamilyRosterMutationResult(
                replay
                        ? CommandFamilyRosterMutationStatus.IDEMPOTENT
                        : CommandFamilyRosterMutationStatus.APPLIED,
                replay ? "command-roster-idempotent"
                        : "command-roster-applied",
                roster.orElse(null),
                current.orElse(null),
                replay
        );
    }

    private CommandRosterMutationOutcome outcome(
            List<ProjectionEvent> events
    ) {
        for (ProjectionEvent event : events) {
            if (CommandRosterMembershipChangeCodec.EVENT_TYPE.equals(
                    event.eventType()
            )) {
                return CommandRosterMembershipChangeCodec.decode(
                        event.payloadVersion(), event.payloadJson()
                );
            }
        }
        return null;
    }

    private CommandFamilyRosterMembershipView membership(
            CommandRosterActionView action
    ) {
        CommandRosterHome home = action.membership().home();
        return new CommandFamilyRosterMembershipView(
                action.membership().familyKey().ownerId().value(),
                action.membership().familyKey().familyId(),
                action.membership().profileId().toString(),
                action.roleId(),
                action.metadataRevision(),
                state(action.lifecycle().state()),
                action.membership().groupId(),
                action.membership().activeForBulkCommands(),
                home == null
                        ? null
                        : new Vector3View(home.x(), home.y(), home.z()),
                action.membership().updatedAtMs()
        );
    }

    private CommandFamilyRosterMemberState state(LifecycleState state) {
        return switch (state) {
            case ACTIVE -> CommandFamilyRosterMemberState.ACTIVE;
            case UNLOADED, CAPTURED, COOP, RELEASED ->
                    CommandFamilyRosterMemberState.UNLOADED;
            case UNRESOLVED -> CommandFamilyRosterMemberState.UNAVAILABLE;
            case ROSTER_STORED, PROVISIONED_DORMANT ->
                    CommandFamilyRosterMemberState.ROSTER_STORED;
            case DEAD_REVIVABLE ->
                    CommandFamilyRosterMemberState.DEAD_REVIVABLE;
            case LOST -> CommandFamilyRosterMemberState.LOST;
        };
    }

    private CommandFamilyRosterMutationStatus status(
            OperationWorkflowResult workflow
    ) {
        String reason = workflowReason(workflow);
        if (reason.contains("revision") || reason.contains("conflict")) {
            return CommandFamilyRosterMutationStatus.CONFLICT;
        }
        if (reason.contains("not_found") || reason.contains("not-found")) {
            return CommandFamilyRosterMutationStatus.NOT_FOUND;
        }
        return switch (workflow.status()) {
            case LIVE_RETRYABLE, LIVE_UNKNOWN, PUBLICATION_PENDING,
                    COMPENSATION_REQUIRED, COMPENSATION_RETRYABLE,
                    COMPENSATION_UNKNOWN ->
                    CommandFamilyRosterMutationStatus.UNAVAILABLE;
            default -> CommandFamilyRosterMutationStatus.FAILED;
        };
    }

    private String workflowReason(OperationWorkflowResult workflow) {
        if (workflow.operation() != null
                && workflow.operation().failureCode() != null) {
            return workflow.operation().failureCode();
        }
        return workflow.status().name()
                .toLowerCase(java.util.Locale.ROOT);
    }

    private CompletionStage<CommandFamilyRosterMutationResult> completed(
            CommandFamilyRosterMutationStatus status,
            String reason
    ) {
        return CompletableFuture.completedFuture(
                new CommandFamilyRosterMutationResult(
                        status, reason, null, null, false
                )
        );
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

    /**
     * Resolves public intent into complete canonical evidence before submission.
     * Implementations may read config/world state but must not mutate it.
     */
    @FunctionalInterface
    public interface MutationAuthor {
        @Nonnull
        CompletionStage<PreparedMutation> prepare(
                @Nonnull CommandFamilyRosterMutationRequest request,
                @Nonnull CommandRosterMembershipRequest.Action action
        );
    }

    /** Complete shared-operation input plus exact pre-mutation public view. */
    public record PreparedMutation(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CommandRosterMembershipRequest request,
            @Nullable CommandFamilyRosterMembershipView previousMembership
    ) {
        public PreparedMutation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(request, "request");
        }
    }
}
