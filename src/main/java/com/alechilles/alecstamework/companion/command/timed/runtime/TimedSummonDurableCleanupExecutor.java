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
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Performs timed-summon world cleanup only after canonical state is durable.
 *
 * <p>START cleanup releases the temporary movement hold. STORE cleanup removes
 * the exact source carrying the durable retirement receipt, saves its chunk,
 * and verifies absence. A retry can therefore distinguish an already-complete
 * cleanup from an unsafe pre-durable disappearance.</p>
 */
final class TimedSummonDurableCleanupExecutor {
    private final TimedSummonWorldSafety safety =
            new TimedSummonWorldSafety();

    @Nonnull
    CompletionStage<LiveOperationResult> execute(
            @Nullable TimedSummonTransitionRequest request,
            @Nullable OperationEnvelope operation,
            @Nullable AttemptGateway attempts
    ) {
        if (!valid(request, operation)
                || operation.phase() != OperationPhase.DURABLE
                || attempts == null) {
            return completed(unknown(
                    "cleanup_operation_invariant_mismatch", null
            ));
        }
        return request.starting()
                ? releaseStartHold(
                        TimedSummonWorldAuthority.start(
                                request, operation.operationId()
                        ),
                        attempts
                )
                : cleanupStoredSource(
                        TimedSummonWorldAuthority.store(
                                request, operation.operationId()
                        ),
                        attempts
                );
    }

    private CompletionStage<LiveOperationResult> releaseStartHold(
            TimedSummonWorldAuthority.Start authority,
            AttemptGateway attempts
    ) {
        ProjectionProbe before = safety.probeStart(attempts, authority);
        if (before.status() == EvidenceStatus.ABSENT) {
            return completed(unknown(
                    "cleanup_start_projection_absent", null
            ));
        }
        if (before.status() == EvidenceStatus.RETRYABLE) {
            return completed(retryable(
                    "cleanup_start_probe_unavailable", before.cause()
            ));
        }
        if (before.status() == EvidenceStatus.CONFLICT) {
            return completed(unknown(
                    "cleanup_start_projection_conflict", before.cause()
            ));
        }
        MutationAttempt release = safety.releaseStartHold(
                attempts, authority
        );
        ProjectionProbe after = safety.probeStart(attempts, authority);
        if (after.status() == EvidenceStatus.EXACT
                && Objects.equals(
                before.chunkIndex(), after.chunkIndex()
        )) {
            return completed(confirmed(
                    "cleanup_start_projection_released"
            ));
        }
        if (release.status() == MutationStatus.RETRYABLE
                || after.status() == EvidenceStatus.RETRYABLE) {
            return completed(retryable(
                    "cleanup_start_release_retryable",
                    first(release.cause(), after.cause())
            ));
        }
        return completed(unknown(
                "cleanup_start_release_conflict",
                first(release.cause(), after.cause())
        ));
    }

    private CompletionStage<LiveOperationResult> cleanupStoredSource(
            TimedSummonWorldAuthority.Store authority,
            AttemptGateway attempts
    ) {
        StoreProbe probe = safety.probeStore(attempts, authority);
        LiveOperationResult failure = cleanupProbeFailure(probe);
        if (failure != null) {
            return completed(failure);
        }
        if (probe.source().status() == EvidenceStatus.ABSENT) {
            return completed(confirmed(
                    "cleanup_store_source_already_absent"
            ));
        }
        if (probe.receipt().status() != EvidenceStatus.EXACT) {
            return completed(unknown(
                    "cleanup_store_exact_receipt_missing", null
            ));
        }
        return retireAfterDurable(
                authority, attempts, probe.receipt().chunkIndex()
        );
    }

    private CompletionStage<LiveOperationResult> retireAfterDurable(
            TimedSummonWorldAuthority.Store authority,
            AttemptGateway attempts,
            long receiptChunk
    ) {
        MutationAttempt mutation = safety.retire(attempts, authority);
        if (mutation.status() == MutationStatus.EXACT) {
            return Objects.equals(mutation.chunkIndex(), receiptChunk)
                    ? persistCleanupRetirement(
                            authority, attempts, receiptChunk
                    )
                    : completed(unknown(
                            "cleanup_store_retirement_chunk_conflict",
                            mutation.cause()
                    ));
        }
        StoreProbe probe = safety.probeStore(attempts, authority);
        if (probe.source().status() == EvidenceStatus.ABSENT) {
            return persistCleanupRetirement(
                    authority, attempts, receiptChunk
            );
        }
        LiveOperationResult failure = cleanupProbeFailure(probe);
        if (failure != null) {
            return completed(mutation.status() == MutationStatus.CONFLICT
                    ? unknown(
                            "cleanup_store_retirement_ambiguous",
                            first(mutation.cause(), failure.cause())
                    )
                    : failure);
        }
        return completed(mutation.status() == MutationStatus.RETRYABLE
                ? retryable(
                        "cleanup_store_retirement_retryable",
                        mutation.cause()
                )
                : unknown(
                        "cleanup_store_retirement_ambiguous",
                        mutation.cause()
                ));
    }

    private CompletionStage<LiveOperationResult> persistCleanupRetirement(
            TimedSummonWorldAuthority.Store authority,
            AttemptGateway attempts,
            @Nullable Long chunkIndex
    ) {
        if (chunkIndex == null) {
            return completed(unknown(
                    "cleanup_store_retirement_chunk_missing", null
            ));
        }
        return persistThenResume(
                attempts,
                chunkIndex,
                () -> verifyCleanupRetirement(
                        authority, attempts, chunkIndex
                )
        );
    }

    private CompletionStage<LiveOperationResult> verifyCleanupRetirement(
            TimedSummonWorldAuthority.Store authority,
            AttemptGateway attempts,
            long expectedChunk
    ) {
        StoreProbe probe = safety.probeStore(attempts, authority);
        if (probe.source().status() == EvidenceStatus.ABSENT) {
            return completed(confirmed(
                    "cleanup_store_retirement_saved_exact"
            ));
        }
        LiveOperationResult failure = cleanupProbeFailure(probe);
        if (failure != null) {
            return completed(failure);
        }
        if (probe.source().status() == EvidenceStatus.EXACT
                && Objects.equals(
                probe.source().chunkIndex(), expectedChunk
        )) {
            return completed(retryable(
                    "cleanup_store_source_still_present_after_save",
                    probe.source().cause()
            ));
        }
        return completed(unknown(
                "cleanup_store_retirement_readback_conflict",
                evidenceCause(probe)
        ));
    }

    private CompletionStage<LiveOperationResult> persistThenResume(
            AttemptGateway attempts,
            long chunkIndex,
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        return safety.persist(attempts, chunkIndex)
                .thenCompose(persistence -> {
                    LiveOperationResult failure = persistenceFailure(
                            persistence, chunkIndex
                    );
                    return failure == null
                            ? safety.resume(
                                    attempts,
                                    continuation,
                                    retryable(
                                            "cleanup_store_retirement"
                                                    + "_world_resume_failed",
                                            null
                                    )
                            )
                            : completed(failure);
                });
    }

    @Nullable
    private LiveOperationResult persistenceFailure(
            ChunkPersistence persistence,
            long expectedChunk
    ) {
        if (persistence.status()
                == TimedSummonWorldAttempt.PersistenceStatus.RETRYABLE) {
            return retryable(
                    "cleanup_store_retirement_save_failed",
                    persistence.cause()
            );
        }
        if (persistence.status()
                == TimedSummonWorldAttempt.PersistenceStatus.CONFLICT
                || !Objects.equals(
                persistence.chunkIndex(), expectedChunk
        )) {
            return unknown(
                    "cleanup_store_retirement_save_conflict",
                    persistence.cause()
            );
        }
        return null;
    }

    @Nullable
    private LiveOperationResult cleanupProbeFailure(StoreProbe probe) {
        if (probe.source().status() == EvidenceStatus.CONFLICT
                || probe.receipt().status() == EvidenceStatus.CONFLICT) {
            return unknown(
                    "cleanup_store_evidence_conflict",
                    evidenceCause(probe)
            );
        }
        if (probe.source().status() == EvidenceStatus.RETRYABLE
                || probe.receipt().status() == EvidenceStatus.RETRYABLE) {
            return retryable(
                    "cleanup_store_probe_unavailable",
                    evidenceCause(probe)
            );
        }
        if (probe.source().status() == EvidenceStatus.EXACT
                && probe.receipt().status() == EvidenceStatus.EXACT
                && !Objects.equals(
                probe.source().chunkIndex(),
                probe.receipt().chunkIndex()
        )) {
            return unknown(
                    "cleanup_store_chunk_evidence_conflict", null
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
