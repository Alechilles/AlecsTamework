package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ArtifactMutation;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ArtifactMutationStatus;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ArtifactState;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.CompositeProbe;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ReceiptMutation;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ReceiptMutationStatus;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.ReceiptState;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.SaveResult;
import com.alechilles.alecstamework.companion.coop.runtime.CompanionCoopCapturedItemAttempt.SaveStatus;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.runtime.player.InventoryOperationReceipt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Receipt-first exact-CAS protocol for captured-item coop intake.
 *
 * <p>The protocol never treats source absence alone as success. The marked artifact is persisted
 * and read back with the exact generic receipt before the shared operation may commit the coop
 * transition.</p>
 */
final class CompanionCoopCapturedItemWorldExecutor {

    @Nonnull
    CompletionStage<LiveOperationResult> execute(
            @Nullable CompanionCoopCaptureRequest request,
            @Nullable OperationEnvelope operation,
            @Nullable CompanionCoopCapturedItemAttempt attempt
    ) {
        if (!validOperation(request, operation, false) || attempt == null) {
            return unknown("operation_invariant_mismatch", null).completed();
        }
        CoopCapturedItemSourceEvidence source =
                (CoopCapturedItemSourceEvidence) request.source();
        InventoryOperationReceipt receipt = expectedReceipt(
                source, operation
        );
        CompositeProbe probe = safeProbe(attempt, receipt, source);
        LiveOperationResult terminal = classifyApplyProbe(probe);
        if (terminal != null) {
            return terminal.completed();
        }
        if (probe.receiptState() == ReceiptState.ABSENT) {
            ReceiptMutation installed = safeReceiptMutation(
                    () -> attempt.installReceipt(receipt)
            );
            if (installed.status() == ReceiptMutationStatus.RETRYABLE) {
                return retryable(
                        "receipt_install_failed", installed.cause()
                ).completed();
            }
            if (installed.status() != ReceiptMutationStatus.EXACT) {
                return unknown(
                        "receipt_install_conflict", installed.cause()
                ).completed();
            }
        }
        return persistThenResume(
                attempt,
                "receipt_save",
                () -> continueAfterReceiptSave(
                        attempt, receipt, source
                )
        );
    }

    @Nonnull
    CompletionStage<LiveOperationResult> cleanupAfterDurableCommit(
            @Nullable CompanionCoopCaptureRequest request,
            @Nullable OperationEnvelope operation,
            @Nullable CompanionCoopCapturedItemAttempt attempt
    ) {
        if (!validOperation(request, operation, true) || attempt == null) {
            return unknown(
                    "cleanup_operation_invariant_mismatch", null
            ).completed();
        }
        CoopCapturedItemSourceEvidence source =
                (CoopCapturedItemSourceEvidence) request.source();
        InventoryOperationReceipt receipt = expectedReceipt(
                source, operation
        );
        CompositeProbe probe = safeProbe(attempt, receipt, source);
        if (unavailable(probe)) {
            return retryable(
                    "cleanup_probe_unavailable", probe.cause()
            ).completed();
        }
        if (conflict(probe)) {
            return unknown(
                    "cleanup_state_conflict", probe.cause()
            ).completed();
        }
        if (probe.receiptState() == ReceiptState.EXACT
                && probe.artifactState() == ArtifactState.MARKED) {
            ArtifactMutation retired = safeArtifactMutation(
                    () -> attempt.retireMarkedArtifact(source)
            );
            if (retired.status() == ArtifactMutationStatus.RETRYABLE
                    || retired.status()
                    == ArtifactMutationStatus.MARKED) {
                return retryable(
                        "cleanup_artifact_retirement_failed",
                        retired.cause()
                ).completed();
            }
            if (retired.status() != ArtifactMutationStatus.ABSENT) {
                return unknown(
                        "cleanup_artifact_retirement_conflict",
                        retired.cause()
                ).completed();
            }
            return persistThenResume(
                    attempt,
                    "cleanup_artifact_save",
                    () -> cleanupReceiptAfterArtifactSave(
                            attempt, receipt, source
                    )
            );
        }
        if (probe.artifactState() == ArtifactState.ABSENT
                && (probe.receiptState() == ReceiptState.EXACT
                || probe.receiptState() == ReceiptState.ABSENT)) {
            return persistThenResume(
                    attempt,
                    "cleanup_resolution_save",
                    () -> cleanupReceiptAfterArtifactSave(
                            attempt, receipt, source
                    )
            );
        }
        return unknown("cleanup_partial_state", probe.cause()).completed();
    }

    private CompletionStage<LiveOperationResult> continueAfterReceiptSave(
            CompanionCoopCapturedItemAttempt attempt,
            InventoryOperationReceipt receipt,
            CoopCapturedItemSourceEvidence source
    ) {
        CompositeProbe probe = safeProbe(attempt, receipt, source);
        if (unavailable(probe)) {
            return retryable(
                    "receipt_readback_unavailable", probe.cause()
            ).completed();
        }
        if (probe.receiptState() != ReceiptState.EXACT) {
            return unknown(
                    "receipt_readback_conflict", probe.cause()
            ).completed();
        }
        if (probe.artifactState() == ArtifactState.ABSENT
                || probe.artifactState() == ArtifactState.CONFLICT) {
            return unknown("inventory_partial_state", probe.cause()).completed();
        }
        if (probe.artifactState() == ArtifactState.UNAVAILABLE) {
            return retryable(
                    "inventory_readback_unavailable", probe.cause()
            ).completed();
        }
        if (probe.artifactState() == ArtifactState.SOURCE) {
            ArtifactMutation marked = safeArtifactMutation(
                    () -> attempt.markSource(source)
            );
            if (marked.status() == ArtifactMutationStatus.RETRYABLE) {
                return retryable(
                        "source_mark_failed", marked.cause()
                ).completed();
            }
            if (marked.status() != ArtifactMutationStatus.MARKED) {
                return unknown(
                        "source_mark_conflict", marked.cause()
                ).completed();
            }
        }
        return persistThenResume(
                attempt,
                "marked_artifact_save",
                () -> verifyMarkedArtifact(attempt, receipt, source)
        );
    }

    private CompletionStage<LiveOperationResult> verifyMarkedArtifact(
            CompanionCoopCapturedItemAttempt attempt,
            InventoryOperationReceipt receipt,
            CoopCapturedItemSourceEvidence source
    ) {
        CompositeProbe probe = safeProbe(attempt, receipt, source);
        if (unavailable(probe)) {
            return retryable(
                    "marked_artifact_readback_unavailable", probe.cause()
            ).completed();
        }
        if (probe.receiptState() == ReceiptState.EXACT
                && probe.artifactState() == ArtifactState.MARKED) {
            return confirmed("marked_artifact_durable").completed();
        }
        if (probe.receiptState() == ReceiptState.EXACT
                && probe.artifactState() == ArtifactState.SOURCE) {
            return retryable(
                    "marked_artifact_not_persisted", probe.cause()
            ).completed();
        }
        return unknown(
                "marked_artifact_readback_conflict", probe.cause()
        ).completed();
    }

    private CompletionStage<LiveOperationResult>
    cleanupReceiptAfterArtifactSave(
            CompanionCoopCapturedItemAttempt attempt,
            InventoryOperationReceipt receipt,
            CoopCapturedItemSourceEvidence source
    ) {
        CompositeProbe probe = safeProbe(attempt, receipt, source);
        if (unavailable(probe)) {
            return retryable(
                    "cleanup_artifact_readback_unavailable", probe.cause()
            ).completed();
        }
        if (probe.artifactState() != ArtifactState.ABSENT
                || conflict(probe)) {
            return unknown(
                    "cleanup_artifact_readback_conflict", probe.cause()
            ).completed();
        }
        if (probe.receiptState() == ReceiptState.EXACT) {
            ReceiptMutation removed = safeReceiptMutation(
                    () -> attempt.removeReceipt(receipt)
            );
            if (removed.status() == ReceiptMutationStatus.RETRYABLE) {
                return retryable(
                        "cleanup_receipt_removal_failed", removed.cause()
                ).completed();
            }
            if (removed.status() != ReceiptMutationStatus.EXACT) {
                return unknown(
                        "cleanup_receipt_removal_conflict", removed.cause()
                ).completed();
            }
        }
        return persistThenResume(
                attempt,
                "cleanup_receipt_save",
                () -> verifyCleanup(attempt, receipt, source)
        );
    }

    private CompletionStage<LiveOperationResult> verifyCleanup(
            CompanionCoopCapturedItemAttempt attempt,
            InventoryOperationReceipt receipt,
            CoopCapturedItemSourceEvidence source
    ) {
        CompositeProbe probe = safeProbe(attempt, receipt, source);
        if (unavailable(probe)) {
            return retryable(
                    "cleanup_readback_unavailable", probe.cause()
            ).completed();
        }
        if (probe.receiptState() == ReceiptState.ABSENT
                && probe.artifactState() == ArtifactState.ABSENT) {
            return confirmed("cleanup_resolved").completed();
        }
        return unknown("cleanup_readback_conflict", probe.cause()).completed();
    }

    private CompletionStage<LiveOperationResult> persistThenResume(
            CompanionCoopCapturedItemAttempt attempt,
            String code,
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        CompletionStage<SaveResult> save;
        try {
            save = attempt.persistActor();
        } catch (RuntimeException | LinkageError failure) {
            return retryable(code + "_failed", failure).completed();
        }
        if (save == null) {
            return retryable(code + "_missing", null).completed();
        }
        CompletableFuture<SaveResult> normalized = new CompletableFuture<>();
        save.whenComplete((result, failure) -> normalized.complete(
                failure == null ? result : SaveResult.retryable(failure)
        ));
        return normalized.thenCompose(result -> {
            if (result == null || result.status() == null) {
                return retryable(code + "_missing", null).completed();
            }
            if (result.status() == SaveStatus.RETRYABLE) {
                return retryable(code + "_failed", result.cause()).completed();
            }
            if (result.status() == SaveStatus.CONFLICT) {
                return unknown(code + "_conflict", result.cause()).completed();
            }
            return safeResume(attempt, continuation, code);
        });
    }

    private CompletionStage<LiveOperationResult> safeResume(
            CompanionCoopCapturedItemAttempt attempt,
            Supplier<CompletionStage<LiveOperationResult>> continuation,
            String code
    ) {
        CompletionStage<LiveOperationResult> resumed;
        try {
            resumed = attempt.resumeOnActorWorldThread(continuation);
        } catch (RuntimeException | LinkageError failure) {
            return retryable(code + "_resume_failed", failure).completed();
        }
        if (resumed == null) {
            return retryable(code + "_resume_missing", null).completed();
        }
        CompletableFuture<LiveOperationResult> normalized =
                new CompletableFuture<>();
        resumed.whenComplete((result, failure) -> {
            if (failure != null) {
                normalized.complete(retryable(
                        code + "_resume_failed", failure
                ));
            } else if (result == null) {
                normalized.complete(retryable(
                        code + "_resume_missing", null
                ));
            } else {
                normalized.complete(result);
            }
        });
        return normalized;
    }

    @Nullable
    private LiveOperationResult classifyApplyProbe(CompositeProbe probe) {
        if (unavailable(probe)) {
            return retryable("inventory_probe_unavailable", probe.cause());
        }
        if (conflict(probe)) {
            return unknown("inventory_state_conflict", probe.cause());
        }
        if (probe.receiptState() == ReceiptState.ABSENT
                && probe.artifactState() != ArtifactState.SOURCE) {
            return unknown(
                    "inventory_partial_state_without_receipt", probe.cause()
            );
        }
        if (probe.receiptState() == ReceiptState.EXACT
                && probe.artifactState() == ArtifactState.ABSENT) {
            return unknown("inventory_partial_state", probe.cause());
        }
        return null;
    }

    private boolean validOperation(
            CompanionCoopCaptureRequest request,
            OperationEnvelope operation,
            boolean requireDurable
    ) {
        if (request == null || operation == null
                || !(request.source()
                instanceof CoopCapturedItemSourceEvidence)
                || !CompanionCoopCaptureDefinition.KIND.equals(
                operation.kind()
        )
                || operation.payloadVersion()
                != CompanionCoopCaptureDefinition.INSTANCE.payloadVersion()
                || !CompanionCoopCaptureDefinition.INSTANCE.encode(request)
                .equals(operation.payloadJson())
                || !request.expectedLifecycleRevision().equals(
                operation.expectedLifecycleRevision()
        )
                || !operation.participants().contains(
                OperationScope.profile(request.profileId())
        )
                || !operation.participants().contains(
                OperationScope.coop(request.targetSlot().toString())
        )) {
            return false;
        }
        return !requireDurable
                || operation.phase() == OperationPhase.DURABLE;
    }

    private InventoryOperationReceipt expectedReceipt(
            CoopCapturedItemSourceEvidence source,
            OperationEnvelope operation
    ) {
        return new InventoryOperationReceipt(
                source.retirementReceiptKey(),
                operation.operationId(),
                operation.kind(),
                Sha256Hash.ofUtf8(operation.payloadJson()),
                operation.createdAtMs()
        );
    }

    private CompositeProbe safeProbe(
            CompanionCoopCapturedItemAttempt attempt,
            InventoryOperationReceipt receipt,
            CoopCapturedItemSourceEvidence source
    ) {
        try {
            CompositeProbe probe = attempt.probe(receipt, source);
            return probe == null || probe.receiptState() == null
                    || probe.artifactState() == null
                    ? CompositeProbe.of(
                    ReceiptState.CONFLICT, ArtifactState.CONFLICT
            ) : probe;
        } catch (RuntimeException | LinkageError failure) {
            return new CompositeProbe(
                    ReceiptState.CONFLICT,
                    ArtifactState.CONFLICT,
                    failure
            );
        }
    }

    private ReceiptMutation safeReceiptMutation(
            Supplier<ReceiptMutation> mutation
    ) {
        try {
            ReceiptMutation result = mutation.get();
            return result == null || result.status() == null
                    ? ReceiptMutation.conflict(null) : result;
        } catch (RuntimeException | LinkageError failure) {
            return ReceiptMutation.conflict(failure);
        }
    }

    private ArtifactMutation safeArtifactMutation(
            Supplier<ArtifactMutation> mutation
    ) {
        try {
            ArtifactMutation result = mutation.get();
            return result == null || result.status() == null
                    ? ArtifactMutation.conflict(null) : result;
        } catch (RuntimeException | LinkageError failure) {
            return ArtifactMutation.conflict(failure);
        }
    }

    private boolean unavailable(CompositeProbe probe) {
        return probe.receiptState() == ReceiptState.UNAVAILABLE
                || probe.artifactState() == ArtifactState.UNAVAILABLE;
    }

    private boolean conflict(CompositeProbe probe) {
        return probe.receiptState() == ReceiptState.CONFLICT
                || probe.artifactState() == ArtifactState.CONFLICT;
    }

    private LiveOperationResult confirmed(String suffix) {
        return LiveOperationResult.confirmed(code(suffix));
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
        return "coop_capture_item_" + suffix;
    }
}
