package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.ownership.CoopPopulationCaptureAdmissionService;
import com.alechilles.alecstamework.ownership.CoopPopulationCaptureAdmissionService.PreparedCapture;
import com.alechilles.alecstamework.ownership.CoopPopulationCaptureAdmissionService.PreparationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.CaptureRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationStatus;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopPopulationMutationContext;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Commits canonical population state and the exact v5 capture claim through one journal boundary.
 */
final class ManagedCoopPopulationCaptureCommitter {
    private final CoopPopulationCaptureAdmissionService admissions;
    private final CompanionIdentityResolver identities;
    private final CoopLifecycleOperationRepository lifecycle;

    ManagedCoopPopulationCaptureCommitter(
            @Nonnull CoopPopulationCaptureAdmissionService admissions,
            @Nonnull CompanionIdentityResolver identities,
            @Nonnull CoopLifecycleOperationRepository lifecycle) {
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** Starts no source retirement; success only proves the combined commit reached SQLite. */
    @Nonnull
    CompletionStage<MutationResult> commit(
            @Nonnull ManagedCoopCaptureCoordinator.CaptureAttempt attempt,
            @Nonnull CaptureRequest capture) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(capture, "capture");
        try {
            // Profile ensure is already durable, so publishing this alias cannot outlive its row.
            identities.remap(capture.profileId(), capture.sourceNpcUuid(), capture.sourceNpcUuid());
            CoopPopulationCaptureAdmissionService.CaptureRequest populationRequest =
                    new CoopPopulationCaptureAdmissionService.CaptureRequest(
                            capture.profileId(),
                            capture.sourceNpcUuid(),
                            attempt.ownerUuid(),
                            attempt.sourceKind(),
                            attempt.sourceChunk(),
                            attempt.newlyEnsuredUnownedProfile(),
                            capture.operationId()
                    );
            CompletableFuture<PreparationResult> preparation = admissions.prepareAsync(
                    populationRequest,
                    profileId -> {
                        if (!capture.profileId().equals(profileId)) {
                            throw new IllegalArgumentException(
                                    "capture durable context profile changed");
                        }
                        return ManagedCoopPopulationMutationContext.captureExtensionJson(capture);
                    }
            );
            if (preparation == null) {
                return completed(conflict("capture_population_prepare_stage_missing"));
            }
            return preparation.thenCompose(result -> afterPreparation(capture, result))
                    .exceptionally(failure -> conflict(detail(
                            "capture_population_prepare", failure)));
        } catch (RuntimeException | LinkageError failure) {
            return completed(conflict(detail("capture_population_prepare", failure)));
        }
    }

    @Nonnull
    private CompletionStage<MutationResult> afterPreparation(
            CaptureRequest capture,
            @Nullable PreparationResult preparation) {
        if (preparation == null || !preparation.allowed()
                || preparation.preparedCapture() == null) {
            return completed(conflict(preparation == null
                    ? "capture_population_prepare_result_missing"
                    : preparation.reason()));
        }
        PreparedCapture prepared = preparation.preparedCapture();
        if (!admissions.claimForCommit(prepared)) {
            return cancelThenConflict(
                    prepared, "capture_population_admission_revalidation_failed");
        }
        final CompletableFuture<CompanionPopulationCommitResult> commit;
        try {
            commit = admissions.commitAsync(prepared);
        } catch (RuntimeException | LinkageError failure) {
            return completed(conflict(detail("capture_population_commit_start", failure)));
        }
        if (commit == null) {
            return completed(conflict("capture_population_commit_stage_missing"));
        }
        return commit.handle((result, failure) -> afterCommit(capture, result, failure));
    }

    @Nonnull
    private MutationResult afterCommit(
            CaptureRequest capture,
            @Nullable CompanionPopulationCommitResult result,
            @Nullable Throwable failure) {
        if (failure != null || result == null || !result.committed()) {
            return conflict(failure != null
                    ? detail("capture_population_commit", failure)
                    : result == null
                    ? "capture_population_commit_result_missing"
                    : result.reason());
        }
        try {
            identities.markDurable(capture.profileId(), capture.sourceNpcUuid());
            OperationRecord operation = lifecycle.load(capture.operationId());
            return operation == null
                    ? conflict("capture_lifecycle_operation_missing_after_population_commit")
                    : new MutationResult(MutationStatus.APPLIED, operation, null);
        } catch (Exception | LinkageError evidenceFailure) {
            return conflict(detail("capture_post_commit_evidence", evidenceFailure));
        }
    }

    @Nonnull
    private CompletionStage<MutationResult> cancelThenConflict(
            PreparedCapture prepared,
            String reason) {
        try {
            CompletableFuture<Boolean> cancellation = admissions.cancelAsync(prepared, reason);
            if (cancellation == null) {
                return completed(conflict(reason + ":cancel_stage_missing"));
            }
            return cancellation.handle((ignored, failure) -> conflict(
                    failure == null ? reason : reason + ":cancel_failed"));
        } catch (RuntimeException | LinkageError failure) {
            return completed(conflict(reason + ":cancel_failed"));
        }
    }

    @Nonnull
    private static MutationResult conflict(@Nullable String reason) {
        return new MutationResult(
                MutationStatus.CONFLICT,
                null,
                reason == null || reason.isBlank() ? "capture_population_conflict" : reason
        );
    }

    @Nonnull
    private static CompletionStage<MutationResult> completed(MutationResult result) {
        return CompletableFuture.completedFuture(result);
    }

    @Nonnull
    private static String detail(String stage, Throwable failure) {
        Throwable cause = failure != null && failure.getCause() != null
                ? failure.getCause() : failure;
        String message = cause != null ? cause.getMessage() : null;
        return stage + ":" + (message == null || message.isBlank()
                ? cause != null ? cause.getClass().getSimpleName() : "unknown"
                : message);
    }
}
