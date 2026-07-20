package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationScanSessionRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Seals restart evidence and its scan session after the final canonical reload succeeds. */
final class CompanionPopulationFinalizationService {
    private final String scanEpoch;
    private final LoadedNpcIdentityIndex loadedIdentities;
    private final CompanionPersistedProjectionEvidenceRegistry projections;
    private final CompanionLiveEvidenceRevision liveEvidenceRevision;
    private final CompanionPopulationCoveragePublisher coverage;
    private final SessionTransition markSessionReady;
    private final SessionTransition invalidateReadySession;
    @Nullable
    private final ReconciliationEvidenceRecoveryProofRegistry recoveryProofs;

    CompanionPopulationFinalizationService(
            @Nonnull CompanionPopulationScanSessionRepository sessions,
            @Nonnull String scanEpoch,
            @Nonnull LoadedNpcIdentityIndex loadedIdentities,
            @Nonnull CompanionPersistedProjectionEvidenceRegistry projections,
            @Nonnull CompanionLiveEvidenceRevision liveEvidenceRevision,
            @Nonnull CompanionPopulationCoveragePublisher coverage,
            @Nullable ReconciliationEvidenceRecoveryProofRegistry recoveryProofs
    ) {
        this(
                scanEpoch,
                loadedIdentities,
                projections,
                liveEvidenceRevision,
                coverage,
                epoch -> committedValue(sessions.markReadyAsync(epoch)),
                epoch -> committedValue(sessions.invalidateReadyAsync(epoch)),
                recoveryProofs
        );
        Objects.requireNonNull(sessions, "sessions");
    }

    CompanionPopulationFinalizationService(
            @Nonnull String scanEpoch,
            @Nonnull LoadedNpcIdentityIndex loadedIdentities,
            @Nonnull CompanionPersistedProjectionEvidenceRegistry projections,
            @Nonnull CompanionLiveEvidenceRevision liveEvidenceRevision,
            @Nonnull CompanionPopulationCoveragePublisher coverage,
            @Nonnull SessionTransition markSessionReady,
            @Nonnull SessionTransition invalidateReadySession
    ) {
        this(scanEpoch, loadedIdentities, projections, liveEvidenceRevision, coverage,
                markSessionReady, invalidateReadySession, null);
    }

    CompanionPopulationFinalizationService(
            @Nonnull String scanEpoch,
            @Nonnull LoadedNpcIdentityIndex loadedIdentities,
            @Nonnull CompanionPersistedProjectionEvidenceRegistry projections,
            @Nonnull CompanionLiveEvidenceRevision liveEvidenceRevision,
            @Nonnull CompanionPopulationCoveragePublisher coverage,
            @Nonnull SessionTransition markSessionReady,
            @Nonnull SessionTransition invalidateReadySession,
            @Nullable ReconciliationEvidenceRecoveryProofRegistry recoveryProofs
    ) {
        this.scanEpoch = Objects.requireNonNull(scanEpoch, "scanEpoch");
        this.loadedIdentities = Objects.requireNonNull(loadedIdentities, "loadedIdentities");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.liveEvidenceRevision = Objects.requireNonNull(
                liveEvidenceRevision, "liveEvidenceRevision"
        );
        this.coverage = Objects.requireNonNull(coverage, "coverage");
        this.markSessionReady = Objects.requireNonNull(markSessionReady, "markSessionReady");
        this.invalidateReadySession = Objects.requireNonNull(
                invalidateReadySession, "invalidateReadySession"
        );
        this.recoveryProofs = recoveryProofs;
    }

    @Nonnull
    CompletableFuture<CompanionPopulationReconciliationService.Result> completeAsync(
            @Nonnull CompanionPopulationReconciliationService.Result result
    ) {
        if (result.status() != CompanionPopulationReconciliationService.Status.READY
                || result.loadedIdentityRevision() == null
                || result.liveEvidenceRevision() == null
                || result.projectionEvidenceSet() == null) {
            return CompletableFuture.completedFuture(reject(
                    result,
                    CompanionPopulationReconciliationService.Status.DEGRADED,
                    "reconciliation-final-readiness-invalid"
            ));
        }
        String stabilityFailure = stabilityFailure(result);
        if (stabilityFailure != null) {
            return failure(result, stabilityFailure);
        }
        return invokeTransition(markSessionReady).thenCompose(markedReady -> {
            if (!markedReady) {
                return invalidateAndFail(result, "reconciliation-session-complete-failed");
            }
            String postCommitFailure = stabilityFailure(result);
            if (postCommitFailure != null) {
                return invalidateAndFail(result, postCommitFailure);
            }
            if (!publishSealed(result)) {
                return invalidateAndFail(
                        result, "reconciliation-projection-evidence-publish-failed"
                );
            }
            return publishFinalCoverage(
                    result,
                    CompanionPopulationCoverageRecord.State.READY,
                    null
            ).thenCompose(published -> {
                if (!published) {
                    return invalidateAndFail(
                            result, "reconciliation-final-coverage-publish-failed"
                    );
                }
                String postPublicationFailure = sealedAuthorityFailure(result);
                if (postPublicationFailure != null) {
                    return invalidateAndFail(result, postPublicationFailure);
                }
                if (recoveryProofs != null) {
                    recoveryProofs.seal(scanEpoch);
                }
                return CompletableFuture.completedFuture(result.withoutFinalizationMetadata());
            });
        });
    }

    /** Publishes the intentional global-only readiness lane after the same canonical fence. */
    @Nonnull
    CompletableFuture<CompanionPopulationReconciliationService.Result> completePartialAsync(
            @Nonnull CompanionPopulationReconciliationService.Result result
    ) {
        if (result.status() != CompanionPopulationReconciliationService.Status.RECONCILING
                || result.loadedIdentityRevision() == null
                || result.liveEvidenceRevision() == null
                || result.projectionEvidenceSet() == null) {
            return invalidateAndFail(result, "reconciliation-partial-readiness-invalid");
        }
        String stabilityFailure = stabilityFailure(result);
        if (stabilityFailure != null) {
            return invalidateAndFail(result, stabilityFailure);
        }
        if (!projections.degrade(scanEpoch, result.reason())) {
            return invalidateAndFail(
                    result, "reconciliation-projection-evidence-degrade-failed"
            );
        }
        return publishFinalCoverage(
                result,
                CompanionPopulationCoverageRecord.State.RECONCILING,
                result.reason()
        ).thenCompose(published -> {
            if (!published) {
                return invalidateAndFail(
                        result, "reconciliation-partial-coverage-publish-failed"
                );
            }
            String postPublicationFailure = stabilityFailure(result);
            return postPublicationFailure == null
                    ? CompletableFuture.completedFuture(result.withoutFinalizationMetadata())
                    : invalidateAndFail(result, postPublicationFailure);
        });
    }

    private boolean publishSealed(
            @Nonnull CompanionPopulationReconciliationService.Result result
    ) {
        try {
            return projections.publishSealed(
                    scanEpoch,
                    result.projectionEvidenceSet(),
                    result.loadedIdentityRevision(),
                    result.liveEvidenceRevision()
            );
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    @Nonnull
    private CompletableFuture<Boolean> publishFinalCoverage(
            @Nonnull CompanionPopulationReconciliationService.Result result,
            @Nonnull CompanionPopulationCoverageRecord.State perWorldState,
            @Nullable String perWorldReason
    ) {
        final CompletableFuture<Boolean> publication;
        try {
            publication = coverage.publishMergedAsync(
                    perWorldState, result.profileCount(), perWorldReason
            );
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(false);
        }
        if (publication == null) {
            return CompletableFuture.completedFuture(false);
        }
        return publication.handle((written, failure) ->
                failure == null && Boolean.TRUE.equals(written));
    }

    @Nonnull
    CompletableFuture<CompanionPopulationReconciliationService.Result> invalidateAndFail(
            @Nonnull CompanionPopulationReconciliationService.Result result,
            @Nonnull String reason
    ) {
        if (recoveryProofs != null) {
            recoveryProofs.invalidate(scanEpoch);
        }
        projections.degrade(scanEpoch, reason);
        return invokeTransition(invalidateReadySession).thenCompose(invalidated -> failure(
                result,
                invalidated ? reason : "reconciliation-session-invalidate-failed"
        ));
    }

    @Nullable
    private String stabilityFailure(
            @Nonnull CompanionPopulationReconciliationService.Result result
    ) {
        if (!loadedIdentities.isMutationRevisionCurrent(result.loadedIdentityRevision())) {
            return "reconciliation-loaded-identity-mutated-during-final-reload";
        }
        if (!liveEvidenceRevision.isCurrent(result.liveEvidenceRevision())) {
            return "reconciliation-live-evidence-mutated-during-final-reload";
        }
        return null;
    }

    @Nullable
    private String sealedAuthorityFailure(
            @Nonnull CompanionPopulationReconciliationService.Result result
    ) {
        String stabilityFailure = stabilityFailure(result);
        if (stabilityFailure != null) {
            return stabilityFailure;
        }
        CompanionPersistedProjectionEvidenceRegistry.Snapshot projection =
                projections.snapshot();
        if (!projection.sealed()
                || !scanEpoch.equals(projection.scanEpoch())
                || projection.loadedIdentities() == null
                || projection.loadedIdentities().mutationRevision()
                != result.loadedIdentityRevision()
                || projection.liveEvidenceRevision() != result.liveEvidenceRevision()) {
            return "reconciliation-projection-evidence-invalidated-during-coverage-publish";
        }
        return null;
    }

    @Nonnull
    CompanionPopulationReconciliationService.Result reject(
            @Nonnull CompanionPopulationReconciliationService.Result result,
            @Nonnull CompanionPopulationReconciliationService.Status status,
            @Nonnull String reason
    ) {
        if (recoveryProofs != null) {
            recoveryProofs.invalidate(scanEpoch);
        }
        projections.degrade(scanEpoch, reason);
        CompanionPopulationReconciliationService.Status rejectedStatus =
                status == CompanionPopulationReconciliationService.Status.READY
                        ? CompanionPopulationReconciliationService.Status.DEGRADED
                        : status;
        return new CompanionPopulationReconciliationService.Result(
                rejectedStatus, reason, result.profileCount(), result.duplicateObservations(),
                result.recoveredOperations(), result.canceledOperations(), null, null, null
        );
    }

    @Nonnull
    private CompletableFuture<CompanionPopulationReconciliationService.Result> failure(
            @Nonnull CompanionPopulationReconciliationService.Result result,
            @Nonnull String reason
    ) {
        projections.degrade(scanEpoch, reason);
        final CompletableFuture<Boolean> publication;
        try {
            publication = coverage.publishBothAsync(
                    CompanionPopulationCoverageRecord.State.DEGRADED,
                    reason,
                    result.profileCount(),
                    1
            );
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(reject(
                    result,
                    CompanionPopulationReconciliationService.Status.DEGRADED,
                    "reconciliation-coverage-publish-failed"
            ));
        }
        if (publication == null) {
            return CompletableFuture.completedFuture(reject(
                    result,
                    CompanionPopulationReconciliationService.Status.DEGRADED,
                    "reconciliation-coverage-publish-failed"
            ));
        }
        return publication.handle((written, publishFailure) -> reject(
                result,
                CompanionPopulationReconciliationService.Status.DEGRADED,
                publishFailure == null && Boolean.TRUE.equals(written)
                        ? reason : "reconciliation-coverage-publish-failed"
        ));
    }

    @Nonnull
    private CompletableFuture<Boolean> invokeTransition(@Nonnull SessionTransition transition) {
        final CompletableFuture<Boolean> completion;
        try {
            completion = transition.apply(scanEpoch);
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(false);
        }
        if (completion == null) {
            return CompletableFuture.completedFuture(false);
        }
        return completion.handle((succeeded, failure) ->
                failure == null && Boolean.TRUE.equals(succeeded));
    }

    @Nonnull
    private static CompletableFuture<Boolean> committedValue(
            @Nonnull PersistenceWriteQueue.WriteSubmission<Boolean> submission
    ) {
        return submission.completion().thenApply(outcome -> outcome.isCommitted()
                && Boolean.TRUE.equals(outcome.value()));
    }

    @FunctionalInterface
    interface SessionTransition {
        @Nonnull
        CompletableFuture<Boolean> apply(@Nonnull String epoch);
    }
}
