package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.CommandFamilyRosterApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningRequest;
import com.alechilles.alecstamework.api.CommandTimedSummoningResult;
import com.alechilles.alecstamework.api.CompanionProvisioningApi;
import com.alechilles.alecstamework.api.CompanionProvisioningLinkRequest;
import com.alechilles.alecstamework.api.CompanionProvisioningLinkResult;
import com.alechilles.alecstamework.api.CompanionProvisioningOperationView;
import com.alechilles.alecstamework.api.CompanionProvisioningRequest;
import com.alechilles.alecstamework.api.CompanionProvisioningResult;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransitionRequest;
import com.alechilles.alecstamework.api.ProvisionedCompanionView;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import com.alechilles.alecstamework.persistence.runtime.PublicOperationEvidence;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provisioning API over deterministic provenance, canonical profiles, and the
 * shared dormant-create/activation/restoration operations.
 */
public final class ReplacementCompanionProvisioningApi
        implements CompanionProvisioningApi {
    private final PublicPersistenceQueries queries;
    private final PublicPersistenceOperations operations;
    private final MutationAuthor author;
    private final CommandFamilyRosterApi rosters;
    private final CommandTimedSummoningApi timed;
    private final ReplacementProvisioningResults results;

    public ReplacementCompanionProvisioningApi(
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull PublicPersistenceOperations operations,
            @Nonnull MutationAuthor author,
            @Nonnull CommandFamilyRosterApi rosters,
            @Nonnull CommandTimedSummoningApi timed,
            @Nonnull Duration readTimeout
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.author = Objects.requireNonNull(author, "author");
        this.rosters = Objects.requireNonNull(rosters, "rosters");
        this.timed = Objects.requireNonNull(timed, "timed");
        results = new ReplacementProvisioningResults(queries, readTimeout);
    }

    @Override
    @Nonnull
    public Optional<ProvisionedCompanionView> getByProfileId(
            @Nonnull String profileId
    ) {
        Objects.requireNonNull(profileId, "profileId");
        return results.getByProfileId(profileId);
    }
    @Override
    @Nonnull
    public Optional<ProvisionedCompanionView> getByOrigin(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey
    ) {
        Objects.requireNonNull(callerNamespace, "callerNamespace");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        return results.getByOrigin(callerNamespace, idempotencyKey);
    }
    @Override
    @Nonnull
    public CompletionStage<CompanionProvisioningResult> provision(
            @Nonnull CompanionProvisioningRequest request
    ) {
        Objects.requireNonNull(request, "request");
        CompletionStage<PreparedProvisioning> stage;
        try {
            stage = author.prepare(request);
        } catch (RuntimeException failure) {
            return unavailable(results.failureCode(failure));
        }
        return prepare(stage, request);
    }
    @Override
    @Nonnull
    public CompletionStage<CompanionProvisioningLinkResult> provisionAndLink(
            @Nonnull CompanionProvisioningLinkRequest request
    ) {
        Objects.requireNonNull(request, "request");
        CompletionStage<PreparedProvisioning> stage;
        try {
            stage = author.prepare(request);
        } catch (RuntimeException failure) {
            return linkUnavailable(results.failureCode(failure));
        }
        if (stage == null) {
            return linkUnavailable("provisioning-author-returned-null");
        }
        return stage.thenCompose(prepared ->
                provision(prepared, request.provisioning())
        ).thenApply(result -> linkResult(request, result))
                .exceptionally(failure -> results.unavailableLink(
                        results.failureCode(failure)
                ));
    }

    @Override
    @Nonnull
    public CompletionStage<CompanionProvisioningResult> transition(
            @Nonnull ProvisionedCompanionTransitionRequest request
    ) {
        Objects.requireNonNull(request, "request");
        CompletionStage<PreparedTransition> stage;
        try {
            stage = author.prepare(request);
        } catch (RuntimeException failure) {
            return unavailable(results.failureCode(failure));
        }
        if (stage == null) {
            return unavailable("provisioning-transition-author-returned-null");
        }
        return stage.thenCompose(prepared ->
                executeTransition(prepared, request)
        ).exceptionally(failure ->
                CompanionProvisioningResult.unavailable(
                        results.failureCode(failure)
                ));
    }

    @Override
    @Nonnull
    public CompletionStage<Optional<CompanionProvisioningOperationView>>
    findOperation(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey
    ) {
        Objects.requireNonNull(callerNamespace, "callerNamespace");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        ProvisioningOrigin origin;
        try {
            origin = new ProvisioningOrigin(
                    callerNamespace, idempotencyKey
            );
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return queries.findOperation(
                CompanionProvisioningDefinition.KIND,
                origin.operationKey()
        ).thenApply(read -> {
            if (!(read instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found)) {
                return Optional.empty();
            }
            return Optional.of(ReplacementProvisioningMapper.operation(
                    found.value().operation()
            ));
        });
    }

    private CompletionStage<CompanionProvisioningResult> prepare(
            CompletionStage<PreparedProvisioning> stage,
            CompanionProvisioningRequest request
    ) {
        if (stage == null) {
            return unavailable("provisioning-author-returned-null");
        }
        return stage.thenCompose(prepared -> provision(prepared, request))
                .exceptionally(failure ->
                        CompanionProvisioningResult.unavailable(
                                results.failureCode(failure)
                        ));
    }

    private CompletionStage<CompanionProvisioningResult> provision(
            PreparedProvisioning prepared,
            CompanionProvisioningRequest request
    ) {
        if (prepared == null) {
            return unavailable("provisioning-request-unresolvable");
        }
        ProvisioningOrigin origin = prepared.request().origin();
        return queries.findOperation(
                CompanionProvisioningDefinition.KIND,
                origin.operationKey()
        ).thenCompose(existing -> {
            if (existing instanceof PersistenceReadResult.Failed<?>) {
                return unavailable("provisioning-operation-read-failed");
            }
            PreparedProvisioning submitted = prepared;
            if (existing instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found) {
                var durable = CompanionProvisioningDefinition.INSTANCE.decode(
                        found.value().operation().payloadJson()
                );
                if (!durable.equals(prepared.request())) {
                    return unavailable("provisioning-idempotency-conflict");
                }
                submitted = new PreparedProvisioning(
                        found.value().operation().operationId(),
                        durable,
                        prepared.activation(),
                        true
                );
            }
            return executeProvision(submitted, request);
        });
    }

    private CompletionStage<CompanionProvisioningResult> executeProvision(
            PreparedProvisioning prepared,
            CompanionProvisioningRequest publicRequest
    ) {
        PublicOperationSubmission submitted;
        try {
            submitted = operations.provisionCompanion(
                    prepared.operationId(), prepared.request()
            );
        } catch (RuntimeException rejected) {
            return unavailable(results.failureCode(rejected));
        }
        if (!submitted.accepted()) {
            return unavailable("provisioning-submission-rejected");
        }
        return submitted.completion().thenCompose(workflow -> {
            if (workflow.status()
                    != OperationWorkflowResult.Status.PUBLISHED) {
                return CompletableFuture.completedFuture(results.failedCreation(
                        publicRequest,
                        prepared.operationId(),
                        workflow
                ));
            }
            if (prepared.activation() == null) {
                return CompletableFuture.completedFuture(results.success(
                        publicRequest,
                        prepared.operationId(),
                        prepared.idempotentReplay()
                ));
            }
            return executeActivation(
                    prepared.activation(), publicRequest,
                    prepared.operationId(), prepared.idempotentReplay()
            );
        });
    }

    private CompletionStage<CompanionProvisioningResult> executeActivation(
            PreparedActivation activation,
            CompanionProvisioningRequest publicRequest,
            OperationId creationOperationId,
            boolean replay
    ) {
        return queries.findOperation(
                ProvisioningActivationDefinition.KIND,
                activation.request().origin().activationKey(
                        activation.request().spawnReceiptKey()
                )
        ).thenCompose(existing -> {
            PreparedActivation submitted = activation;
            boolean existingReplay = false;
            if (existing instanceof PersistenceReadResult.Failed<?>) {
                return unavailable("provisioning-activation-read-failed");
            }
            if (existing instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found) {
                ProvisioningActivationRequest durable =
                        ProvisioningActivationDefinition.INSTANCE.decode(
                                found.value().operation().payloadJson()
                        );
                if (!durable.equals(activation.request())) {
                    return unavailable(
                            "provisioning-activation-idempotency-conflict"
                    );
                }
                submitted = new PreparedActivation(
                        found.value().operation().operationId(), durable
                );
                existingReplay = true;
            }
            PublicOperationSubmission operation;
            try {
                operation = operations.activateProvisionedCompanion(
                        submitted.operationId(), submitted.request()
                );
            } catch (RuntimeException rejected) {
                return unavailable(results.failureCode(rejected));
            }
            if (!operation.accepted()) {
                return unavailable("provisioning-activation-rejected");
            }
            boolean idempotent = replay || existingReplay;
            return operation.completion().thenApply(workflow ->
                    workflow.status()
                            == OperationWorkflowResult.Status.PUBLISHED
                            ? results.success(
                            publicRequest,
                            creationOperationId,
                            idempotent
                    )
                            : results.partial(
                            publicRequest,
                            creationOperationId,
                            workflow
                    ));
        });
    }

    private CompletionStage<CompanionProvisioningResult> executeTransition(
            PreparedTransition prepared,
            ProvisionedCompanionTransitionRequest publicRequest
    ) {
        if (prepared == null) {
            return unavailable("provisioning-transition-unresolvable");
        }
        PublicOperationSubmission submitted;
        try {
            if (prepared instanceof PreparedTransition.Activation activation) {
                submitted = operations.activateProvisionedCompanion(
                        activation.operationId(), activation.request()
                );
            } else {
                PreparedTransition.Restoration restoration =
                        (PreparedTransition.Restoration) prepared;
                submitted = operations.restore(
                        restoration.operationId(),
                        restoration.idempotencyKey(),
                        restoration.request()
                );
            }
        } catch (RuntimeException rejected) {
            return unavailable(results.failureCode(rejected));
        }
        if (!submitted.accepted()) {
            return unavailable("provisioning-transition-rejected");
        }
        OperationId operationId = prepared.operationId();
        return submitted.completion().thenApply(workflow ->
                workflow.status()
                        == OperationWorkflowResult.Status.PUBLISHED
                        ? results.transitionSuccess(publicRequest, operationId)
                        : results.failedTransition(
                        publicRequest, operationId, workflow
                ));
    }

    private CompanionProvisioningLinkResult linkResult(
            CompanionProvisioningLinkRequest request,
            CompanionProvisioningResult provisioning
    ) {
        if (!provisioning.accepted() || provisioning.profileId() == null) {
            return results.unavailableLink(provisioning.reason());
        }
        var roster = rosters.get(
                request.provisioning().ownerUuid(),
                request.commandFamilyId()
        ).orElse(null);
        var member = rosters.getMembership(
                request.provisioning().ownerUuid(),
                request.commandFamilyId(),
                provisioning.profileId()
        ).orElse(null);
        if (roster == null || member == null) {
            return results.unavailableLink(
                    "provisioning-roster-projection-pending"
            );
        }
        CommandTimedSummoningResult initial = null;
        if (request.requestInitialProjection()) {
            initial = timed.get(new CommandTimedSummoningRequest(
                    request.provisioning().ownerUuid(),
                    request.commandFamilyId(),
                    provisioning.profileId(),
                    request.provisioning().idempotencyKey()
            )).map(view -> new CommandTimedSummoningResult(
                    CommandTimedSummoningResult.Status.SUCCESS,
                    "initial-projection-published",
                    view
            )).orElse(null);
        }
        boolean replay = provisioning.status()
                == CompanionProvisioningResult.Status.ALREADY_PROVISIONED;
        return new CompanionProvisioningLinkResult(
                replay
                        ? CompanionProvisioningLinkResult.Status
                        .ALREADY_COMMITTED
                        : CompanionProvisioningLinkResult.Status.COMMITTED,
                replay ? "provisioning-link-idempotent"
                        : "provisioning-link-committed",
                provisioning,
                roster,
                member,
                initial
        );
    }

    private CompletionStage<CompanionProvisioningResult> unavailable(
            String reason
    ) {
        return CompletableFuture.completedFuture(
                CompanionProvisioningResult.unavailable(reason)
        );
    }

    private CompletionStage<CompanionProvisioningLinkResult> linkUnavailable(
            String reason
    ) {
        return CompletableFuture.completedFuture(
                results.unavailableLink(reason)
        );
    }

    /**
     * Resolves caller intent into frozen replacement-domain evidence. It must
     * not perform the live effect represented by that evidence.
     */
    public interface MutationAuthor {
        @Nonnull
        CompletionStage<PreparedProvisioning> prepare(
                @Nonnull CompanionProvisioningRequest request
        );

        @Nonnull
        CompletionStage<PreparedProvisioning> prepare(
                @Nonnull CompanionProvisioningLinkRequest request
        );

        @Nonnull
        CompletionStage<PreparedTransition> prepare(
                @Nonnull ProvisionedCompanionTransitionRequest request
        );
    }

    /** Dormant creation and optional first activation, submitted in order. */
    public record PreparedProvisioning(
            @Nonnull OperationId operationId,
            @Nonnull com.alechilles.alecstamework.companion.provisioning
                    .CompanionProvisioningRequest request,
            @Nullable PreparedActivation activation,
            boolean idempotentReplay
    ) {
        public PreparedProvisioning {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(request, "request");
        }
    }

    /** Exact initial dormant-to-live operation. */
    public record PreparedActivation(
            @Nonnull OperationId operationId,
            @Nonnull ProvisioningActivationRequest request
    ) {
        public PreparedActivation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(request, "request");
        }
    }

    /** Supported existing-profile transition plans over shared operations. */
    public sealed interface PreparedTransition {
        @Nonnull
        OperationId operationId();

        record Activation(
                @Nonnull OperationId operationId,
                @Nonnull ProvisioningActivationRequest request
        ) implements PreparedTransition {
            public Activation {
                Objects.requireNonNull(operationId, "operationId");
                Objects.requireNonNull(request, "request");
            }
        }

        record Restoration(
                @Nonnull OperationId operationId,
                @Nonnull IdempotencyKey idempotencyKey,
                @Nonnull CompanionRestorationRequest request
        ) implements PreparedTransition {
            public Restoration {
                Objects.requireNonNull(operationId, "operationId");
                Objects.requireNonNull(idempotencyKey, "idempotencyKey");
                Objects.requireNonNull(request, "request");
            }
        }
    }
}
