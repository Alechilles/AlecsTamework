package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Claims and cancels public capabilities without holding record monitors across provider calls. */
final class PublicPopulationCapabilityLifecycle {
    private final CompanionPopulationAdmissionCoordinator coordinator;
    private final CompanionAdmissionPolicyResolver policyResolver;
    private final PopulationAdmissionDecisionMapper decisions;
    private final ClaimLookupMetrics lookupMetrics;

    PublicPopulationCapabilityLifecycle(CompanionPopulationAdmissionCoordinator coordinator,
                                        CompanionAdmissionPolicyResolver policyResolver,
                                        PopulationAdmissionDecisionMapper decisions,
                                        ClaimLookupMetrics lookupMetrics) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.policyResolver = Objects.requireNonNull(policyResolver, "policyResolver");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
    }

    PopulationAdmissionDecision claim(PublicPopulationAdmissionRecord record) {
        synchronized (record) {
            if (record.state() == PublicPopulationAdmissionRecord.State.APPLYING
                    || record.state() == PublicPopulationAdmissionRecord.State.COMMITTED) {
                return record.decision();
            }
            if (!record.transition(
                    PublicPopulationAdmissionRecord.State.RESERVED,
                    PublicPopulationAdmissionRecord.State.CLAIMING
            )) {
                return record.decision();
            }
        }

        boolean claimed;
        try {
            CompanionAdmissionPolicyResolver.Policy current = policyResolver.resolve(
                    PopulationAdmissionDecisionMapper.operation(record.request()),
                    claimPolicyRelevant(record)
            );
            claimed = coordinator.claimForApply(
                    record.prepared(), current.settingsRevision(), session(current)
            );
        } catch (RuntimeException | LinkageError failure) {
            return closeFailedClaim(record, "population-admission-claim-exception");
        }

        CompletableFuture<PopulationAdmissionDecision> cancellation = null;
        PopulationAdmissionDecision result;
        synchronized (record) {
            if (!claimed) {
                if (record.state() != PublicPopulationAdmissionRecord.State.CANCELING) {
                    record.update(
                            PublicPopulationAdmissionRecord.State.CANCELING,
                            record.decision()
                    );
                    record.completion(new CompletableFuture<>());
                }
                result = decisions.closed(
                        PopulationAdmissionDecision.Status.CANCELED,
                        "population-admission-context-or-reservation-invalid"
                );
                cancellation = record.completion();
            } else if (record.state() == PublicPopulationAdmissionRecord.State.CANCELING) {
                cancellation = record.completion();
                result = decisions.closed(
                        PopulationAdmissionDecision.Status.CANCELED,
                        "population-admission-cancel-requested-during-claim"
                );
            } else if (record.state() == PublicPopulationAdmissionRecord.State.CLAIMING) {
                result = decisions.accepted(
                        PopulationAdmissionDecision.Status.APPLYING,
                        "population-admission-claimed-for-apply",
                        record.token(),
                        record.prepared().ownerAdmission().decision()
                );
                record.update(PublicPopulationAdmissionRecord.State.APPLYING, result);
            } else {
                return record.decision();
            }
        }
        if (cancellation != null) {
            startCancellation(record, cancellation);
        }
        return result;
    }

    CompletionStage<PopulationAdmissionDecision> cancel(PublicPopulationAdmissionRecord record) {
        CompletableFuture<PopulationAdmissionDecision> future;
        boolean claiming;
        synchronized (record) {
            if (record.state() == PublicPopulationAdmissionRecord.State.CANCELING) {
                return record.completion();
            }
            if (record.state() == PublicPopulationAdmissionRecord.State.CANCELED
                    || record.state() == PublicPopulationAdmissionRecord.State.DEGRADED) {
                return CompletableFuture.completedFuture(record.decision());
            }
            if (record.state() == PublicPopulationAdmissionRecord.State.COMMITTED
                    || record.state() == PublicPopulationAdmissionRecord.State.COMMITTING) {
                return CompletableFuture.completedFuture(decisions.denied(
                        record.request(), "population-admission-already-committing-or-committed"
                ));
            }
            claiming = record.state() == PublicPopulationAdmissionRecord.State.CLAIMING;
            record.update(PublicPopulationAdmissionRecord.State.CANCELING, record.decision());
            future = new CompletableFuture<>();
            record.completion(future);
        }
        if (!claiming) {
            startCancellation(record, future);
        }
        return future;
    }

    private PopulationAdmissionDecision closeFailedClaim(PublicPopulationAdmissionRecord record,
                                                          String reason) {
        CompletableFuture<PopulationAdmissionDecision> future;
        synchronized (record) {
            if (record.state() != PublicPopulationAdmissionRecord.State.CANCELING) {
                record.update(PublicPopulationAdmissionRecord.State.CANCELING, record.decision());
                record.completion(new CompletableFuture<>());
            }
            future = record.completion();
        }
        startCancellation(record, future);
        return decisions.closed(PopulationAdmissionDecision.Status.CANCELED, reason);
    }

    private void startCancellation(PublicPopulationAdmissionRecord record,
                                   CompletableFuture<PopulationAdmissionDecision> future) {
        try {
            CompletionStage<Boolean> cancellation = coordinator.cancelAsync(
                    record.prepared(),
                    "population-admission-canceled"
            );
            if (cancellation == null) {
                finishCancel(record, future, false, null);
                return;
            }
            cancellation.whenComplete((canceled, failure) ->
                    finishCancel(record, future, canceled, failure)
            );
        } catch (RuntimeException | LinkageError failure) {
            finishCancel(record, future, false, failure);
        }
    }

    private void finishCancel(PublicPopulationAdmissionRecord record,
                              CompletableFuture<PopulationAdmissionDecision> future,
                              @Nullable Boolean canceled,
                              @Nullable Throwable failure) {
        boolean success = failure == null && Boolean.TRUE.equals(canceled);
        if (!success) {
            try {
                coordinator.markReadinessDegraded("public_population_cancel_failed");
            } catch (RuntimeException | LinkageError ignored) {
                // Preserve terminal capability completion even if readiness reporting fails.
            }
        }
        PopulationAdmissionDecision decision = decisions.closed(
                success ? PopulationAdmissionDecision.Status.CANCELED
                        : PopulationAdmissionDecision.Status.DEGRADED,
                success ? "population-admission-canceled" : "population-admission-cancel-failed"
        );
        synchronized (record) {
            record.update(success ? PublicPopulationAdmissionRecord.State.CANCELED
                    : PublicPopulationAdmissionRecord.State.DEGRADED, decision);
        }
        future.complete(decision);
    }

    private ClaimLookupSession session(CompanionAdmissionPolicyResolver.Policy policy) {
        return new ClaimLookupSession(
                policy.claimContext(), policy.claimLimitPerChunk() > 0, lookupMetrics
        );
    }

    private static boolean claimPolicyRelevant(PublicPopulationAdmissionRecord record) {
        return record.request().operation() == com.alechilles.alecstamework.api.PopulationAdmissionOperation.BREEDING
                || record.prepared().claimReservation().topologyCheckRequired()
                || record.prepared().claimReservation().reservedSlots() > 0L;
    }
}
