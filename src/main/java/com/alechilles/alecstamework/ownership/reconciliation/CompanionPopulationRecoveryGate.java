package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/**
 * Decides whether recovered startup journals and their evidence may advance to canonical merge.
 */
final class CompanionPopulationRecoveryGate {
    private final CompanionPopulationAmbiguityContainment containment;
    private final CompanionPopulationCoveragePublisher coveragePublisher;

    CompanionPopulationRecoveryGate(
            @Nonnull CompanionPopulationAmbiguityContainment containment,
            @Nonnull CompanionPopulationCoveragePublisher coveragePublisher
    ) {
        this.containment = Objects.requireNonNull(containment, "containment");
        this.coveragePublisher = Objects.requireNonNull(coveragePublisher, "coveragePublisher");
    }

    /** Durably contains bounded ambiguity, then validates the evidence merge gate. */
    @Nonnull
    CompletableFuture<Decision> evaluateAsync(
            @Nonnull CompanionPopulationEvidenceSet evidenceSet,
            @Nonnull CompanionPopulationOperationRecoveryService.RecoveryResult recovery
    ) {
        Objects.requireNonNull(evidenceSet, "evidenceSet");
        Objects.requireNonNull(recovery, "recovery");
        if (recovery.complete()) {
            return evaluateEvidence(evidenceSet);
        }
        if (!containment.enabled()) {
            return publishOperationAmbiguity(evidenceSet, recovery);
        }
        return containment.containAsync(recovery.ambiguous()).thenCompose(contained -> contained
                ? evaluateEvidence(evidenceSet)
                : publishOperationAmbiguity(evidenceSet, recovery));
    }

    /** Requires the remaining journals to be exactly the ambiguity set just durably fenced. */
    boolean matchesDurableAmbiguities(
            @Nonnull List<CompanionPopulationOperationRecord> remaining,
            @Nonnull CompanionPopulationOperationRecoveryService.RecoveryResult recovery
    ) {
        if (!containment.enabled() || recovery.ambiguous().size() != remaining.size()) {
            return false;
        }
        Set<String> containedIds = recovery.ambiguous().stream()
                .map(CompanionPopulationOperationRecoveryService.AmbiguousOperation::operationId)
                .collect(Collectors.toUnmodifiableSet());
        return remaining.stream().allMatch(operation -> containedIds.contains(operation.operationId()));
    }

    @Nonnull
    private CompletableFuture<Decision> evaluateEvidence(
            @Nonnull CompanionPopulationEvidenceSet evidenceSet
    ) {
        if (evidenceSet.isConflictFree()) {
            return CompletableFuture.completedFuture(Decision.permit(evidenceSet, 0));
        }
        if (containment.evidenceEnabled()) {
            return containment.containEvidenceAsync(evidenceSet.conflicts())
                    .thenCompose(result -> evaluateContainedEvidence(evidenceSet, result));
        }
        return publishEvidenceConflict(evidenceSet);
    }

    @Nonnull
    private CompletableFuture<Decision> evaluateContainedEvidence(
            @Nonnull CompanionPopulationEvidenceSet evidenceSet,
            @Nonnull CompanionPopulationAmbiguityContainment.EvidenceContainmentResult result
    ) {
        if (!result.complete()) {
            return publishEvidenceConflict(evidenceSet);
        }
        CompanionPopulationEvidenceSet repairEvidence;
        try {
            repairEvidence = evidenceSet.excludingConflictUuids(result.containedNpcUuids());
        } catch (RuntimeException failure) {
            return publishEvidenceConflict(evidenceSet);
        }
        return repairEvidence.isConflictFree()
                ? CompletableFuture.completedFuture(Decision.permit(
                        repairEvidence, result.containedProfileCount()))
                : publishEvidenceConflict(evidenceSet);
    }

    @Nonnull
    private CompletableFuture<Decision> publishEvidenceConflict(
            @Nonnull CompanionPopulationEvidenceSet evidenceSet
    ) {
        return coveragePublisher.publishBothAsync(
                CompanionPopulationCoverageRecord.State.DEGRADED,
                "reconciliation-evidence-conflict",
                evidenceSet.evidence().size(),
                evidenceSet.conflicts().size()
        ).thenApply(written -> Decision.stop(evidenceSet, written
                ? "reconciliation-evidence-conflict"
                : "reconciliation-coverage-publish-failed"));
    }

    @Nonnull
    private CompletableFuture<Decision> publishOperationAmbiguity(
            @Nonnull CompanionPopulationEvidenceSet evidenceSet,
            @Nonnull CompanionPopulationOperationRecoveryService.RecoveryResult recovery
    ) {
        return coveragePublisher.publishBothAsync(
                CompanionPopulationCoverageRecord.State.DEGRADED,
                "reconciliation-operation-ambiguous",
                evidenceSet.evidence().size(),
                recovery.ambiguous().size()
        ).thenApply(written -> Decision.stop(evidenceSet, written
                ? "reconciliation-operation-ambiguous"
                : "reconciliation-coverage-publish-failed"));
    }

    record Decision(boolean mayProceed,
                    @Nonnull String reason,
                    @Nonnull CompanionPopulationEvidenceSet repairEvidenceSet,
                    int containedProfileCount) {
        @Nonnull
        private static Decision permit(@Nonnull CompanionPopulationEvidenceSet evidenceSet,
                                       int containedProfileCount) {
            return new Decision(true, "reconciliation-recovery-gate-passed",
                    evidenceSet, containedProfileCount);
        }

        @Nonnull
        private static Decision stop(@Nonnull CompanionPopulationEvidenceSet evidenceSet,
                                     @Nonnull String reason) {
            return new Decision(false, reason, evidenceSet, 0);
        }
    }
}
