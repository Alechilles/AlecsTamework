package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceEvidence.CapturedItemSource;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.ItemRetirementReceipt;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Restart recovery for a captured item retired after slot claim but before capture finalization.
 *
 * <p>The caller supplies immutable operation/resident evidence from one coherent index epoch. This
 * service revalidates that exact pair, requires the receipt in the original player hotbar slot,
 * invokes the item-only finalizer, and cleans only the same receipt afterward.</p>
 */
public final class ManagedCoopItemCaptureRecoveryService {
    public enum RecoveryStatus {
        COMPLETED,
        DEDUPLICATED,
        WAITING,
        BLOCKED,
        FAILED
    }

    public enum ReceiptStatus {
        VERIFIED,
        WAITING,
        CONFLICT
    }

    public record Outcome(@Nonnull RecoveryStatus status,
                          @Nullable String detail) {
        public Outcome {
            Objects.requireNonNull(status, "status");
        }

        public boolean completed() {
            return status == RecoveryStatus.COMPLETED
                    || status == RecoveryStatus.DEDUPLICATED;
        }
    }

    public record ReceiptResolution(@Nonnull ReceiptStatus status,
                                    @Nullable ItemRetirementReceipt receipt,
                                    @Nullable String detail) {
        public ReceiptResolution {
            Objects.requireNonNull(status, "status");
        }

        @Nonnull
        public static ReceiptResolution verified(@Nonnull ItemRetirementReceipt receipt) {
            return new ReceiptResolution(ReceiptStatus.VERIFIED, receipt, null);
        }

        @Nonnull
        public static ReceiptResolution waiting(@Nonnull String detail) {
            return new ReceiptResolution(ReceiptStatus.WAITING, null, detail);
        }

        @Nonnull
        public static ReceiptResolution conflict(@Nonnull String detail) {
            return new ReceiptResolution(ReceiptStatus.CONFLICT, null, detail);
        }
    }

    private final ReceiptGateway receipts;
    private final FinalizationGateway finalization;
    private final ConcurrentHashMap<String, CompletableFuture<Outcome>> pending =
            new ConcurrentHashMap<>();

    public ManagedCoopItemCaptureRecoveryService(
            @Nonnull ManagedCoopItemCaptureFinalizer finalizer) {
        this(new HytaleManagedCoopItemReceiptGateway(), finalizer::complete);
    }

    ManagedCoopItemCaptureRecoveryService(@Nonnull ReceiptGateway receipts,
                                          @Nonnull FinalizationGateway finalization) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
    }

    /**
     * Recovers only exact item-tagged SOURCE_RETIRE_REQUESTED evidence. Entity-source or invalid
     * snapshots are blocked and must be routed to their own recovery path.
     */
    @Nonnull
    public CompletionStage<Outcome> recover(@Nonnull RetirementReady ready,
                                             @Nonnull ResidentRecord resident) {
        Evidence evidence = validateEvidence(ready, resident);
        if (!evidence.valid()) {
            return CompletableFuture.completedFuture(
                    new Outcome(RecoveryStatus.BLOCKED, evidence.detail()));
        }
        CompletableFuture<Outcome> completion = new CompletableFuture<>();
        CompletableFuture<Outcome> existing =
                pending.putIfAbsent(ready.operationId(), completion);
        if (existing != null) {
            return existing.thenApply(outcome -> new Outcome(
                    outcome.completed() ? RecoveryStatus.DEDUPLICATED : outcome.status(),
                    outcome.detail()));
        }
        startRecovery(ready, evidence.source(), completion);
        return completion;
    }

    private void startRecovery(RetirementReady ready,
                               CapturedItemSource source,
                               CompletableFuture<Outcome> completion) {
        final CompletionStage<ReceiptResolution> verification;
        try {
            verification = receipts.verify(ready, source);
        } catch (RuntimeException exception) {
            finish(ready, completion, failed("item_receipt_verify", exception));
            return;
        }
        if (verification == null) {
            finish(ready, completion,
                    new Outcome(RecoveryStatus.FAILED, "item_receipt_verify_missing"));
            return;
        }
        verification.thenCompose(result -> afterReceipt(ready, source, result))
                .handle((outcome, failure) -> failure == null
                        ? outcome : failed("item_capture_recovery", unwrap(failure)))
                .whenComplete((outcome, failure) -> finish(
                        ready,
                        completion,
                        failure == null ? outcome : failed("item_capture_recovery", failure)));
    }

    private CompletionStage<Outcome> afterReceipt(
            RetirementReady ready,
            CapturedItemSource source,
            @Nullable ReceiptResolution resolution) {
        if (resolution == null) {
            return completed(RecoveryStatus.FAILED, "item_receipt_resolution_missing");
        }
        if (resolution.status() == ReceiptStatus.WAITING) {
            return completed(RecoveryStatus.WAITING, resolution.detail());
        }
        if (resolution.status() != ReceiptStatus.VERIFIED
                || !matchesReceipt(ready, source, resolution.receipt())) {
            return completed(RecoveryStatus.BLOCKED,
                    fallback(resolution.detail(), "item_receipt_identity_conflict"));
        }
        ItemRetirementReceipt receipt = resolution.receipt();
        return completeCapture(ready).thenCompose(finalized -> {
            if (finalized == null || !finalized.completed()) {
                return completed(RecoveryStatus.FAILED,
                        finalized != null ? finalized.detail() : "item_finalization_missing");
            }
            return cleanup(receipt).handle((cleaned, failure) ->
                    new Outcome(
                            RecoveryStatus.COMPLETED,
                            failure == null && Boolean.TRUE.equals(cleaned)
                                    ? null : "item_receipt_cleanup_pending"));
        });
    }

    private CompletionStage<ManagedCoopItemCaptureFinalizer.Outcome> completeCapture(
            RetirementReady ready) {
        try {
            CompletionStage<ManagedCoopItemCaptureFinalizer.Outcome> result =
                    finalization.complete(ready);
            return result != null
                    ? result
                    : CompletableFuture.completedFuture(
                            new ManagedCoopItemCaptureFinalizer.Outcome(
                                    false, "item_finalization_missing"));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(
                    new ManagedCoopItemCaptureFinalizer.Outcome(
                            false, detail("item_finalization", exception)));
        }
    }

    private CompletionStage<Boolean> cleanup(ItemRetirementReceipt receipt) {
        try {
            CompletionStage<Boolean> result = receipt.cleanup().cleanup();
            return result != null ? result : CompletableFuture.completedFuture(false);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private Evidence validateEvidence(@Nullable RetirementReady ready,
                                      @Nullable ResidentRecord resident) {
        if (!readyShapeValid(ready) || resident == null) {
            return Evidence.invalid("item_recovery_evidence_missing");
        }
        if (!residentMatches(ready, resident)) {
            return Evidence.invalid("item_recovery_resident_identity_mismatch");
        }
        if (!snapshotHashValid(resident)) {
            return Evidence.invalid("item_recovery_snapshot_hash_invalid");
        }
        ManagedCoopCaptureSourceEvidence.ReadResult source =
                ManagedCoopCaptureSourceEvidence.read(resident.snapshotJson());
        if (source.status() != ManagedCoopCaptureSourceEvidence.Status.CAPTURED_ITEM
                || source.capturedItem() == null) {
            return Evidence.invalid(source.status()
                    == ManagedCoopCaptureSourceEvidence.Status.INVALID
                    ? "item_recovery_source_marker_invalid"
                    : "item_recovery_source_is_not_captured_item");
        }
        return Evidence.valid(source.capturedItem());
    }

    private boolean readyShapeValid(@Nullable RetirementReady ready) {
        return ready != null
                && ready.durableState() == OperationState.SOURCE_RETIRE_REQUESTED
                && ready.sourceNpcUuid() != null
                && ready.profileId() != null && !ready.profileId().isBlank()
                && ready.operationId() != null && !ready.operationId().isBlank()
                && ready.authorityKey() != null
                && ready.coopId() != null && !ready.coopId().isBlank()
                && ready.residentSlot() >= 0
                && ready.snapshotHash() != null && !ready.snapshotHash().isBlank()
                && ready.operationGeneration() >= 0L;
    }

    private boolean residentMatches(RetirementReady ready, ResidentRecord resident) {
        return resident.active()
                && resident.state() == ResidentState.HOUSED
                && resident.residentId().equals(ready.residentId())
                && resident.profileId().equals(ready.profileId())
                && resident.authorityKey().equals(ready.authorityKey())
                && resident.coopId().equals(ready.coopId())
                && resident.residentSlot() == ready.residentSlot()
                && resident.residentUuid().equals(ready.sourceNpcUuid())
                && Objects.equals(resident.sourceNpcUuid(), ready.sourceNpcUuid())
                && resident.deployedNpcUuid() == null
                && Objects.equals(resident.snapshotHash(), ready.snapshotHash());
    }

    private boolean snapshotHashValid(ResidentRecord resident) {
        if (resident.snapshotJson() == null || resident.snapshotHash() == null) {
            return false;
        }
        try {
            return resident.snapshotHash().equals(
                    ManagedCoopCaptureClaimValidator.snapshotSha256(resident.snapshotJson()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean matchesReceipt(RetirementReady ready,
                                   CapturedItemSource source,
                                   @Nullable ItemRetirementReceipt receipt) {
        return receipt != null
                && ready.operationId().equals(receipt.operationId())
                && source.itemFingerprint().equals(receipt.itemFingerprint());
    }

    private void finish(RetirementReady ready,
                        CompletableFuture<Outcome> completion,
                        @Nullable Outcome outcome) {
        pending.remove(ready.operationId(), completion);
        completion.complete(outcome != null
                ? outcome : new Outcome(RecoveryStatus.FAILED, "item_recovery_outcome_missing"));
    }

    private CompletionStage<Outcome> completed(RecoveryStatus status, String detail) {
        return CompletableFuture.completedFuture(new Outcome(status, detail));
    }

    private Outcome failed(String stage, Throwable failure) {
        return new Outcome(RecoveryStatus.FAILED, detail(stage, failure));
    }

    @Nonnull
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static String detail(String stage, Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return stage + "_failed:" + (message == null || message.isBlank()
                ? failure == null ? "unknown" : failure.getClass().getSimpleName()
                : message);
    }

    private static String fallback(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @FunctionalInterface
    interface ReceiptGateway {
        @Nonnull
        CompletionStage<ReceiptResolution> verify(
                @Nonnull RetirementReady ready,
                @Nonnull CapturedItemSource source);
    }

    @FunctionalInterface
    interface FinalizationGateway {
        @Nonnull
        CompletionStage<ManagedCoopItemCaptureFinalizer.Outcome> complete(
                @Nonnull RetirementReady ready);
    }

    private record Evidence(@Nullable CapturedItemSource source, @Nullable String detail) {
        static Evidence valid(CapturedItemSource source) {
            return new Evidence(source, null);
        }

        static Evidence invalid(String detail) {
            return new Evidence(null, detail);
        }

        boolean valid() {
            return source != null && detail == null;
        }
    }
}
