package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.AccessProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.MarkerAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.MutationAttempt;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.ReceiptPersistence;
import com.alechilles.alecstamework.companion.capture.runtime.CompanionCaptureTameWorldAttempt.TargetProbe;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Receipt-first asynchronous state machine for in-place tame-and-command-link capture.
 *
 * <p>Player source evidence is saved before spending, the spent source is saved before the NPC
 * marker, and the marker is saved before any tame mutation. Exact final NPC state is saved and
 * re-read before confirmation.</p>
 */
final class CompanionCaptureTameWorldExecutor {
    private static final int MAX_ROLE_POLLS = 20;
    private final ResolvedCaptureSourceWorldExecutor source =
            new ResolvedCaptureSourceWorldExecutor();
    private final CompanionCaptureTameWorldSafety safety =
            new CompanionCaptureTameWorldSafety();

    @Nonnull
    CompletionStage<LiveOperationResult> execute(
            @Nullable CompanionCaptureRequest request,
            @Nullable OperationEnvelope operation,
            @Nonnull AttemptGateway attempts
    ) {
        if (!valid(request, operation) || attempts == null) {
            return completed(unknown(
                    "operation_invariant_mismatch", null
            ));
        }
        ResolvedCaptureSourceWorldExecutor.SpendProbe spend =
                safety.spendProbe(attempts);
        TargetProbe target = safety.targetProbe(attempts);
        LiveOperationResult blocked = preflight(
                spend, target, attempts
        );
        if (blocked != null) {
            return completed(blocked);
        }
        return switch (spend.status()) {
            case SOURCE -> beginSourceReceipt(attempts);
            case RECEIPTED_SOURCE -> persistActorThen(
                    attempts,
                    "source_receipt_save_failed",
                    () -> spendAndPersist(attempts)
            );
            case SPENT -> persistActorThen(
                    attempts,
                    "source_spend_save_failed",
                    () -> beginTarget(attempts, MAX_ROLE_POLLS)
            );
            case ABSENT, CONFLICT -> completed(unknown(
                    "source_evidence_conflict", spend.cause()
            ));
        };
    }

    private CompletionStage<LiveOperationResult> beginSourceReceipt(
            AttemptGateway attempts
    ) {
        var receipt = safety.installSourceReceipt(attempts);
        if (receipt.status()
                != ResolvedCaptureSourceWorldExecutor.ReceiptStatus
                .RECEIPTED) {
            return completed(receipt.status()
                    == ResolvedCaptureSourceWorldExecutor.ReceiptStatus
                    .SOURCE_UNCHANGED
                    ? retryable(
                            "source_receipt_not_installed",
                            receipt.cause()
                    )
                    : unknown(
                            "source_receipt_ambiguous",
                            receipt.cause()
                    ));
        }
        return persistActorThen(
                attempts,
                "source_receipt_save_failed",
                () -> spendAndPersist(attempts)
        );
    }

    private CompletionStage<LiveOperationResult> spendAndPersist(
            AttemptGateway attempts
    ) {
        LiveOperationResult spent = source.execute(attempts);
        if (spent.status() != LiveOperationResult.Status.CONFIRMED) {
            return completed(spent);
        }
        return persistActorThen(
                attempts,
                "source_spend_save_failed",
                () -> requireSpentThenTarget(attempts)
        );
    }

    private CompletionStage<LiveOperationResult> requireSpentThenTarget(
            AttemptGateway attempts
    ) {
        var spend = safety.spendProbe(attempts);
        if (spend.status()
                != ResolvedCaptureSourceWorldExecutor.SpendStatus.SPENT) {
            return completed(unknown(
                    "source_spend_readback_conflict", spend.cause()
            ));
        }
        return beginTarget(attempts, MAX_ROLE_POLLS);
    }

    private CompletionStage<LiveOperationResult> beginTarget(
            AttemptGateway attempts,
            int pollsRemaining
    ) {
        TargetProbe target = safety.targetProbe(attempts);
        return switch (target.status()) {
            case TARGET -> saveFinalTarget(attempts, pollsRemaining);
            case APPLYING -> persistMarkerThenConverge(
                    attempts, pollsRemaining
            );
            case UNCHANGED -> beginMarker(
                    attempts, pollsRemaining
            );
            case ABSENT -> completed(unknown(
                    "target_absent", target.cause()
            ));
            case CONFLICT -> completed(unknown(
                    "target_identity_or_state_conflict", target.cause()
            ));
        };
    }

    private CompletionStage<LiveOperationResult> beginMarker(
            AttemptGateway attempts,
            int pollsRemaining
    ) {
        if (!safety.roleResolvable(attempts)) {
            return completed(compensate(
                    "target_role_permanently_unavailable", null
            ));
        }
        MarkerAttempt marker = safety.installMarker(attempts);
        return switch (marker.status()) {
            case EXACT -> persistMarkerThenConverge(
                    attempts, pollsRemaining
            );
            case RETRYABLE -> completed(retryable(
                    "target_marker_not_installed", marker.cause()
            ));
            case CONFLICT -> completed(unknown(
                    "target_marker_conflict", marker.cause()
            ));
        };
    }

    private CompletionStage<LiveOperationResult> persistMarkerThenConverge(
            AttemptGateway attempts,
            int pollsRemaining
    ) {
        return persistTargetThen(
                attempts,
                "target_marker_save_failed",
                () -> converge(attempts, pollsRemaining)
        );
    }

    private CompletionStage<LiveOperationResult> converge(
            AttemptGateway attempts,
            int pollsRemaining
    ) {
        TargetProbe before = safety.targetProbe(attempts);
        if (before.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.TARGET) {
            return saveFinalTarget(attempts, pollsRemaining);
        }
        if (before.status()
                != CompanionCaptureTameWorldAttempt.TargetStatus.APPLYING) {
            return completed(classifyTargetConflict(before));
        }
        if (!safety.roleResolvable(attempts)) {
            return completed(retryable(
                    "marked_target_role_unavailable", null
            ));
        }
        if (!before.rolePending()) {
            MutationAttempt mutation = safety.converge(attempts);
            if (mutation.status()
                    == CompanionCaptureTameWorldAttempt.MutationStatus
                    .CONFLICT) {
                return completed(unknown(
                        "target_mutation_conflict", mutation.cause()
                ));
            }
            if (mutation.status()
                    == CompanionCaptureTameWorldAttempt.MutationStatus
                    .RETRYABLE) {
                return completed(retryable(
                        "target_mutation_retryable", mutation.cause()
                ));
            }
        }
        TargetProbe after = safety.targetProbe(attempts);
        if (after.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.TARGET) {
            return saveFinalTarget(attempts, pollsRemaining);
        }
        if (after.status()
                != CompanionCaptureTameWorldAttempt.TargetStatus.APPLYING) {
            return completed(classifyTargetConflict(after));
        }
        if (pollsRemaining <= 0) {
            return completed(retryable(
                    "target_role_transition_pending", null
            ));
        }
        return safety.resumeAfterTick(
                attempts,
                () -> converge(attempts, pollsRemaining - 1),
                code("role_poll_schedule_failed")
        );
    }

    private CompletionStage<LiveOperationResult> saveFinalTarget(
            AttemptGateway attempts,
            int pollsRemaining
    ) {
        return persistTargetThen(
                attempts,
                "target_final_save_failed",
                () -> verifyFinalTarget(attempts, pollsRemaining)
        );
    }

    private CompletionStage<LiveOperationResult> verifyFinalTarget(
            AttemptGateway attempts,
            int pollsRemaining
    ) {
        TargetProbe target = safety.targetProbe(attempts);
        if (target.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.TARGET) {
            return completed(confirmed("target_saved_exact"));
        }
        if (target.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.APPLYING
                && pollsRemaining > 0) {
            return safety.resumeAfterTick(
                    attempts,
                    () -> converge(attempts, pollsRemaining - 1),
                    code("role_poll_schedule_failed")
            );
        }
        return completed(classifyTargetConflict(target));
    }

    private LiveOperationResult preflight(
            ResolvedCaptureSourceWorldExecutor.SpendProbe spend,
            TargetProbe target,
            AttemptGateway attempts
    ) {
        if (spend.status()
                == ResolvedCaptureSourceWorldExecutor.SpendStatus.CONFLICT
                || spend.status()
                == ResolvedCaptureSourceWorldExecutor.SpendStatus.ABSENT) {
            return unknown("source_evidence_conflict", spend.cause());
        }
        if (target.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.ABSENT) {
            return unknown("target_absent", target.cause());
        }
        if (target.status()
                == CompanionCaptureTameWorldAttempt.TargetStatus.CONFLICT) {
            return unknown(
                    "target_identity_or_state_conflict", target.cause()
            );
        }
        if (spend.status()
                != ResolvedCaptureSourceWorldExecutor.SpendStatus.SOURCE
                || target.status()
                != CompanionCaptureTameWorldAttempt.TargetStatus.UNCHANGED) {
            return null;
        }
        if (!safety.roleResolvable(attempts)) {
            return retryable("target_role_unavailable_before_spend", null);
        }
        AccessProbe access = safety.accessProbe(attempts);
        return switch (access.status()) {
            case PRESENT -> null;
            case MISSING -> retryable(
                    "command_access_item_missing", null
            );
            case CONFLICT -> unknown(
                    "command_access_evidence_conflict", access.cause()
            );
        };
    }

    private CompletionStage<LiveOperationResult> persistActorThen(
            AttemptGateway attempts,
            String failureCode,
            java.util.function.Supplier<
                    CompletionStage<LiveOperationResult>> continuation
    ) {
        return safety.persistActor(attempts).thenCompose(saved -> {
            LiveOperationResult failure = persistenceFailure(
                    saved, failureCode
            );
            return failure == null
                    ? safety.resume(
                            attempts,
                            continuation,
                            code("world_resume_failed")
                    )
                    : completed(failure);
        });
    }

    private CompletionStage<LiveOperationResult> persistTargetThen(
            AttemptGateway attempts,
            String failureCode,
            java.util.function.Supplier<
                    CompletionStage<LiveOperationResult>> continuation
    ) {
        return safety.persistTarget(attempts).thenCompose(saved -> {
            LiveOperationResult failure = persistenceFailure(
                    saved, failureCode
            );
            return failure == null
                    ? safety.resume(
                            attempts,
                            continuation,
                            code("world_resume_failed")
                    )
                    : completed(failure);
        });
    }

    private LiveOperationResult persistenceFailure(
            ReceiptPersistence persistence,
            String failureCode
    ) {
        if (persistence.status()
                == CompanionCaptureTameWorldAttempt.PersistenceStatus.SAVED) {
            return null;
        }
        return persistence.status()
                == CompanionCaptureTameWorldAttempt.PersistenceStatus.CONFLICT
                ? unknown(failureCode + "_conflict", persistence.cause())
                : retryable(failureCode, persistence.cause());
    }

    private LiveOperationResult classifyTargetConflict(TargetProbe target) {
        return switch (target.status()) {
            case ABSENT -> unknown("target_absent", target.cause());
            case CONFLICT, UNCHANGED -> unknown(
                    "target_identity_or_state_conflict", target.cause()
            );
            case APPLYING -> retryable(
                    "target_role_transition_pending", target.cause()
            );
            case TARGET -> confirmed("target_saved_exact");
        };
    }

    private boolean valid(
            CompanionCaptureRequest request,
            OperationEnvelope operation
    ) {
        return request != null
                && request.tameAndCommandLink()
                && operation != null
                && CompanionCaptureDefinition.KIND.equals(operation.kind())
                && request.expectedLifecycleRevision().equals(
                        operation.expectedLifecycleRevision()
                );
    }

    private LiveOperationResult confirmed(String suffix) {
        return LiveOperationResult.confirmed(code(suffix));
    }

    private LiveOperationResult compensate(
            String suffix,
            Throwable cause
    ) {
        return LiveOperationResult.compensate(code(suffix), cause);
    }

    private LiveOperationResult retryable(
            String suffix,
            Throwable cause
    ) {
        return LiveOperationResult.retryable(code(suffix), cause);
    }

    private LiveOperationResult unknown(
            String suffix,
            Throwable cause
    ) {
        return LiveOperationResult.unknown(code(suffix), cause);
    }

    private String code(String suffix) {
        return "capture_tame_link_" + suffix;
    }

    private CompletionStage<LiveOperationResult> completed(
            LiveOperationResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }

}
