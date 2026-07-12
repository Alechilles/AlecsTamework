package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Advances public population capabilities through commit and terminal result publication.
 *
 * <p>The record monitor protects only capability state. Durable population work and identity
 * cache updates run after the monitor is released so capability maintenance can continue to
 * inspect and quarantine stale work without blocking on persistence.
 */
final class PublicPopulationCommitCoordinator {
    private final CompanionIdentityResolver identityResolver;
    private final CompanionPopulationAdmissionCoordinator coordinator;
    private final PopulationAdmissionDecisionMapper decisions;

    PublicPopulationCommitCoordinator(
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull CompanionPopulationAdmissionCoordinator coordinator,
            @Nonnull PopulationAdmissionDecisionMapper decisions
    ) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    @Nonnull
    CompletionStage<PopulationAdmissionDecision> commit(
            @Nonnull PublicPopulationAdmissionRecord record
    ) {
        Objects.requireNonNull(record, "record");
        CompletableFuture<PopulationAdmissionDecision> future;
        synchronized (record) {
            if (record.state() == PublicPopulationAdmissionRecord.State.COMMITTING) {
                return record.completion();
            }
            if (record.state() != PublicPopulationAdmissionRecord.State.APPLYING) {
                return CompletableFuture.completedFuture(terminalOrDenied(record));
            }
            record.transition(
                    PublicPopulationAdmissionRecord.State.APPLYING,
                    PublicPopulationAdmissionRecord.State.COMMITTING
            );
            future = new CompletableFuture<>();
            record.completion(future);
        }
        startCommit(record, future);
        return future;
    }

    @Nonnull
    private PopulationAdmissionDecision terminalOrDenied(
            @Nonnull PublicPopulationAdmissionRecord record
    ) {
        return record.state() == PublicPopulationAdmissionRecord.State.COMMITTED
                || record.state() == PublicPopulationAdmissionRecord.State.DEGRADED
                || record.state() == PublicPopulationAdmissionRecord.State.CANCELED
                ? record.decision()
                : decisions.denied(record.request(), "population-admission-not-applying");
    }

    private void startCommit(
            @Nonnull PublicPopulationAdmissionRecord record,
            @Nonnull CompletableFuture<PopulationAdmissionDecision> future
    ) {
        try {
            CompletionStage<CompanionPopulationCommitResult> commit = coordinator.commitAsync(
                    record.prepared()
            );
            if (commit == null) {
                finishSafely(record, future, null, new IllegalStateException(
                        "Population commit returned no completion stage."
                ));
                return;
            }
            commit.whenComplete((result, failure) ->
                    finishSafely(record, future, result, failure)
            );
        } catch (RuntimeException | LinkageError failure) {
            finishSafely(record, future, null, failure);
        }
    }

    private void finish(
            @Nonnull PublicPopulationAdmissionRecord record,
            @Nonnull CompletableFuture<PopulationAdmissionDecision> future,
            @Nullable CompanionPopulationCommitResult result,
            @Nullable Throwable failure
    ) {
        PopulationAdmissionDecision decision;
        PublicPopulationAdmissionRecord.State state;
        boolean ownerCommitted = result != null && result.ownerCommit() != null
                && result.ownerCommit().committed();
        if (failure == null && result != null && (result.committed() || ownerCommitted)) {
            try {
                identityResolver.markDurable(record.profileId(), record.currentNpcUuid());
                if (result.committed()) {
                    decision = decisions.accepted(
                            PopulationAdmissionDecision.Status.COMMITTED,
                            result.reason(),
                            record.token(),
                            record.prepared().ownerAdmission().decision()
                    );
                    state = PublicPopulationAdmissionRecord.State.COMMITTED;
                } else {
                    decision = decisions.closed(
                            PopulationAdmissionDecision.Status.DEGRADED,
                            result.reason()
                    );
                    state = PublicPopulationAdmissionRecord.State.DEGRADED;
                }
            } catch (RuntimeException | LinkageError exception) {
                decision = decisions.closed(
                        PopulationAdmissionDecision.Status.DEGRADED,
                        "population-admission-identity-cache-degraded"
                );
                state = PublicPopulationAdmissionRecord.State.DEGRADED;
            }
        } else {
            markReadinessDegraded("public_population_commit_failed");
            decision = decisions.closed(
                    PopulationAdmissionDecision.Status.DEGRADED,
                    result == null ? "population-admission-commit-failed" : result.reason()
            );
            state = PublicPopulationAdmissionRecord.State.DEGRADED;
        }
        synchronized (record) {
            record.update(state, decision);
        }
        future.complete(decision);
    }

    private void finishSafely(
            @Nonnull PublicPopulationAdmissionRecord record,
            @Nonnull CompletableFuture<PopulationAdmissionDecision> future,
            @Nullable CompanionPopulationCommitResult result,
            @Nullable Throwable failure
    ) {
        try {
            finish(record, future, result, failure);
        } catch (RuntimeException | LinkageError completionFailure) {
            markReadinessDegraded("public_population_commit_callback_failed");
            PopulationAdmissionDecision degraded = decisions.closed(
                    PopulationAdmissionDecision.Status.DEGRADED,
                    "population-admission-commit-callback-failed"
            );
            synchronized (record) {
                record.update(PublicPopulationAdmissionRecord.State.DEGRADED, degraded);
            }
            future.complete(degraded);
        }
    }

    private void markReadinessDegraded(@Nonnull String reason) {
        try {
            coordinator.markReadinessDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // Preserve terminal completion if readiness diagnostics also fail.
        }
    }
}
