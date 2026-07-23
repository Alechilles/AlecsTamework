package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryAction;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryClaim;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryIssue;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryScanResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import java.util.ArrayList;
import java.util.List;
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
        return scan(0, 0, new ArrayList<>());
    }

    private CompletionStage<SqlitePublicRecoveryResult> scan(
            int passCount,
            int completedCount,
            ArrayList<OperationScope> quarantined
    ) {
        if (passCount >= MAX_PASSES) {
            return completed(
                    SqlitePublicRecoveryResult.Status.PASS_LIMIT_REACHED,
                    passCount,
                    completedCount,
                    quarantined,
                    new IllegalStateException("recovery_pass_limit_reached")
            );
        }
        long now = clock.getAsLong();
        long leaseUntil;
        try {
            leaseUntil = Math.addExact(now, LEASE_DURATION_MS);
        } catch (ArithmeticException failure) {
            return completed(
                    SqlitePublicRecoveryResult.Status.SCAN_FAILED,
                    passCount,
                    completedCount,
                    quarantined,
                    failure
            );
        }
        return scanner.scanAndClaim(
                workerId,
                now,
                leaseUntil,
                BATCH_SIZE
        ).thenCompose(result -> continueScan(
                result,
                passCount,
                completedCount,
                quarantined
        ));
    }

    private CompletionStage<SqlitePublicRecoveryResult> continueScan(
            OperationRecoveryScanResult scan,
            int passCount,
            int completedCount,
            ArrayList<OperationScope> quarantined
    ) {
        if (scan.status() != OperationRecoveryScanResult.Status.COMPLETE) {
            return completed(
                    SqlitePublicRecoveryResult.Status.SCAN_FAILED,
                    passCount + 1,
                    completedCount,
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
                    quarantined,
                    null
            );
        }
        return dispatchClaims(
                scan.claims(),
                0,
                passCount + 1,
                completedCount,
                quarantined
        );
    }

    private CompletionStage<SqlitePublicRecoveryResult> dispatchClaims(
            List<OperationRecoveryClaim> claims,
            int index,
            int passCount,
            int completedCount,
            ArrayList<OperationScope> quarantined
    ) {
        if (index >= claims.size()) {
            return scan(passCount, completedCount, quarantined);
        }
        OperationRecoveryClaim claim = claims.get(index);
        if (claim.action() == OperationRecoveryAction.MANUAL_REVIEW) {
            return containManualReview(claim).thenCompose(result -> {
                if (!(result instanceof PersistenceTransactionResult.Committed<?>)) {
                    return completed(
                            SqlitePublicRecoveryResult.Status.DISPATCH_FAILED,
                            passCount,
                            completedCount,
                            quarantined,
                            transactionFailure(result)
                    );
                }
                quarantined.add(OperationScope.operation(
                        claim.operation().operationId()
                ));
                return dispatchClaims(
                        claims,
                        index + 1,
                        passCount,
                        completedCount,
                        quarantined
                );
            });
        }
        CompletionStage<OperationWorkflowResult> dispatched;
        try {
            dispatched = routes.dispatch(claim);
        } catch (Throwable failure) {
            return completed(
                    SqlitePublicRecoveryResult.Status.DISPATCH_FAILED,
                    passCount,
                    completedCount,
                    quarantined,
                    failure
            );
        }
        return dispatched.handle((result, failure) ->
                failure == null ? result : failedWorkflow(failure)
        ).thenCompose(result -> {
            if (result.status() != OperationWorkflowResult.Status.PUBLISHED
                    && result.status()
                    != OperationWorkflowResult.Status.COMPENSATED) {
                return completed(
                        unresolvedStatus(result),
                        passCount,
                        completedCount,
                        quarantined,
                        result.failure()
                );
            }
            return dispatchClaims(
                    claims,
                    index + 1,
                    passCount,
                    completedCount + 1,
                    quarantined
            );
        });
    }

    private CompletionStage<PersistenceTransactionResult<
            com.alechilles.alecstamework.persistence.incidents.IncidentRecord>>
    containManualReview(OperationRecoveryClaim claim) {
        return operations.engine().containUnknown(
                claim.operation(),
                "recovery_manual_review",
                "Recovery requires manual review of an ambiguous live outcome",
                List.of(OperationScope.operation(
                        claim.operation().operationId()
                )),
                clock.getAsLong()
        ).completion();
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
            List<OperationScope> quarantined,
            Throwable failure
    ) {
        return CompletableFuture.completedFuture(
                new SqlitePublicRecoveryResult(
                        status,
                        passCount,
                        completedCount,
                        quarantined,
                        failure
                )
        );
    }
}
