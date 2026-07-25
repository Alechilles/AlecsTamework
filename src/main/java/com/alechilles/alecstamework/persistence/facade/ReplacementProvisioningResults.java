package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.CompanionProvisioningLinkResult;
import com.alechilles.alecstamework.api.CompanionProvisioningProjectionStatus;
import com.alechilles.alecstamework.api.CompanionProvisioningRequest;
import com.alechilles.alecstamework.api.CompanionProvisioningResult;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.ProvisionedCompanionTransitionRequest;
import com.alechilles.alecstamework.api.ProvisionedCompanionView;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Maps replacement provisioning evidence to the stable public result model.
 *
 * <p>This collaborator keeps blocking bounded reads and presentation concerns
 * out of the mutation orchestrator.</p>
 */
final class ReplacementProvisioningResults {
    private final PublicPersistenceQueries queries;
    private final long readTimeoutMs;

    ReplacementProvisioningResults(
            PublicPersistenceQueries queries,
            Duration readTimeout
    ) {
        this.queries = Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(readTimeout, "readTimeout");
        if (readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "A positive provisioning read timeout is required"
            );
        }
        readTimeoutMs = readTimeout.toMillis();
    }

    Optional<ProvisionedCompanionView> getByProfileId(String profileId) {
        ProfileId parsed = profile(profileId);
        if (parsed == null) {
            return Optional.empty();
        }
        return queries.projectedProvisioning(parsed).flatMap(this::view);
    }

    Optional<ProvisionedCompanionView> getByOrigin(
            String callerNamespace,
            String idempotencyKey
    ) {
        ProvisioningOrigin origin;
        try {
            origin = new ProvisioningOrigin(
                    callerNamespace, idempotencyKey
            );
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
        return queries.projectedProvisioning(origin).flatMap(this::view);
    }

    CompanionProvisioningResult success(
            CompanionProvisioningRequest request,
            OperationId operationId,
            boolean replay
    ) {
        ProvisionedCompanionView view = getByOrigin(
                request.callerNamespace(), request.idempotencyKey()
        ).orElse(null);
        if (view == null) {
            return CompanionProvisioningResult.unavailable(
                    "provisioning-projection-pending"
            );
        }
        CompanionProvisioningResult.Status status = replay
                ? CompanionProvisioningResult.Status.ALREADY_PROVISIONED
                : view.lifecycle() == PopulationCompanionLifecycle.ACTIVE
                ? CompanionProvisioningResult.Status.PROVISIONED_ACTIVE
                : CompanionProvisioningResult.Status.PROVISIONED_DORMANT;
        return result(
                status,
                status.name().toLowerCase(Locale.ROOT),
                request.callerNamespace(),
                request.idempotencyKey(),
                operationId,
                view
        );
    }

    CompanionProvisioningResult transitionSuccess(
            ProvisionedCompanionTransitionRequest request,
            OperationId operationId
    ) {
        ProvisionedCompanionView view =
                getByProfileId(request.profileId()).orElse(null);
        return view == null
                ? CompanionProvisioningResult.unavailable(
                "provisioning-transition-projection-pending"
        )
                : result(
                CompanionProvisioningResult.Status.TRANSITIONED,
                "provisioning-transitioned",
                request.callerNamespace(),
                request.idempotencyKey(),
                operationId,
                view
        );
    }

    CompanionProvisioningResult partial(
            CompanionProvisioningRequest request,
            OperationId operationId,
            OperationWorkflowResult workflow
    ) {
        ProvisionedCompanionView view = getByOrigin(
                request.callerNamespace(), request.idempotencyKey()
        ).orElse(null);
        String reason = workflowReason(workflow);
        if (view == null) {
            return CompanionProvisioningResult.unavailable(reason);
        }
        return new CompanionProvisioningResult(
                CompanionProvisioningResult.Status.PARTIAL_DORMANT,
                reason,
                request.callerNamespace(),
                request.idempotencyKey(),
                operationId.value(),
                view.profileId(),
                view.ownerUuid(),
                view.roleId(),
                PopulationCompanionLifecycle.PROVISIONED_DORMANT,
                CompanionProvisioningProjectionStatus.FAILED_RECOVERABLE,
                reason,
                null,
                view.profileRevision()
        );
    }

    CompanionProvisioningResult failedCreation(
            CompanionProvisioningRequest request,
            OperationId operationId,
            OperationWorkflowResult workflow
    ) {
        String reason = workflowReason(workflow);
        return new CompanionProvisioningResult(
                CompanionProvisioningResult.Status.DENIED,
                reason,
                request.callerNamespace(),
                request.idempotencyKey(),
                operationId.value(),
                null,
                request.ownerUuid(),
                request.roleId(),
                null,
                CompanionProvisioningProjectionStatus.UNAVAILABLE,
                reason,
                null,
                CompanionProvisioningResult.UNKNOWN_PROFILE_REVISION
        );
    }

    CompanionProvisioningResult failedTransition(
            ProvisionedCompanionTransitionRequest request,
            OperationId operationId,
            OperationWorkflowResult workflow
    ) {
        ProvisionedCompanionView view =
                getByProfileId(request.profileId()).orElse(null);
        if (view == null) {
            return CompanionProvisioningResult.unavailable(
                    workflowReason(workflow)
            );
        }
        String reason = workflowReason(workflow);
        return new CompanionProvisioningResult(
                CompanionProvisioningResult.Status.DENIED,
                reason,
                request.callerNamespace(),
                request.idempotencyKey(),
                operationId.value(),
                view.profileId(),
                view.ownerUuid(),
                view.roleId(),
                view.lifecycle(),
                view.projectionStatus(),
                reason,
                null,
                view.profileRevision()
        );
    }

    CompanionProvisioningLinkResult unavailableLink(String reason) {
        return new CompanionProvisioningLinkResult(
                CompanionProvisioningLinkResult.Status.UNAVAILABLE,
                reason,
                CompanionProvisioningResult.unavailable(reason),
                null,
                null,
                null
        );
    }

    String failureCode(Throwable failure) {
        Throwable cause = failure instanceof CompletionException
                && failure.getCause() != null
                ? failure.getCause()
                : failure;
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message.trim();
    }

    private CompanionProvisioningResult result(
            CompanionProvisioningResult.Status status,
            String reason,
            String namespace,
            String key,
            OperationId operationId,
            ProvisionedCompanionView view
    ) {
        return new CompanionProvisioningResult(
                status,
                reason,
                namespace,
                key,
                operationId.value(),
                view.profileId(),
                view.ownerUuid(),
                view.roleId(),
                view.lifecycle(),
                view.projectionStatus(),
                view.projectionStatus().name().toLowerCase(Locale.ROOT),
                null,
                view.profileRevision()
        );
    }

    private Optional<ProvisionedCompanionView> view(
            ProvisioningRecord record
    ) {
        PersistenceReadResult<CompanionProfileReadModel> read = await(
                queries.findProfile(record.profileId())
        );
        return read instanceof PersistenceReadResult.Found<
                CompanionProfileReadModel> found
                ? Optional.of(ReplacementProvisioningMapper.companion(
                record, found.value()
        ))
                : Optional.empty();
    }

    private <T> PersistenceReadResult<T> await(
            CompletionStage<PersistenceReadResult<T>> stage
    ) {
        try {
            return stage.toCompletableFuture().get(
                    readTimeoutMs, TimeUnit.MILLISECONDS
            );
        } catch (Exception unavailable) {
            return null;
        }
    }

    private ProfileId profile(String value) {
        try {
            return ProfileId.parse(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private String workflowReason(OperationWorkflowResult workflow) {
        return workflow.operation() != null
                && workflow.operation().failureCode() != null
                ? workflow.operation().failureCode()
                : workflow.status().name().toLowerCase(Locale.ROOT);
    }
}
