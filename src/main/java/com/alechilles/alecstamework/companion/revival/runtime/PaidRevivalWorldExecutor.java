package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ActorPersistence;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ChargeAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.PersistenceStatus;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ProjectionAttempt;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.ReceiptInstall;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldAttempt.TargetPersistence;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.CompositeProbe;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.ReceiptStatus;
import com.alechilles.alecstamework.companion.revival.runtime.PaidRevivalWorldEvidence.SpawnStatus;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure receipt-first durability protocol for one paid revival.
 *
 * <p>This class coordinates live evidence only. The shared operation engine
 * retains the durable fence and owns prepare, commit, compensation, recovery,
 * and containment.</p>
 */
final class PaidRevivalWorldExecutor {
    private final PaidRevivalWorldSafety safety =
            new PaidRevivalWorldSafety();

    @Nonnull
    CompletionStage<PaidRevivalLiveResult> execute(
            @Nullable PaidRevivalRequest request,
            @Nullable OperationEnvelope operation,
            @Nullable AttemptGateway attempts
    ) {
        if (!validOperation(request, operation) || attempts == null) {
            return completed(unknown(
                    "operation_invariant_mismatch", null
            ));
        }
        CompositeProbe probe = safety.probe(attempts, null);
        PaidRevivalLiveResult failure = safety.probeFailure(request, probe);
        return failure == null
                ? dispatch(request, attempts, probe)
                : completed(failure);
    }

    private CompletionStage<PaidRevivalLiveResult> dispatch(
            PaidRevivalRequest request,
            AttemptGateway attempts,
            CompositeProbe probe
    ) {
        if (probe.spawn().status() == SpawnStatus.EXACT) {
            if (probe.receipt().status() != ReceiptStatus.EXACT
                    || !safety.chargeComplete(request, probe.charge())) {
                return completed(unknown(
                        "spawn_without_exact_charge_receipt",
                        safety.evidenceCause(probe)
                ));
            }
            return persistTarget(
                    request, attempts, probe.spawn().chunkIndex()
            );
        }
        if (probe.receipt().status() == ReceiptStatus.ABSENT) {
            return safety.uncharged(request, probe.charge())
                    ? installReceipt(request, attempts)
                    : completed(unknown(
                            "charge_without_inventory_receipt",
                            probe.charge().cause()
                    ));
        }
        if (safety.chargeComplete(request, probe.charge())) {
            return applyProjection(request, attempts);
        }
        if (safety.uncharged(request, probe.charge())) {
            return consumeRecipe(request, attempts);
        }
        return completed(unknown(
                "composite_evidence_conflict", safety.evidenceCause(probe)
        ));
    }

    private CompletionStage<PaidRevivalLiveResult> installReceipt(
            PaidRevivalRequest request,
            AttemptGateway attempts
    ) {
        ReceiptInstall install = safety.install(attempts);
        return switch (install.status()) {
            case EXACT -> persistActor(
                    attempts,
                    "receipt",
                    () -> afterReceiptPersistence(request, attempts)
            );
            case UNCHANGED -> completed(resolvePositiveNoCharge(
                    request,
                    safety.probe(attempts, null),
                    "receipt_install_unchanged"
            ));
            case CONFLICT -> completed(unknown(
                    "receipt_install_ambiguous", install.cause()
            ));
        };
    }

    private CompletionStage<PaidRevivalLiveResult> afterReceiptPersistence(
            PaidRevivalRequest request,
            AttemptGateway attempts
    ) {
        CompositeProbe probe = safety.probe(attempts, null);
        PaidRevivalLiveResult failure = safety.probeFailure(request, probe);
        if (failure != null) {
            return completed(failure);
        }
        if (probe.receipt().status() != ReceiptStatus.EXACT) {
            return completed(unknown(
                    "receipt_save_readback_conflict",
                    probe.receipt().cause()
            ));
        }
        return dispatch(request, attempts, probe);
    }

    private CompletionStage<PaidRevivalLiveResult> consumeRecipe(
            PaidRevivalRequest request,
            AttemptGateway attempts
    ) {
        ChargeAttempt charge = safety.charge(attempts);
        return switch (charge.status()) {
            case CHARGED -> persistActor(
                    attempts,
                    "charge",
                    () -> afterChargePersistence(request, attempts)
            );
            case UNCHANGED -> completed(resolvePositiveNoCharge(
                    request,
                    safety.probe(attempts, null),
                    "recipe_unchanged"
            ));
            case RETRYABLE -> completed(retryable(
                    "recipe_consumption_retryable", charge.cause()
            ));
            case PARTIAL, CONFLICT -> completed(unknown(
                    "recipe_consumption_ambiguous", charge.cause()
            ));
        };
    }

    private CompletionStage<PaidRevivalLiveResult> afterChargePersistence(
            PaidRevivalRequest request,
            AttemptGateway attempts
    ) {
        CompositeProbe probe = safety.probe(attempts, null);
        PaidRevivalLiveResult failure = safety.probeFailure(request, probe);
        if (failure != null) {
            return completed(failure);
        }
        if (probe.receipt().status() != ReceiptStatus.EXACT
                || !safety.chargeComplete(request, probe.charge())) {
            return completed(unknown(
                    "charge_save_readback_conflict",
                    safety.evidenceCause(probe)
            ));
        }
        if (probe.spawn().status() == SpawnStatus.EXACT) {
            return persistTarget(
                    request, attempts, probe.spawn().chunkIndex()
            );
        }
        return applyProjection(request, attempts);
    }

    private CompletionStage<PaidRevivalLiveResult> applyProjection(
            PaidRevivalRequest request,
            AttemptGateway attempts
    ) {
        ProjectionAttempt projection = safety.project(attempts);
        return switch (projection.status()) {
            case EXACT -> persistTarget(
                    request, attempts, projection.chunkIndex()
            );
            case TERMINAL_ABSENT -> completed(resolveTerminalAbsence(
                    request,
                    safety.probe(attempts, null),
                    projection.cause()
            ));
            case RETRYABLE -> completed(retryable(
                    "projection_retryable", projection.cause()
            ));
            case CONFLICT -> completed(unknown(
                    "projection_ambiguous", projection.cause()
            ));
        };
    }

    private CompletionStage<PaidRevivalLiveResult> persistActor(
            AttemptGateway attempts,
            String phase,
            Supplier<CompletionStage<PaidRevivalLiveResult>> continuation
    ) {
        return normalizeActor(safeActorPersistence(attempts))
                .thenCompose(persistence -> {
                    PaidRevivalLiveResult failure =
                            persistenceFailure(persistence, phase);
                    return failure == null
                            ? resume(attempts, continuation)
                            : completed(failure);
                });
    }

    private CompletionStage<PaidRevivalLiveResult> persistTarget(
            PaidRevivalRequest request,
            AttemptGateway attempts,
            @Nullable Long chunkIndex
    ) {
        if (chunkIndex == null) {
            return completed(unknown(
                    "target_chunk_evidence_missing", null
            ));
        }
        return normalizeTarget(safeTargetPersistence(
                attempts, chunkIndex
        )).thenCompose(persistence -> afterTargetPersistence(
                request, attempts, chunkIndex, persistence
        ));
    }

    private CompletionStage<PaidRevivalLiveResult> afterTargetPersistence(
            PaidRevivalRequest request,
            AttemptGateway attempts,
            long expectedChunk,
            TargetPersistence persistence
    ) {
        if (persistence.status() == PersistenceStatus.RETRYABLE) {
            return completed(retryable(
                    "target_save_failed", persistence.cause()
            ));
        }
        if (persistence.status() == PersistenceStatus.CONFLICT
                || !Objects.equals(
                persistence.chunkIndex(), expectedChunk
        )) {
            return completed(unknown(
                    "target_save_conflict", persistence.cause()
            ));
        }
        return resume(
                attempts,
                () -> verifyFinal(
                        request, attempts, expectedChunk
                )
        );
    }

    private CompletionStage<PaidRevivalLiveResult> verifyFinal(
            PaidRevivalRequest request,
            AttemptGateway attempts,
            long expectedChunk
    ) {
        CompositeProbe probe = safety.probe(attempts, expectedChunk);
        PaidRevivalLiveResult failure = safety.probeFailure(request, probe);
        if (failure != null) {
            return completed(failure);
        }
        boolean exact = probe.receipt().status() == ReceiptStatus.EXACT
                && safety.chargeComplete(request, probe.charge())
                && probe.spawn().status() == SpawnStatus.EXACT
                && Objects.equals(
                probe.spawn().chunkIndex(), expectedChunk
        );
        return completed(exact
                ? PaidRevivalLiveResult.confirmed(code(
                "durable_charge_and_spawn_receipts"
        ))
                : unknown(
                "final_readback_conflict", safety.evidenceCause(probe)
        ));
    }

    private PaidRevivalLiveResult resolvePositiveNoCharge(
            PaidRevivalRequest request,
            CompositeProbe probe,
            String suffix
    ) {
        PaidRevivalLiveResult failure = safety.probeFailure(request, probe);
        if (failure != null) {
            return failure;
        }
        if (probe.spawn().status() != SpawnStatus.ABSENT
                || !safety.uncharged(request, probe.charge())) {
            return unknown(
                    suffix + "_evidence_conflict",
                    safety.evidenceCause(probe)
            );
        }
        return PaidRevivalLiveResult.noCharge(code(suffix));
    }

    private PaidRevivalLiveResult resolveTerminalAbsence(
            PaidRevivalRequest request,
            CompositeProbe probe,
            @Nullable Throwable terminalCause
    ) {
        PaidRevivalLiveResult failure = safety.probeFailure(request, probe);
        if (failure != null) {
            return failure;
        }
        if (probe.receipt().status() != ReceiptStatus.EXACT
                || probe.spawn().status() != SpawnStatus.ABSENT) {
            return unknown(
                    "terminal_absence_evidence_conflict",
                    safety.first(
                            terminalCause, safety.evidenceCause(probe)
                    )
            );
        }
        if (request.exactCost().isEmpty()
                && safety.chargeComplete(request, probe.charge())) {
            return PaidRevivalLiveResult.noCharge(
                    code("terminal_spawn_absence_no_charge")
            );
        }
        if (!request.exactCost().isEmpty()
                && safety.chargeComplete(request, probe.charge())) {
            return PaidRevivalLiveResult.refundRequired(
                    request, code("terminal_spawn_absence_refund")
            );
        }
        return unknown(
                "terminal_absence_charge_conflict",
                safety.first(terminalCause, probe.charge().cause())
        );
    }

    private boolean validOperation(
            @Nullable PaidRevivalRequest request,
            @Nullable OperationEnvelope operation
    ) {
        return request != null
                && operation != null
                && PaidRevivalDefinition.KIND.equals(operation.kind())
                && PaidRevivalDefinition.INSTANCE.payloadVersion()
                == operation.payloadVersion()
                && request.groupAdmission().before().revision().equals(
                operation.expectedLifecycleRevision()
        )
                && operation.participants().contains(
                OperationScope.profile(request.sourceSnapshot().profileId())
        )
                && operation.participants().contains(
                OperationScope.owner(request.familyKey().ownerId())
        )
                && operation.participants().contains(
                OperationScope.commandFamily(request.familyKey())
        );
    }


    private CompletionStage<ActorPersistence> safeActorPersistence(
            AttemptGateway attempts
    ) {
        try {
            CompletionStage<ActorPersistence> result =
                    attempts.persistActor();
            return result == null
                    ? completed(ActorPersistence.retryable(null))
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return completed(ActorPersistence.retryable(failure));
        }
    }

    private CompletionStage<TargetPersistence> safeTargetPersistence(
            AttemptGateway attempts,
            long chunkIndex
    ) {
        try {
            CompletionStage<TargetPersistence> result =
                    attempts.persistTargetChunk(chunkIndex);
            return result == null
                    ? completed(TargetPersistence.retryable(null))
                    : result;
        } catch (RuntimeException | LinkageError failure) {
            return completed(TargetPersistence.retryable(failure));
        }
    }

    private CompletionStage<PaidRevivalLiveResult> resume(
            AttemptGateway attempts,
            Supplier<CompletionStage<PaidRevivalLiveResult>> continuation
    ) {
        CompletionStage<PaidRevivalLiveResult> resumed;
        try {
            resumed = attempts.resumeOnWorldThread(continuation);
        } catch (RuntimeException | LinkageError failure) {
            return completed(retryable("world_resume_failed", failure));
        }
        if (resumed == null) {
            return completed(retryable("world_resume_missing", null));
        }
        CompletableFuture<PaidRevivalLiveResult> normalized =
                new CompletableFuture<>();
        resumed.whenComplete((result, failure) -> normalized.complete(
                failure != null
                        ? retryable("world_resume_failed", failure)
                        : result == null
                        ? retryable("world_resume_missing", null)
                        : result
        ));
        return normalized;
    }

    private CompletableFuture<ActorPersistence> normalizeActor(
            CompletionStage<ActorPersistence> persistence
    ) {
        CompletableFuture<ActorPersistence> normalized =
                new CompletableFuture<>();
        persistence.whenComplete((result, failure) -> normalized.complete(
                failure != null
                        ? ActorPersistence.retryable(failure)
                        : result == null
                        ? ActorPersistence.retryable(null)
                        : result
        ));
        return normalized;
    }

    private CompletableFuture<TargetPersistence> normalizeTarget(
            CompletionStage<TargetPersistence> persistence
    ) {
        CompletableFuture<TargetPersistence> normalized =
                new CompletableFuture<>();
        persistence.whenComplete((result, failure) -> normalized.complete(
                failure != null
                        ? TargetPersistence.retryable(failure)
                        : result == null
                        ? TargetPersistence.retryable(null)
                        : result
        ));
        return normalized;
    }

    @Nullable
    private PaidRevivalLiveResult persistenceFailure(
            ActorPersistence persistence,
            String phase
    ) {
        if (persistence.status() == PersistenceStatus.CONFLICT) {
            return unknown(
                    phase + "_save_conflict", persistence.cause()
            );
        }
        return persistence.status() == PersistenceStatus.SAVED
                ? null
                : retryable(
                phase + "_save_failed", persistence.cause()
        );
    }

    private PaidRevivalLiveResult retryable(
            String suffix,
            @Nullable Throwable cause
    ) {
        return PaidRevivalLiveResult.retryable(code(suffix), cause);
    }

    private PaidRevivalLiveResult unknown(
            String suffix,
            @Nullable Throwable cause
    ) {
        return PaidRevivalLiveResult.unknown(code(suffix), cause);
    }

    private String code(String suffix) {
        return "paid_revival_" + suffix;
    }

    private <T> CompletableFuture<T> completed(T result) {
        return CompletableFuture.completedFuture(result);
    }
}
