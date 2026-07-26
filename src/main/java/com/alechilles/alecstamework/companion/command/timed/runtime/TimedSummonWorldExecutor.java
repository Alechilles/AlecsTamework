package com.alechilles.alecstamework.companion.command.timed.runtime;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.AttemptGateway;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.ChunkPersistence;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.EvidenceStatus;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.MutationAttempt;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.MutationStatus;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.companion.command.timed.runtime.TimedSummonWorldAttempt.StoreProbe;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure exact-receipt live protocol for timed summon start and store.
 *
 * <p>The shared operation engine owns the durable fence. This executor owns
 * only idempotent world evidence, chunk durability, and conservative outcome
 * classification.</p>
 */
final class TimedSummonWorldExecutor {
    private final TimedSummonWorldSafety safety =
            new TimedSummonWorldSafety();
    @Nonnull
    CompletionStage<LiveOperationResult> execute(
            @Nullable TimedSummonTransitionRequest request,
            @Nullable OperationEnvelope operation,
            @Nullable AttemptGateway attempts
    ) {
        if (!valid(request, operation) || attempts == null) {
            return completed(unknown(
                    "operation_invariant_mismatch", null
            ));
        }
        return request.starting()
                ? start(
                        TimedSummonWorldAuthority.start(
                                request, operation.operationId()
                        ),
                        attempts
                )
                : store(
                        TimedSummonWorldAuthority.store(
                                request, operation.operationId()
                        ),
                        attempts
                );
    }

    private CompletionStage<LiveOperationResult> start(
            TimedSummonWorldAuthority.Start authority,
            AttemptGateway attempts
    ) {
        ProjectionProbe probe = safety.probeStart(attempts, authority);
        return switch (probe.status()) {
            case EXACT -> persistStart(
                    authority, attempts, probe.chunkIndex()
            );
            case ABSENT -> spawn(authority, attempts);
            case RETRYABLE -> completed(retryable(
                    "start_probe_unavailable", probe.cause()
            ));
            case CONFLICT -> completed(unknown(
                    "start_projection_conflict", probe.cause()
            ));
        };
    }
    private CompletionStage<LiveOperationResult> spawn(
            TimedSummonWorldAuthority.Start authority,
            AttemptGateway attempts
    ) {
        MutationAttempt mutation = safety.spawn(attempts, authority);
        if (mutation.status() == MutationStatus.EXACT) {
            return persistStart(
                    authority, attempts, mutation.chunkIndex()
            );
        }
        ProjectionProbe probe = safety.probeStart(attempts, authority);
        if (probe.status() == EvidenceStatus.EXACT) {
            return persistStart(
                    authority, attempts, probe.chunkIndex()
            );
        }
        if (mutation.status() == MutationStatus.CONFLICT
                || probe.status() == EvidenceStatus.CONFLICT) {
            return completed(unknown(
                    "start_mutation_ambiguous",
                    first(mutation.cause(), probe.cause())
            ));
        }
        return completed(probe.status() == EvidenceStatus.ABSENT
                || probe.status() == EvidenceStatus.RETRYABLE
                ? retryable(
                        "start_mutation_retryable",
                        first(mutation.cause(), probe.cause())
                )
                : unknown(
                        "start_mutation_ambiguous",
                        first(mutation.cause(), probe.cause())
                ));
    }

    private CompletionStage<LiveOperationResult> persistStart(
            TimedSummonWorldAuthority.Start authority,
            AttemptGateway attempts,
            @Nullable Long chunkIndex
    ) {
        if (chunkIndex == null) {
            return completed(unknown(
                    "start_chunk_evidence_missing", null
            ));
        }
        return persistThenResume(
                attempts,
                chunkIndex,
                "start_projection",
                () -> verifyStart(authority, attempts, chunkIndex)
        );
    }

    private CompletionStage<LiveOperationResult> verifyStart(
            TimedSummonWorldAuthority.Start authority,
            AttemptGateway attempts,
            long expectedChunk
    ) {
        ProjectionProbe probe = safety.probeStart(attempts, authority);
        return completed(switch (probe.status()) {
            case EXACT -> Objects.equals(
                    probe.chunkIndex(), expectedChunk
            )
                    ? confirmed("start_projection_saved_exact")
                    : unknown(
                            "start_chunk_readback_conflict",
                            probe.cause()
                    );
            case ABSENT -> unknown(
                    "start_projection_absent_after_save", probe.cause()
            );
            case RETRYABLE -> retryable(
                    "start_readback_unavailable", probe.cause()
            );
            case CONFLICT -> unknown(
                    "start_projection_readback_conflict", probe.cause()
            );
        });
    }

    private CompletionStage<LiveOperationResult> store(
            TimedSummonWorldAuthority.Store authority,
            AttemptGateway attempts
    ) {
        StoreProbe probe = safety.probeStore(attempts, authority);
        LiveOperationResult failure = storeProbeFailure(probe);
        if (failure != null) {
            return completed(failure);
        }
        if (probe.receipt().status() == EvidenceStatus.ABSENT) {
            return probe.source().status() == EvidenceStatus.ABSENT
                    ? completed(unknown(
                            "store_source_absent_without_receipt", null
                    ))
                    : installReceipt(authority, attempts);
        }
        if (probe.source().status() == EvidenceStatus.ABSENT) {
            return completed(unknown(
                    "store_source_absent_before_durable", null
            ));
        }
        return persistReceipt(
                authority, attempts, probe.receipt().chunkIndex()
        );
    }

    private CompletionStage<LiveOperationResult> installReceipt(
            TimedSummonWorldAuthority.Store authority,
            AttemptGateway attempts
    ) {
        MutationAttempt mutation =
                safety.installReceipt(attempts, authority);
        if (mutation.status() == MutationStatus.EXACT) {
            return persistReceipt(
                    authority, attempts, mutation.chunkIndex()
            );
        }
        StoreProbe probe = safety.probeStore(attempts, authority);
        LiveOperationResult failure = storeProbeFailure(probe);
        if (failure != null) {
            return completed(mutation.status() == MutationStatus.CONFLICT
                    ? unknown(
                            "store_receipt_install_ambiguous",
                            first(mutation.cause(), failure.cause())
                    )
                    : failure);
        }
        if (probe.receipt().status() == EvidenceStatus.EXACT) {
            return probe.source().status() == EvidenceStatus.ABSENT
                    ? completed(unknown(
                            "store_source_absent_before_durable", null
                    ))
                    : persistReceipt(
                            authority,
                            attempts,
                            probe.receipt().chunkIndex()
                    );
        }
        return completed(mutation.status() == MutationStatus.RETRYABLE
                ? retryable(
                        "store_receipt_install_retryable",
                        mutation.cause()
                )
                : unknown(
                        "store_receipt_install_ambiguous",
                        mutation.cause()
                ));
    }

    private CompletionStage<LiveOperationResult> persistReceipt(
            TimedSummonWorldAuthority.Store authority,
            AttemptGateway attempts,
            @Nullable Long chunkIndex
    ) {
        if (chunkIndex == null) {
            return completed(unknown(
                    "store_receipt_chunk_missing", null
            ));
        }
        return persistThenResume(
                attempts,
                chunkIndex,
                "store_receipt",
                () -> afterReceiptPersistence(
                        authority, attempts, chunkIndex
                )
        );
    }

    private CompletionStage<LiveOperationResult> afterReceiptPersistence(
            TimedSummonWorldAuthority.Store authority,
            AttemptGateway attempts,
            long expectedChunk
    ) {
        StoreProbe probe = safety.probeStore(attempts, authority);
        LiveOperationResult failure =
                exactReceiptFailure(probe, expectedChunk);
        if (failure != null) {
            return completed(failure);
        }
        if (probe.source().status() == EvidenceStatus.ABSENT) {
            return completed(unknown(
                    "store_source_absent_after_receipt_save", null
            ));
        }
        return completed(confirmed("store_receipt_saved_exact"));
    }

    private CompletionStage<LiveOperationResult> persistThenResume(
            AttemptGateway attempts,
            long chunkIndex,
            String phase,
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        return safety.persist(attempts, chunkIndex)
                .thenCompose(persistence -> {
                    LiveOperationResult failure = persistenceFailure(
                            persistence, chunkIndex, phase
                    );
                    return failure == null
                            ? safety.resume(
                                    attempts,
                                    continuation,
                                    retryable(
                                            phase + "_world_resume_failed",
                                            null
                                    )
                            )
                            : completed(failure);
                });
    }

    @Nullable
    private LiveOperationResult persistenceFailure(
            ChunkPersistence persistence,
            long expectedChunk,
            String phase
    ) {
        if (persistence.status()
                == TimedSummonWorldAttempt.PersistenceStatus.RETRYABLE) {
            return retryable(
                    phase + "_save_failed", persistence.cause()
            );
        }
        if (persistence.status()
                == TimedSummonWorldAttempt.PersistenceStatus.CONFLICT
                || !Objects.equals(
                        persistence.chunkIndex(), expectedChunk
                )) {
            return unknown(
                    phase + "_save_conflict", persistence.cause()
            );
        }
        return null;
    }

    @Nullable
    private LiveOperationResult storeProbeFailure(StoreProbe probe) {
        if (probe.receipt().status() == EvidenceStatus.CONFLICT
                || probe.source().status() == EvidenceStatus.CONFLICT) {
            return unknown(
                    storeConflictCode(evidenceCause(probe)),
                    evidenceCause(probe)
            );
        }
        if (probe.receipt().status() == EvidenceStatus.RETRYABLE
                || probe.source().status() == EvidenceStatus.RETRYABLE) {
            return retryable(
                    "store_probe_unavailable", evidenceCause(probe)
            );
        }
        if (probe.receipt().status() == EvidenceStatus.EXACT
                && probe.source().status() == EvidenceStatus.EXACT
                && !Objects.equals(
                        probe.receipt().chunkIndex(),
                        probe.source().chunkIndex()
                )) {
            return unknown("store_chunk_evidence_conflict", null);
        }
        return null;
    }

    private String storeConflictCode(@Nullable Throwable cause) {
        String message = cause == null ? null : cause.getMessage();
        String prefix = "timed_summon_store_";
        return message != null && message.startsWith(prefix)
                ? "store_evidence_conflict_"
                + message.substring(prefix.length())
                : "store_evidence_conflict";
    }

    @Nullable
    private LiveOperationResult exactReceiptFailure(
            StoreProbe probe,
            long expectedChunk
    ) {
        LiveOperationResult failure = storeProbeFailure(probe);
        if (failure != null) {
            return failure;
        }
        if (probe.receipt().status() != EvidenceStatus.EXACT
                || !Objects.equals(
                        probe.receipt().chunkIndex(), expectedChunk
                )) {
            return unknown(
                    "store_receipt_readback_conflict",
                    probe.receipt().cause()
            );
        }
        return null;
    }

    private boolean valid(
            TimedSummonTransitionRequest request,
            OperationEnvelope operation
    ) {
        return request != null
                && operation != null
                && TimedSummonTransitionDefinition.KIND.equals(
                        operation.kind()
                )
                && TimedSummonTransitionDefinition.INSTANCE.payloadVersion()
                == operation.payloadVersion()
                && request.groupAdmission().before().revision().equals(
                        operation.expectedLifecycleRevision()
                )
                && operation.participants().contains(
                        OperationScope.profile(
                                request.beforeLease().profileId()
                        )
                )
                && operation.participants().contains(
                        OperationScope.owner(
                                request.familyKey().ownerId()
                        )
                )
                && operation.participants().contains(
                        OperationScope.commandFamily(request.familyKey())
                );
    }

    private Throwable evidenceCause(StoreProbe probe) {
        return first(
                probe.receipt().cause(),
                probe.source().cause()
        );
    }
    private Throwable first(Throwable first, Throwable second) {
        return first == null ? second : first;
    }
    private LiveOperationResult confirmed(String suffix) {
        return LiveOperationResult.confirmed(code(suffix));
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
        return "timed_summon_" + suffix;
    }
    private CompletionStage<LiveOperationResult> completed(
            LiveOperationResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }
}
