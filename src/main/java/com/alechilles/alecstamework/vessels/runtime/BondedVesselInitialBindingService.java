package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselBoundEvent;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.vessels.BondedVesselEventSink;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Creates the generation-one vessel authority after capture has durably committed its canonical
 * profile. The binding row is written before the exact empty source is replaced, so a crash can
 * only leave a recoverable APPLIED operation and can never bless an unjournaled filled item.
 */
public final class BondedVesselInitialBindingService {
    private final BondedVesselRepository repository;
    private final SourceFinalizer sourceFinalizer;
    private final BondedVesselEventSink events;
    private final Executor executor;
    private final LongSupplier clock;

    public BondedVesselInitialBindingService(
            @Nonnull BondedVesselRepository repository,
            @Nonnull SourceFinalizer sourceFinalizer,
            @Nullable BondedVesselEventSink events,
            @Nonnull Executor executor,
            @Nonnull LongSupplier clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sourceFinalizer = Objects.requireNonNull(sourceFinalizer, "sourceFinalizer");
        this.events = events == null ? BondedVesselEventSink.NO_OP : events;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Nonnull
    public CompletionStage<Result> bind(@Nonnull Request request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.supplyAsync(() -> findExisting(request), executor)
                .thenCompose(existing -> existing == null
                        ? create(request)
                        : continueExisting(request, existing, false))
                .exceptionally(failure -> Result.indeterminate(
                        "initial-binding-runtime-failure", request.bindingId(), request.profileId()));
    }

    /** Continues a bounded recovery row using its frozen source-finalization evidence. */
    @Nonnull
    public CompletionStage<Result> recover(@Nonnull Request request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.supplyAsync(() -> findExisting(request), executor)
                .thenCompose(existing -> existing == null
                        ? CompletableFuture.completedFuture(Result.indeterminate(
                                "initial-binding-operation-not-found", request.bindingId(),
                                request.profileId()))
                        : continueExisting(request, existing, true))
                .exceptionally(failure -> Result.indeterminate(
                        "initial-binding-recovery-failure", request.bindingId(), request.profileId()));
    }

    private CompletionStage<Result> create(Request request) {
        long now = nonNegativeNow();
        BondedVesselOperationRecord operation = operation(request, now);
        BondedVesselBindingRecord binding = binding(request, operation, now);
        PersistenceWriteQueue.WriteSubmission<BondedVesselRepository.MutationResult> submission;
        try {
            submission = repository.createInitialBindingAsync(binding, operation);
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(Result.indeterminate(
                    "initial-binding-write-rejected", request.bindingId(), request.profileId()));
        }
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.completedFuture(Result.indeterminate(
                    "initial-binding-write-unavailable", request.bindingId(), request.profileId()));
        }
        return submission.completion().thenCompose(outcome -> {
            if (outcome == null || !outcome.isCommitted() || outcome.value() == null) {
                return CompletableFuture.completedFuture(Result.indeterminate(
                        "initial-binding-write-unconfirmed", request.bindingId(), request.profileId()));
            }
            BondedVesselRepository.MutationResult mutation = outcome.value();
            if (mutation.status() != BondedVesselRepository.Status.APPLIED
                    && mutation.status() != BondedVesselRepository.Status.IDEMPOTENT) {
                return CompletableFuture.completedFuture(Result.denied(
                        mutation.reason() == null ? "initial-binding-conflict" : mutation.reason(),
                        request.bindingId(), request.profileId()));
            }
            BondedVesselOperationRecord persisted = mutation.operation() == null
                    ? operation : mutation.operation();
            return continueExisting(request, persisted, false);
        });
    }

    private CompletionStage<Result> continueExisting(
            Request request,
            BondedVesselOperationRecord operation,
            boolean recovered) {
        String mismatch = mismatch(request, operation);
        if (mismatch != null) {
            return CompletableFuture.completedFuture(Result.denied(
                    mismatch, request.bindingId(), request.profileId()));
        }
        if (operation.state() == BondedVesselOperationRecord.State.COMMITTED) {
            return CompletableFuture.completedFuture(Result.committed(
                    "initial-binding-already-committed", request.bindingId(), request.profileId()));
        }
        if (operation.state() == BondedVesselOperationRecord.State.QUARANTINED) {
            return CompletableFuture.completedFuture(Result.quarantined(
                    operation.reasonCode() == null ? "initial-binding-quarantined"
                            : operation.reasonCode(), request.bindingId(), request.profileId()));
        }
        if (operation.state() != BondedVesselOperationRecord.State.APPLIED) {
            return CompletableFuture.completedFuture(Result.indeterminate(
                    "initial-binding-operation-not-applied", request.bindingId(), request.profileId()));
        }
        CompletionStage<SourceFinalization> finalization;
        try {
            finalization = sourceFinalizer.finalizeSource(request);
        } catch (RuntimeException | LinkageError failure) {
            finalization = null;
        }
        if (finalization == null) {
            return CompletableFuture.completedFuture(Result.indeterminate(
                    "initial-binding-source-finalizer-unavailable",
                    request.bindingId(), request.profileId()));
        }
        return finalization.thenCompose(result -> finish(request, operation, result, recovered));
    }

    private CompletionStage<Result> finish(
            Request request,
            BondedVesselOperationRecord operation,
            @Nullable SourceFinalization finalization,
            boolean recovered) {
        if (finalization == null || finalization.status() == SourceStatus.INDETERMINATE) {
            return CompletableFuture.completedFuture(Result.indeterminate(
                    finalization == null ? "initial-binding-source-result-missing"
                            : finalization.reason(), request.bindingId(), request.profileId()));
        }
        if (finalization.status() == SourceStatus.SOURCE_CHANGED) {
            return quarantine(request, finalization.reason());
        }
        var submission = repository.commitAsync(operation.operationId(), nonNegativeNow());
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.completedFuture(Result.indeterminate(
                    "initial-binding-commit-unavailable", request.bindingId(), request.profileId()));
        }
        return submission.completion().thenApply(outcome -> {
            if (outcome == null || !outcome.isCommitted() || outcome.value() == null) {
                return Result.indeterminate("initial-binding-commit-unconfirmed",
                        request.bindingId(), request.profileId());
            }
            BondedVesselRepository.MutationResult mutation = outcome.value();
            if (mutation.status() != BondedVesselRepository.Status.COMMITTED
                    && mutation.status() != BondedVesselRepository.Status.IDEMPOTENT) {
                return Result.indeterminate(mutation.reason() == null
                                ? "initial-binding-commit-failed" : mutation.reason(),
                        request.bindingId(), request.profileId());
            }
            if (mutation.status() == BondedVesselRepository.Status.COMMITTED) {
                emitBound(request, operation, recovered);
            }
            return Result.committed("initial-binding-committed",
                    request.bindingId(), request.profileId());
        });
    }

    private CompletionStage<Result> quarantine(Request request, String reason) {
        var submission = repository.quarantineAsync(
                request.operationId().toString(), reason, nonNegativeNow());
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.completedFuture(Result.indeterminate(
                    "initial-binding-quarantine-unavailable",
                    request.bindingId(), request.profileId()));
        }
        return submission.completion().thenApply(outcome -> outcome != null
                && outcome.isCommitted() && outcome.value() != null
                && (outcome.value().status() == BondedVesselRepository.Status.QUARANTINED
                || outcome.value().status() == BondedVesselRepository.Status.IDEMPOTENT)
                ? Result.quarantined(reason, request.bindingId(), request.profileId())
                : Result.indeterminate("initial-binding-quarantine-unconfirmed",
                request.bindingId(), request.profileId()));
    }

    @Nullable
    private BondedVesselOperationRecord findExisting(Request request) {
        try {
            return repository.findOperationByCallerKey(
                    request.callerNamespace(), request.idempotencyKey());
        } catch (Exception failure) {
            throw new IllegalStateException("Initial binding operation lookup failed.", failure);
        }
    }

    private void emitBound(Request request, BondedVesselOperationRecord operation, boolean recovered) {
        try {
            events.emit(new BondedVesselBoundEvent(
                    request.operationId(), request.bindingId(), request.profileId(),
                    request.ownerUuid(), request.configId(), 1L, request.profileRevision(),
                    BondedVesselState.STORED, recovered, operation.appliedAtMs(), nonNegativeNow()));
        } catch (RuntimeException | LinkageError ignored) {
            // Notifications cannot change a committed binding.
        }
    }

    private static BondedVesselBindingRecord binding(
            Request request,
            BondedVesselOperationRecord operation,
            long now) {
        return new BondedVesselBindingRecord(
                request.bindingId().toString(), request.profileId(), 1L,
                request.configId(), request.configRevision(),
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                request.ownerUuid(), request.profileRevision(), null, null, 0L,
                request.targetItemId(), request.targetItemEvidenceJson(),
                operation.operationId(), null, 0L, now, now, 0L);
    }

    private static BondedVesselOperationRecord operation(Request request, long now) {
        return new BondedVesselOperationRecord(
                request.operationId().toString(), request.callerNamespace(),
                request.idempotencyKey(), request.correlationId(),
                request.bindingId().toString(), request.profileId(),
                BondedVesselOperationRecord.Action.INITIAL_BIND,
                BondedVesselOperationRecord.State.APPLIED, 0L, 1L,
                request.profileRevision(), request.configId(), request.configRevision(),
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.LifecycleState.STORING,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                0L, 0L, request.sourceItemId(), request.targetItemId(),
                request.sourceFingerprint(), request.targetFingerprint(),
                request.sourceContextJson(), request.policySnapshotJson(),
                request.populationOperationId(), null, "captured", "SOURCE_FINALIZATION_PENDING",
                0L, now, now, now, 0L);
    }

    @Nullable
    private static String mismatch(Request request, BondedVesselOperationRecord operation) {
        if (operation.action() != BondedVesselOperationRecord.Action.INITIAL_BIND) {
            return "initial-binding-idempotency-key-in-use";
        }
        if (!operation.operationId().equals(request.operationId().toString())
                || !operation.bindingId().equals(request.bindingId().toString())
                || !operation.profileId().equals(request.profileId())
                || !operation.configId().equals(request.configId())
                || operation.configRevision() != request.configRevision()
                || operation.expectedProfileRevision() != request.profileRevision()
                || !Objects.equals(operation.sourceFingerprint(), request.sourceFingerprint())
                || !Objects.equals(operation.replacementFingerprint(), request.targetFingerprint())) {
            return "initial-binding-request-does-not-match-journal";
        }
        return null;
    }

    private long nonNegativeNow() {
        return Math.max(0L, clock.getAsLong());
    }

    @FunctionalInterface
    public interface SourceFinalizer {
        @Nonnull
        CompletionStage<SourceFinalization> finalizeSource(@Nonnull Request request);
    }

    public enum SourceStatus { REPLACED, ALREADY_REPLACED, SOURCE_CHANGED, INDETERMINATE }

    public record SourceFinalization(@Nonnull SourceStatus status, @Nonnull String reason) {
        public SourceFinalization {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
        }
    }

    public record Request(
            @Nonnull UUID operationId,
            @Nonnull UUID bindingId,
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey,
            @Nullable String correlationId,
            @Nonnull String profileId,
            @Nonnull UUID ownerUuid,
            long profileRevision,
            @Nonnull String configId,
            long configRevision,
            @Nonnull String sourceItemId,
            @Nonnull String targetItemId,
            @Nonnull String sourceFingerprint,
            @Nonnull String targetFingerprint,
            @Nonnull String sourceContextJson,
            @Nonnull String targetItemEvidenceJson,
            @Nonnull String policySnapshotJson,
            @Nullable String populationOperationId) {
        public Request {
            operationId = Objects.requireNonNull(operationId, "operationId");
            bindingId = Objects.requireNonNull(bindingId, "bindingId");
            callerNamespace = requireText(callerNamespace, "callerNamespace");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
            correlationId = normalize(correlationId);
            profileId = requireText(profileId, "profileId");
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            configId = requireText(configId, "configId");
            sourceItemId = requireText(sourceItemId, "sourceItemId");
            targetItemId = requireText(targetItemId, "targetItemId");
            sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
            targetFingerprint = requireText(targetFingerprint, "targetFingerprint");
            sourceContextJson = requireText(sourceContextJson, "sourceContextJson");
            targetItemEvidenceJson = requireText(targetItemEvidenceJson, "targetItemEvidenceJson");
            policySnapshotJson = requireText(policySnapshotJson, "policySnapshotJson");
            populationOperationId = normalize(populationOperationId);
            if (profileRevision < 0L || configRevision < 0L) {
                throw new IllegalArgumentException("Initial binding revisions cannot be negative.");
            }
        }
    }

    public enum Status { COMMITTED, DENIED, QUARANTINED, INDETERMINATE }

    public record Result(@Nonnull Status status,
                         @Nonnull String reason,
                         @Nonnull UUID bindingId,
                         @Nonnull String profileId) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            bindingId = Objects.requireNonNull(bindingId, "bindingId");
            profileId = requireText(profileId, "profileId");
        }

        static Result committed(String reason, UUID bindingId, String profileId) {
            return new Result(Status.COMMITTED, reason, bindingId, profileId);
        }

        static Result denied(String reason, UUID bindingId, String profileId) {
            return new Result(Status.DENIED, reason, bindingId, profileId);
        }

        static Result quarantined(String reason, UUID bindingId, String profileId) {
            return new Result(Status.QUARANTINED, reason, bindingId, profileId);
        }

        static Result indeterminate(String reason, UUID bindingId, String profileId) {
            return new Result(Status.INDETERMINATE, reason, bindingId, profileId);
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
