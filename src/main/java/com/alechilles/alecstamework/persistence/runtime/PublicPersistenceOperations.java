package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionRequest;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseMutationRequest;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistration;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionRequest;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationRequest;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentRequest;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteDatabaseOperationCoordinator;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePublicPersistenceAdapter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSingleWriter;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Adapter-neutral mutation facade for all released public persistence behavior.
 *
 * <p>Every method enters the same admission, envelope, projection, recovery,
 * and shutdown workflow.</p>
 */
public final class PublicPersistenceOperations {
    private final SqlitePublicPersistenceAdapter adapter;
    private final PublicPersistenceLiveBoundaries boundaries;
    private final PublicPersistenceWorkflowTracker workflows;

    PublicPersistenceOperations(
            SqlitePublicPersistenceAdapter adapter,
            PublicPersistenceLiveBoundaries boundaries,
            PublicPersistenceWorkflowTracker workflows
    ) {
        this.adapter = adapter;
        this.boundaries = boundaries;
        this.workflows = workflows;
    }

    @Nonnull
    public PublicOperationSubmission mutateProfile(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionProfileMutation mutation
    ) {
        var submitted = adapter.profileOperations().submit(
                operationId, idempotencyKey, mutation
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    /**
     * Narrow internal path for the startup graph's evidence-backed resolution.
     */
    PublicOperationSubmission reconcileProfileDuringStartup(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            CompanionProfileMutation.StartupReconciliation reconciliation
    ) {
        var submitted = adapter.reconcileProfileAtStartup(
                operationId,
                idempotencyKey,
                reconciliation
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission rotateAlias(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionAliasRotation rotation
    ) {
        var submitted = adapter.aliasOperations().submit(
                operationId,
                idempotencyKey,
                rotation
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission capture(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCaptureRequest capture
    ) {
        var submitted = adapter.captureOperations().submit(
                operationId,
                idempotencyKey,
                capture,
                boundaries.captures()
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission releaseCapturedCompanion(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCaptureReleaseRequest release
    ) {
        var submitted = adapter.captureReleaseOperations().submit(
                operationId,
                idempotencyKey,
                release,
                boundaries.capturedReleases()
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission makeDormant(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionDormantTransitionRequest dormant
    ) {
        var submitted = adapter.dormantOperations().submit(
                operationId, idempotencyKey, dormant
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission restore(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionRestorationRequest restoration
    ) {
        var submitted = adapter.restorationOperations().submit(
                operationId,
                idempotencyKey,
                restoration,
                boundaries.restorations()
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission mutateTimedSummonLease(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull TimedSummonLeaseMutationRequest mutation
    ) {
        var submitted = adapter.timedSummonOperations().submit(
                operationId, idempotencyKey, mutation
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission transitionTimedSummon(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull TimedSummonTransitionRequest transition
    ) {
        var submitted = adapter.timedSummonTransitionOperations().submit(
                operationId,
                idempotencyKey,
                transition,
                boundaries.timedSummons()
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission registerCoopSlot(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CoopSlotRegistration registration
    ) {
        var submitted = adapter.coopSlotOperations().submit(
                operationId, idempotencyKey, registration
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission captureToCoop(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCoopCaptureRequest capture
    ) {
        var submitted = adapter.coopCaptureOperations().submit(
                operationId,
                idempotencyKey,
                capture,
                boundaries.coopCaptures()
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission releaseFromCoop(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CompanionCoopReleaseRequest release
    ) {
        var submitted = adapter.coopReleaseOperations().submit(
                operationId,
                idempotencyKey,
                release,
                boundaries.coopReleases()
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission mutateExtension(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull ProfileExtensionMutation mutation
    ) {
        SqliteDatabaseOperationCoordinator.Submission submitted =
                adapter.extensionOperations().submit(
                        operationId, idempotencyKey, mutation
                );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission transitionOwnerPopulation(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull OwnerPopulationTransitionRequest transition
    ) {
        var submitted = adapter.ownerPopulationOperations().submit(
                operationId, idempotencyKey, transition
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission reconcileOwnerPopulation(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull OwnerPopulationReconciliationRequest reconciliation
    ) {
        var submitted =
                adapter.ownerPopulationReconciliationOperations().submit(
                        operationId, idempotencyKey, reconciliation
                );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission assignPopulationGroups(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull PopulationGroupAssignmentRequest assignment
    ) {
        var submitted = adapter.populationGroupOperations().submit(
                operationId, idempotencyKey, assignment
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission mutateCommandRoster(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CommandRosterMembershipRequest request
    ) {
        var submitted = adapter.commandRosterOperations().submit(
                operationId, idempotencyKey, request
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    @Nonnull
    public PublicOperationSubmission transitionCommandRoster(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey idempotencyKey,
            @Nonnull CommandRosterTransitionRequest transition
    ) {
        var submitted = adapter.commandRosterTransitionOperations().submit(
                operationId, idempotencyKey, transition
        );
        return submission(submitted.acceptance(), submitted.completion());
    }

    private PublicOperationSubmission submission(
            SqliteSingleWriter.WriteAcceptance acceptance,
            CompletionStage<OperationWorkflowResult> completion
    ) {
        PublicOperationSubmission.Admission publicAdmission =
                switch (acceptance) {
                    case ACCEPTED ->
                            PublicOperationSubmission.Admission.ACCEPTED;
                    case CANCELLED_BEFORE_ACCEPTANCE ->
                            PublicOperationSubmission.Admission
                                    .CANCELLED_BEFORE_ACCEPTANCE;
                    case REJECTED ->
                            PublicOperationSubmission.Admission.REJECTED;
                };
        CompletionStage<OperationWorkflowResult> tracked =
                publicAdmission == PublicOperationSubmission.Admission.ACCEPTED
                        ? workflows.track(completion)
                        : completion;
        return new PublicOperationSubmission(publicAdmission, tracked);
    }
}
