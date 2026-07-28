package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ChunkPersistence;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.PersistenceStatus;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ProjectionAttempt;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ProjectionAttemptStatus;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ProjectionProbe;
import com.alechilles.alecstamework.companion.provisioning.runtime.ProvisioningActivationWorldAttempt.ProjectionStatus;
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
 * Pure idempotent spawn-and-save protocol for provisioned companion activation.
 *
 * <p>The shared operation engine retains the durable lifecycle fence. This
 * executor confirms live work only after the exact alias/profile/receipt and
 * frozen placement have been force-saved and read back from the reported
 * owning chunk. Observed absence is never successful completion.</p>
 */
final class ProvisioningActivationWorldExecutor {

    @Nonnull
    CompletionStage<LiveOperationResult> execute(
            @Nullable ProvisioningActivationRequest request,
            @Nullable OperationEnvelope operation,
            @Nullable ProvisioningActivationWorldAttempt attempt
    ) {
        if (!validOperation(request, operation) || attempt == null) {
            return completed(unknown(
                    "operation_invariant_mismatch", null
            ));
        }
        ProjectionProbe probe = safeProbe(attempt, null);
        return switch (probe.status()) {
            case EXACT -> persistTarget(
                    attempt, probe.chunkIndex()
            );
            case ABSENT -> applyProjection(attempt);
            case UNAVAILABLE -> completed(retryable(
                    "projection_probe_unavailable", probe.cause()
            ));
            case CONFLICT -> completed(unknown(
                    "projection_probe_conflict", probe.cause()
            ));
        };
    }

    private CompletionStage<LiveOperationResult> applyProjection(
            ProvisioningActivationWorldAttempt attempt
    ) {
        ProjectionAttempt projection = safeProjection(attempt);
        return switch (projection.status()) {
            case EXACT -> persistTarget(
                    attempt, projection.chunkIndex()
            );
            case UNCHANGED -> resolveUnchanged(attempt, projection);
            case RETRYABLE -> completed(retryable(
                    "projection_apply_retryable", projection.cause()
            ));
            case CONFLICT -> completed(unknown(
                    "projection_apply_conflict", projection.cause()
            ));
        };
    }

    private CompletionStage<LiveOperationResult> resolveUnchanged(
            ProvisioningActivationWorldAttempt attempt,
            ProjectionAttempt projection
    ) {
        ProjectionProbe probe = safeProbe(attempt, null);
        return switch (probe.status()) {
            case EXACT -> persistTarget(attempt, probe.chunkIndex());
            case ABSENT -> completed(retryable(
                    "projection_remains_absent", projection.cause()
            ));
            case UNAVAILABLE -> completed(retryable(
                    "projection_resolution_unavailable", probe.cause()
            ));
            case CONFLICT -> completed(unknown(
                    "projection_resolution_conflict", probe.cause()
            ));
        };
    }

    private CompletionStage<LiveOperationResult> persistTarget(
            ProvisioningActivationWorldAttempt attempt,
            @Nullable Long chunkIndex
    ) {
        if (chunkIndex == null) {
            return completed(unknown(
                    "target_chunk_evidence_missing", null
            ));
        }
        return normalizePersistence(
                safePersistence(attempt, chunkIndex)
        ).thenCompose(persistence -> afterPersistence(
                attempt, chunkIndex, persistence
        ));
    }

    private CompletionStage<LiveOperationResult> afterPersistence(
            ProvisioningActivationWorldAttempt attempt,
            long expectedChunk,
            ChunkPersistence persistence
    ) {
        if (persistence.status() == PersistenceStatus.RETRYABLE) {
            return completed(retryable(
                    "target_chunk_save_failed", persistence.cause()
            ));
        }
        if (persistence.status() == PersistenceStatus.CONFLICT
                || !Objects.equals(
                persistence.chunkIndex(), expectedChunk
        )) {
            return completed(unknown(
                    "target_chunk_save_conflict", persistence.cause()
            ));
        }
        return resume(
                attempt,
                () -> verifyDurableReadback(attempt, expectedChunk)
        );
    }

    private CompletionStage<LiveOperationResult> verifyDurableReadback(
            ProvisioningActivationWorldAttempt attempt,
            long expectedChunk
    ) {
        ProjectionProbe probe = safeProbe(attempt, expectedChunk);
        if (probe.status() == ProjectionStatus.UNAVAILABLE) {
            return completed(retryable(
                    "durable_readback_unavailable", probe.cause()
            ));
        }
        if (probe.status() != ProjectionStatus.EXACT
                || !Objects.equals(probe.chunkIndex(), expectedChunk)) {
            return completed(unknown(
                    probe.status() == ProjectionStatus.ABSENT
                            ? "durable_readback_absent"
                            : "durable_readback_conflict",
                    probe.cause()
            ));
        }
        return completed(LiveOperationResult.confirmed(
                code("durable_projection_confirmed")
        ));
    }

    private CompletionStage<ChunkPersistence> safePersistence(
            ProvisioningActivationWorldAttempt attempt,
            long chunkIndex
    ) {
        try {
            CompletionStage<ChunkPersistence> persistence =
                    attempt.persistTargetChunk(chunkIndex);
            return persistence == null
                    ? completed(ChunkPersistence.retryable(null))
                    : persistence;
        } catch (RuntimeException | LinkageError failure) {
            return completed(ChunkPersistence.retryable(failure));
        }
    }

    private CompletableFuture<ChunkPersistence> normalizePersistence(
            CompletionStage<ChunkPersistence> persistence
    ) {
        CompletableFuture<ChunkPersistence> normalized =
                new CompletableFuture<>();
        persistence.whenComplete((result, failure) -> normalized.complete(
                failure != null
                        ? ChunkPersistence.retryable(failure)
                        : result == null
                        ? ChunkPersistence.retryable(null)
                        : result
        ));
        return normalized;
    }

    private CompletionStage<LiveOperationResult> resume(
            ProvisioningActivationWorldAttempt attempt,
            Supplier<CompletionStage<LiveOperationResult>> continuation
    ) {
        CompletionStage<LiveOperationResult> resumed;
        try {
            resumed = attempt.resumeOnWorldThread(continuation);
        } catch (RuntimeException | LinkageError failure) {
            return completed(retryable("world_resume_failed", failure));
        }
        if (resumed == null) {
            return completed(retryable("world_resume_missing", null));
        }
        CompletableFuture<LiveOperationResult> normalized =
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

    private ProjectionProbe safeProbe(
            ProvisioningActivationWorldAttempt attempt,
            @Nullable Long expectedChunk
    ) {
        try {
            ProjectionProbe probe = expectedChunk == null
                    ? attempt.probe()
                    : attempt.probeInTargetChunk(expectedChunk);
            return probe == null || probe.status() == null
                    ? ProjectionProbe.conflict(null)
                    : probe;
        } catch (RuntimeException | LinkageError failure) {
            return ProjectionProbe.conflict(failure);
        }
    }

    private ProjectionAttempt safeProjection(
            ProvisioningActivationWorldAttempt attempt
    ) {
        try {
            ProjectionAttempt projection =
                    attempt.applyOrResolveExactProjection();
            return projection == null || projection.status() == null
                    ? ProjectionAttempt.conflict(null)
                    : projection;
        } catch (RuntimeException | LinkageError failure) {
            return ProjectionAttempt.retryable(failure);
        }
    }

    private boolean validOperation(
            ProvisioningActivationRequest request,
            OperationEnvelope operation
    ) {
        if (request == null || operation == null
                || operation.phase() != OperationPhase.LIVE_APPLYING
                || !ProvisioningActivationDefinition.KIND.equals(
                operation.kind()
        )
                || operation.payloadVersion()
                != ProvisioningActivationDefinition.INSTANCE.payloadVersion()
                || !ProvisioningActivationDefinition.INSTANCE.encode(request)
                .equals(operation.payloadJson())
                || !request.origin().activationKey(
                request.spawnReceiptKey()
        ).equals(operation.idempotencyKey())
                || !request.groupAdmission().before().revision().equals(
                operation.expectedLifecycleRevision()
        )
                || !operation.participants().contains(
                OperationScope.profile(request.origin().profileId())
        )
                || !operation.participants().contains(
                OperationScope.owner(
                        request.groupAdmission().before().ownerId()
                )
        )) {
            return false;
        }
        return request.timedActivation() == null
                || operation.participants().contains(
                OperationScope.commandFamily(
                        request.timedActivation().familyKey()
                )
        );
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
        return "provisioning_activation_" + suffix;
    }

    private <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
