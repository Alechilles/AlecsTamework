package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.InventoryProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.InventoryStatus;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ProjectionStatus;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ReceiptPersistence;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ReceiptPersistenceStatus;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ReplacementAttempt;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure serial durability ordering for captured-artifact consumption and entity projection.
 *
 * <p>The player receipt is force-saved and read back on the exact world before projection may
 * begin. Only then is the exact projection applied and its target chunk force-saved. A final
 * exact-world readback of both receipts is required for confirmation, including on replay.</p>
 */
final class CompanionCaptureReleaseWorldExecutor {
    @Nonnull
    CompletionStage<LiveOperationResult> execute(
            @Nullable CompanionCaptureReleaseRequest request,
            @Nullable OperationEnvelope operation,
            @Nullable AttemptGateway attempts
    ) {
        if (!validOperation(request, operation) || attempts == null) {
            return completed(unknown("operation_invariant_mismatch", null));
        }
        InventoryProbe inventory = safeInventoryProbe(attempts);
        LiveOperationResult probeFailure = inventoryFailure(
                inventory, "inventory"
        );
        if (probeFailure != null) {
            return completed(probeFailure);
        }
        if (inventory.status() == InventoryStatus.SOURCE) {
            LiveOperationResult replacementFailure =
                    replacementFailure(attempts);
            if (replacementFailure != null) {
                return completed(replacementFailure);
            }
        }
        return persistActorReceipt(attempts);
    }

    private CompletionStage<LiveOperationResult> persistActorReceipt(
            AttemptGateway attempts
    ) {
        return normalize(safePersistence(attempts::persistActorReceipt))
                .thenCompose(result -> afterActorPersistence(
                        attempts, result
                ));
    }

    private CompletionStage<LiveOperationResult> afterActorPersistence(
            AttemptGateway attempts,
            ReceiptPersistence persistence
    ) {
        LiveOperationResult failure = persistenceFailure(
                persistence, "actor_receipt"
        );
        if (failure != null) {
            return completed(failure);
        }
        return resumeOnWorldThread(
                attempts,
                () -> projectAfterActorReadback(attempts)
        );
    }

    private CompletionStage<LiveOperationResult> projectAfterActorReadback(
            AttemptGateway attempts
    ) {
        InventoryProbe inventory = safeInventoryProbe(attempts);
        LiveOperationResult failure = exactInventoryReceiptFailure(inventory);
        if (failure != null) {
            return completed(failure);
        }

        LiveOperationResult projection = safeProject(attempts);
        if (projection.status() != LiveOperationResult.Status.CONFIRMED) {
            return completed(projection);
        }
        return persistTargetReceipt(attempts);
    }

    private CompletionStage<LiveOperationResult> persistTargetReceipt(
            AttemptGateway attempts
    ) {
        return normalize(
                safePersistence(attempts::persistTargetChunkReceipt)
        ).thenCompose(result -> afterTargetPersistence(attempts, result));
    }

    private CompletionStage<LiveOperationResult> afterTargetPersistence(
            AttemptGateway attempts,
            ReceiptPersistence persistence
    ) {
        LiveOperationResult failure = persistenceFailure(
                persistence, "target_receipt"
        );
        if (failure != null) {
            return completed(failure);
        }
        return resumeOnWorldThread(
                attempts,
                () -> verifyDurableReceipts(
                        attempts,
                        persistence.targetChunkIndex()
                )
        );
    }

    private CompletionStage<LiveOperationResult> resumeOnWorldThread(
            AttemptGateway attempts,
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        CompletionStage<LiveOperationResult> resumed;
        try {
            resumed = attempts.resumeOnWorldThread(continuation);
        } catch (RuntimeException | LinkageError failure) {
            return completed(retryable("world_resume_failed", failure));
        }
        if (resumed == null) {
            return completed(retryable("world_resume_missing", null));
        }
        CompletableFuture<LiveOperationResult> normalized =
                new CompletableFuture<>();
        resumed.whenComplete((result, failure) -> {
            if (failure != null) {
                normalized.complete(retryable(
                        "world_resume_failed", failure
                ));
            } else if (result == null) {
                normalized.complete(retryable(
                        "world_resume_missing", null
                ));
            } else {
                normalized.complete(result);
            }
        });
        return normalized;
    }

    private CompletionStage<LiveOperationResult> verifyDurableReceipts(
            AttemptGateway attempts,
            @Nullable Long targetChunkIndex
    ) {
        if (targetChunkIndex == null) {
            return completed(unknown(
                    "target_receipt_save_evidence_missing",
                    null
            ));
        }
        InventoryProbe inventory = safeInventoryProbe(attempts);
        LiveOperationResult inventoryFailure =
                exactInventoryReceiptFailure(inventory);
        if (inventoryFailure != null) {
            return completed(inventoryFailure);
        }

        ProjectionProbe projection = safeProjectionProbe(
                attempts,
                targetChunkIndex
        );
        if (projection.status() == ProjectionStatus.UNAVAILABLE) {
            return completed(retryable(
                    "projection_readback_unavailable", projection.cause()
            ));
        }
        if (projection.status() == ProjectionStatus.MOVED) {
            return completed(retryable(
                    "projection_chunk_changed",
                    projection.cause()
            ));
        }
        if (projection.status() != ProjectionStatus.EXACT) {
            return completed(unknown(
                    "projection_receipt_readback_conflict",
                    projection.cause()
            ));
        }
        return completed(LiveOperationResult.confirmed(
                code("durable_receipts_confirmed")
        ));
    }

    @Nullable
    private LiveOperationResult inventoryFailure(
            InventoryProbe inventory,
            String phase
    ) {
        if (inventory.status() == InventoryStatus.UNAVAILABLE) {
            return retryable(phase + "_unavailable", inventory.cause());
        }
        if (inventory.status() == InventoryStatus.CONFLICT) {
            return unknown(phase + "_conflict", inventory.cause());
        }
        return null;
    }

    @Nullable
    private LiveOperationResult exactInventoryReceiptFailure(
            InventoryProbe inventory
    ) {
        if (inventory.status() == InventoryStatus.UNAVAILABLE) {
            return retryable(
                    "inventory_readback_unavailable", inventory.cause()
            );
        }
        if (inventory.status() != InventoryStatus.RECEIPT) {
            return unknown(
                    "inventory_receipt_readback_conflict",
                    inventory.cause()
            );
        }
        return null;
    }

    @Nullable
    private LiveOperationResult replacementFailure(AttemptGateway attempts) {
        ReplacementAttempt replacement = safeReplace(attempts);
        return switch (replacement.status()) {
            case RECEIPT -> null;
            case SOURCE_UNCHANGED -> retryable(
                    "inventory_source_unchanged", replacement.cause()
            );
            case UNAVAILABLE -> retryable(
                    "inventory_unavailable", replacement.cause()
            );
            case AMBIGUOUS -> unknown(
                    "inventory_mutation_ambiguous", replacement.cause()
            );
        };
    }

    @Nullable
    private LiveOperationResult persistenceFailure(
            ReceiptPersistence persistence,
            String phase
    ) {
        if (persistence.status() == ReceiptPersistenceStatus.CONFLICT) {
            return unknown(phase + "_save_conflict", persistence.cause());
        }
        if (persistence.status() != ReceiptPersistenceStatus.SAVED) {
            return retryable(phase + "_save_failed", persistence.cause());
        }
        return null;
    }

    private CompletionStage<ReceiptPersistence> safePersistence(
            Supplier<CompletionStage<ReceiptPersistence>> save
    ) {
        try {
            CompletionStage<ReceiptPersistence> result = save.get();
            return result == null
                    ? completed(ReceiptPersistence.retryable(null))
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return completed(ReceiptPersistence.retryable(failure));
        }
    }

    private CompletableFuture<ReceiptPersistence> normalize(
            CompletionStage<ReceiptPersistence> persistence
    ) {
        CompletableFuture<ReceiptPersistence> normalized =
                new CompletableFuture<>();
        persistence.whenComplete((result, failure) -> {
            if (failure != null) {
                normalized.complete(ReceiptPersistence.retryable(failure));
            } else if (result == null || result.status() == null) {
                normalized.complete(ReceiptPersistence.retryable(null));
            } else {
                normalized.complete(result);
            }
        });
        return normalized;
    }

    private boolean validOperation(
            CompanionCaptureReleaseRequest request,
            OperationEnvelope operation
    ) {
        return request != null
                && operation != null
                && CompanionCaptureReleaseDefinition.KIND.equals(
                operation.kind()
        )
                && request.expectedLifecycleRevision().equals(
                operation.expectedLifecycleRevision()
        )
                && operation.participants().contains(
                OperationScope.profile(request.profileId())
        );
    }

    private InventoryProbe safeInventoryProbe(AttemptGateway attempts) {
        try {
            InventoryProbe result = attempts.probeInventory();
            return result == null || result.status() == null
                    ? InventoryProbe.conflict(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return InventoryProbe.conflict(failure);
        }
    }

    private ReplacementAttempt safeReplace(AttemptGateway attempts) {
        try {
            ReplacementAttempt result =
                    attempts.replaceSourceWithReceipt();
            return result == null || result.status() == null
                    ? ReplacementAttempt.ambiguous(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return ReplacementAttempt.ambiguous(failure);
        }
    }

    private LiveOperationResult safeProject(AttemptGateway attempts) {
        try {
            LiveOperationResult result = attempts.applyOrResolveProjection();
            return result == null
                    ? unknown("projection_result_missing", null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return unknown("projection_outcome_ambiguous", failure);
        }
    }

    private ProjectionProbe safeProjectionProbe(AttemptGateway attempts) {
        return safeProjectionProbe(attempts, null);
    }

    private ProjectionProbe safeProjectionProbe(
            AttemptGateway attempts,
            @Nullable Long expectedChunkIndex
    ) {
        try {
            ProjectionProbe result = expectedChunkIndex == null
                    ? attempts.probeProjectionReceipt()
                    : attempts.probeProjectionReceiptInChunk(
                            expectedChunkIndex
                    );
            return result == null || result.status() == null
                    ? ProjectionProbe.conflict(null)
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return ProjectionProbe.conflict(failure);
        }
    }

    private LiveOperationResult retryable(
            String suffix,
            @Nullable Throwable cause
    ) {
        return LiveOperationResult.retryable(code(suffix), cause);
    }

    private LiveOperationResult unknown(
            String suffix,
            @Nullable Throwable cause
    ) {
        return LiveOperationResult.unknown(code(suffix), cause);
    }

    private String code(String suffix) {
        return "capture_release_" + suffix;
    }

    private <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
