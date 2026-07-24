package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryAction;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryClaim;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryIssue;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryScanResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** Registry-routed recovery that re-enters the same typed operation adapters. */
final class SqlitePublicRecoveryDispatcher {
    private static final int BATCH_SIZE = 256;
    private static final int MAX_PASSES = 1_000;
    private static final long LEASE_DURATION_MS = 30_000;

    private final SqliteOperationRecoveryCoordinator scanner;
    private final PersistenceFeatureRegistry features;
    private final SqlitePublicOperationSet operations;
    private final SqlitePublicRecoveryRegistry routes;
    private final LongSupplier clock;
    private final String workerId;

    SqlitePublicRecoveryDispatcher(
            @Nonnull SqliteOperationRecoveryCoordinator scanner,
            @Nonnull PersistenceFeatureRegistry features,
            @Nonnull SqlitePublicOperationSet operations,
            @Nonnull PublicPersistenceLiveBoundaries boundaries,
            @Nonnull LongSupplier clock,
            @Nonnull String workerId
    ) {
        if (scanner == null || features == null || operations == null
                || boundaries == null
                || clock == null || workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Complete public recovery dependencies are required"
            );
        }
        this.scanner = scanner;
        this.features = features;
        this.operations = operations;
        this.routes = new SqlitePublicRecoveryRegistry(
                features,
                operations,
                boundaries
        );
        this.clock = clock;
        this.workerId = workerId.trim();
    }

    @Nonnull
    CompletionStage<SqlitePublicRecoveryResult> recover() {
        return scan(
                0,
                0,
                new LinkedHashSet<>(),
                new ArrayList<>(),
                clock.getAsLong()
        );
    }

    private CompletionStage<SqlitePublicRecoveryResult> scan(
            int passCount,
            int completedCount,
            LinkedHashSet<OperationId> deferred,
            ArrayList<OperationScope> quarantined,
            long recoveryNow
    ) {
        if (passCount >= MAX_PASSES) {
            return completed(
                    SqlitePublicRecoveryResult.Status.PASS_LIMIT_REACHED,
                    passCount,
                    completedCount,
                    deferred.size(),
                    quarantined,
                    new IllegalStateException("recovery_pass_limit_reached")
            );
        }
        long leaseUntil;
        try {
            leaseUntil = Math.addExact(recoveryNow, LEASE_DURATION_MS);
        } catch (ArithmeticException failure) {
            return completed(
                    SqlitePublicRecoveryResult.Status.SCAN_FAILED,
                    passCount,
                    completedCount,
                    deferred.size(),
                    quarantined,
                    failure
            );
        }
        return scanner.scanAndClaim(
                workerId,
                recoveryNow,
                leaseUntil,
                BATCH_SIZE,
                deferred
        ).thenCompose(result -> continueScan(
                result,
                passCount,
                completedCount,
                deferred,
                quarantined,
                recoveryNow
        ));
    }

    private CompletionStage<SqlitePublicRecoveryResult> continueScan(
            OperationRecoveryScanResult scan,
            int passCount,
            int completedCount,
            LinkedHashSet<OperationId> deferred,
            ArrayList<OperationScope> quarantined,
            long recoveryNow
    ) {
        if (scan.status() != OperationRecoveryScanResult.Status.COMPLETE) {
            return completed(
                    SqlitePublicRecoveryResult.Status.SCAN_FAILED,
                    passCount + 1,
                    completedCount,
                    deferred.size(),
                    quarantined,
                    new IllegalStateException(
                            scan.storageFailure().code(),
                            scan.storageFailure().cause()
                    )
            );
        }
        for (OperationRecoveryIssue issue : scan.issues()) {
            if (!issue.contained()) {
                return completed(
                        SqlitePublicRecoveryResult.Status.SCAN_FAILED,
                        passCount + 1,
                        completedCount,
                        deferred.size(),
                        quarantined,
                        issue.failure() == null
                                ? new IllegalStateException(issue.code())
                                : issue.failure()
                );
            }
            quarantined.add(OperationScope.operation(issue.operationId()));
        }
        if (scan.claims().isEmpty()) {
            return completed(
                    SqlitePublicRecoveryResult.Status.COMPLETE,
                    passCount + 1,
                    completedCount,
                    deferred.size(),
                    quarantined,
                    null
            );
        }
        return dispatchClaims(
                scan.claims(),
                0,
                new DispatchContext(
                        passCount + 1,
                        completedCount,
                        deferred,
                        quarantined,
                        recoveryNow
                )
        );
    }

    private CompletionStage<SqlitePublicRecoveryResult> dispatchClaims(
            List<OperationRecoveryClaim> claims,
            int index,
            DispatchContext context
    ) {
        if (index >= claims.size()) {
            return scan(
                    context.passCount(),
                    context.completedCount(),
                    context.deferred(),
                    context.quarantined(),
                    context.recoveryNow()
            );
        }
        if (claims.get(index).action()
                == OperationRecoveryAction.MANUAL_REVIEW) {
            return dispatchManualReview(
                    claims,
                    index,
                    context
            );
        }
        return dispatchRoutedClaim(
                claims,
                index,
                context
        );
    }

    private CompletionStage<SqlitePublicRecoveryResult>
    dispatchManualReview(
            List<OperationRecoveryClaim> claims,
            int index,
            DispatchContext context
    ) {
        OperationRecoveryClaim claim = claims.get(index);
        return containManualReview(claim).thenCompose(result -> {
            if (!(result instanceof PersistenceTransactionResult.Committed<?>)) {
                return completed(
                        SqlitePublicRecoveryResult.Status.DISPATCH_FAILED,
                        context.passCount(),
                        context.completedCount(),
                        context.deferred().size(),
                        context.quarantined(),
                        transactionFailure(result)
                );
            }
            context.quarantined().addAll(containmentScopes(claim));
            return dispatchClaims(
                    claims,
                    index + 1,
                    context
            );
        });
    }

    private CompletionStage<SqlitePublicRecoveryResult> dispatchRoutedClaim(
            List<OperationRecoveryClaim> claims,
            int index,
            DispatchContext context
    ) {
        OperationRecoveryClaim claim = claims.get(index);
        CompletionStage<OperationWorkflowResult> dispatched;
        try {
            dispatched = routes.dispatch(claim);
        } catch (Throwable failure) {
            return completed(
                    SqlitePublicRecoveryResult.Status.DISPATCH_FAILED,
                    context.passCount(),
                    context.completedCount(),
                    context.deferred().size(),
                    context.quarantined(),
                    failure
            );
        }
        return dispatched.handle((result, failure) ->
                failure == null ? result : failedWorkflow(failure)
        ).thenCompose(result -> continueRoutedClaim(
                claims,
                index,
                context,
                claim,
                result
        ));
    }

    private CompletionStage<SqlitePublicRecoveryResult>
    continueRoutedClaim(
            List<OperationRecoveryClaim> claims,
            int index,
            DispatchContext context,
            OperationRecoveryClaim claim,
            OperationWorkflowResult result
    ) {
        if (result.status()
                == OperationWorkflowResult.Status.LIVE_RETRYABLE) {
            return deferClaim(
                    claims,
                    index,
                    context,
                    claim
            );
        }
        if (result.status() != OperationWorkflowResult.Status.PUBLISHED
                && result.status()
                != OperationWorkflowResult.Status.COMPENSATED) {
            return completed(
                    unresolvedStatus(result),
                    context.passCount(),
                    context.completedCount(),
                    context.deferred().size(),
                    context.quarantined(),
                    result.failure()
            );
        }
        return dispatchClaims(
                claims,
                index + 1,
                context.completedOne()
        );
    }

    private CompletionStage<SqlitePublicRecoveryResult> deferClaim(
            List<OperationRecoveryClaim> claims,
            int index,
            DispatchContext context,
            OperationRecoveryClaim claim
    ) {
        if (!context.deferred().contains(claim.operation().operationId())
                && context.deferred().size()
                >= SqliteOperationStore.MAX_RECOVERY_EXCLUSIONS) {
            return completed(
                    SqlitePublicRecoveryResult.Status.PASS_LIMIT_REACHED,
                    context.passCount(),
                    context.completedCount(),
                    context.deferred().size(),
                    context.quarantined(),
                    new IllegalStateException(
                            "recovery_deferred_limit_reached"
                    )
            );
        }
        context.deferred().add(claim.operation().operationId());
        return dispatchClaims(
                claims,
                index + 1,
                context
        );
    }

    private CompletionStage<PersistenceTransactionResult<
            com.alechilles.alecstamework.persistence.incidents.IncidentRecord>>
    containManualReview(OperationRecoveryClaim claim) {
        return operations.engine().containUnknown(
                claim.operation(),
                "recovery_manual_review",
                "Recovery requires manual review of an ambiguous live outcome",
                containmentScopes(claim),
                clock.getAsLong()
        ).completion();
    }

    private List<OperationScope> containmentScopes(
            OperationRecoveryClaim claim
    ) {
        var descriptor = features.requireOperation(
                claim.operation().kind()
        );
        Set<OperationScopeType> allowed = new HashSet<>(
                descriptor.operationScopes().get(
                        claim.operation().kind()
                )
        );
        allowed.retainAll(descriptor.quarantineGranularity());
        allowed.add(OperationScopeType.OPERATION);
        return claim.operation().participants().stream()
                .filter(scope -> scope.type() != OperationScopeType.GLOBAL)
                .filter(scope -> allowed.contains(scope.type()))
                .toList();
    }

    private SqlitePublicRecoveryResult.Status unresolvedStatus(
            OperationWorkflowResult result
    ) {
        return result.status() == OperationWorkflowResult.Status.LIVE_RETRYABLE
                || result.status()
                == OperationWorkflowResult.Status.COMPENSATION_RETRYABLE
                ? SqlitePublicRecoveryResult.Status.UNRESOLVED
                : SqlitePublicRecoveryResult.Status.DISPATCH_FAILED;
    }

    private OperationWorkflowResult failedWorkflow(Throwable failure) {
        return new OperationWorkflowResult(
                OperationWorkflowResult.Status.PREPARE_FAILED,
                null,
                List.of(),
                failure
        );
    }

    private Throwable transactionFailure(
            PersistenceTransactionResult<?> result
    ) {
        if (result instanceof PersistenceTransactionResult.RolledBack<?> failed) {
            return failed.failure().cause();
        }
        if (result instanceof PersistenceTransactionResult.Unknown<?> unknown) {
            return unknown.failure().cause();
        }
        return new IllegalStateException("recovery_containment_not_committed");
    }

    private CompletionStage<SqlitePublicRecoveryResult> completed(
            SqlitePublicRecoveryResult.Status status,
            int passCount,
            int completedCount,
            int deferredCount,
            List<OperationScope> quarantined,
            Throwable failure
    ) {
        return CompletableFuture.completedFuture(
                new SqlitePublicRecoveryResult(
                        status,
                        passCount,
                        completedCount,
                        deferredCount,
                        quarantined,
                        failure
                )
        );
    }

    private record DispatchContext(
            int passCount,
            int completedCount,
            LinkedHashSet<OperationId> deferred,
            ArrayList<OperationScope> quarantined,
            long recoveryNow
    ) {
        private DispatchContext completedOne() {
            return new DispatchContext(
                    passCount,
                    completedCount + 1,
                    deferred,
                    quarantined,
                    recoveryNow
            );
        }
    }
}
