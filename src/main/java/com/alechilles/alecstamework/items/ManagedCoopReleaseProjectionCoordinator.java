package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Persists one claimed managed-coop release projection and finalizes its resident deployment.
 *
 * <p>The coordinator consumes and emits immutable persistence identities only. A caller must create
 * the projection before invoking this boundary and must not pass live game-runtime objects.</p>
 */
public final class ManagedCoopReleaseProjectionCoordinator {
    private static final String RELEASE_OPERATION_PREFIX = "managed-coop-release:";

    public enum OutcomeStatus {
        FINALIZED,
        DEDUPLICATED,
        FAILED
    }

    /** Immutable post-spawn input safe to retain across persistence completions. */
    public record ProjectionAttempt(@Nonnull SpawnReady spawnClaim,
                                    @Nonnull UUID actualTargetUuid,
                                    long projectionRecordedAtMs) {
        public ProjectionAttempt {
            Objects.requireNonNull(spawnClaim, "spawnClaim");
            Objects.requireNonNull(actualTargetUuid, "actualTargetUuid");
        }
    }

    /** Immutable finalized projection identity. */
    public record FinalizedProjection(@Nonnull String operationId,
                                      @Nonnull String profileId,
                                      @Nonnull String residentId,
                                      @Nonnull ManagedCoopAuthorityKey authorityKey,
                                      @Nonnull String coopId,
                                      int residentSlot,
                                      @Nonnull UUID sourceNpcUuid,
                                      @Nonnull UUID plannedTargetUuid,
                                      @Nonnull UUID actualTargetUuid,
                                      @Nonnull String snapshotHash,
                                      long expectedResidentGeneration,
                                      long deployedResidentGeneration,
                                      long operationGeneration,
                                      long projectionIndexRevision,
                                      long finalizedIndexRevision) {
    }

    /** Completion result for projection persistence and release finalization. */
    public record ProjectionOutcome(@Nonnull OutcomeStatus status,
                                    @Nullable FinalizedProjection finalizedProjection,
                                    @Nullable String detail) {
        public boolean finalized() {
            return status == OutcomeStatus.FINALIZED && finalizedProjection != null;
        }
    }

    private final OperationGateway operations;
    private final IndexRefreshGateway indexRefresh;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, InFlight> inFlightByOperation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, InFlight> inFlightByTarget = new ConcurrentHashMap<>();

    public ManagedCoopReleaseProjectionCoordinator(
            @Nonnull CoopLifecycleOperationRepository operations,
            @Nonnull ManagedCoopResidentIndexRefreshService indexRefresh) {
        this(
                new RepositoryOperationGateway(Objects.requireNonNull(operations, "operations")),
                Objects.requireNonNull(indexRefresh, "indexRefresh")::refresh,
                System::currentTimeMillis
        );
    }

    ManagedCoopReleaseProjectionCoordinator(@Nonnull OperationGateway operations,
                                            @Nonnull IndexRefreshGateway indexRefresh,
                                            @Nonnull LongSupplier clock) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.indexRefresh = Objects.requireNonNull(indexRefresh, "indexRefresh");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Records the actual projection UUID, publishes RELEASING state, finalizes deployment, then
     * publishes DEPLOYED state. Only a FINALIZED outcome may be treated as complete.
     */
    @Nonnull
    public CompletableFuture<ProjectionOutcome> coordinate(@Nonnull ProjectionAttempt attempt) {
        try {
            validateAttempt(attempt);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failed(detail("projection_attempt", exception)));
        }

        SpawnReady claim = attempt.spawnClaim();
        InFlight flight = new InFlight(claim.operationId(), attempt.actualTargetUuid());
        InFlight operationFlight = inFlightByOperation.putIfAbsent(claim.operationId(), flight);
        if (operationFlight != null) {
            return CompletableFuture.completedFuture(
                    operationFlight.actualTargetUuid.equals(attempt.actualTargetUuid())
                            ? deduplicated("release_projection_operation_already_in_flight")
                            : failed("release_projection_target_in_flight_conflict")
            );
        }
        InFlight targetFlight = inFlightByTarget.putIfAbsent(attempt.actualTargetUuid(), flight);
        if (targetFlight != null && targetFlight != flight) {
            inFlightByOperation.remove(claim.operationId(), flight);
            return CompletableFuture.completedFuture(failed("release_projection_uuid_already_in_flight"));
        }

        CompletionStage<ProjectionOutcome> pipeline;
        try {
            pipeline = committed(
                    operations.markProjectionCreated(
                            claim.operationId(),
                            claim.operationGeneration(),
                            attempt.actualTargetUuid(),
                            attempt.projectionRecordedAtMs()
                    ),
                    "projection_created"
            ).thenCompose(result -> afterProjection(attempt, result));
        } catch (RuntimeException exception) {
            pipeline = CompletableFuture.completedFuture(failed(detail("projection_created", exception)));
        }
        return pipeline.handle((outcome, failure) -> failure == null
                        ? outcome
                        : failed(detail("projection_coordinate", unwrap(failure))))
                .whenComplete((ignored, failure) -> clearInFlight(flight))
                .toCompletableFuture();
    }

    private CompletionStage<ProjectionOutcome> afterProjection(ProjectionAttempt attempt,
                                                               MutationResult result) {
        SpawnReady claim = attempt.spawnClaim();
        OperationRecord operation = requireOperation(
                result, claim, attempt.actualTargetUuid(), true, "projection_created");
        if (operation == null) {
            return CompletableFuture.completedFuture(failed(mutationDetail("projection_created", result)));
        }
        RefreshAttempt projectionRefresh = refreshIndex(
                "projection_index_refresh");
        if (!projectionRefresh.succeeded()) {
            return CompletableFuture.completedFuture(failed(projectionRefresh.failure()));
        }
        if (operation.state() == OperationState.FINALIZED) {
            return CompletableFuture.completedFuture(finalized(
                    claim,
                    operation,
                    projectionRefresh.result().revision(),
                    projectionRefresh.result().revision()
            ));
        }
        return committed(
                operations.finalizeRelease(
                        operation.operationId(), operation.generation(), clock.getAsLong()),
                "release_finalize"
        ).thenCompose(finalizedResult -> afterFinalization(
                claim,
                attempt.actualTargetUuid(),
                projectionRefresh.result().revision(),
                finalizedResult
        ));
    }

    private CompletionStage<ProjectionOutcome> afterFinalization(SpawnReady claim,
                                                                 UUID actualTargetUuid,
                                                                 long projectionRevision,
                                                                 MutationResult result) {
        OperationRecord operation = requireOperation(
                result, claim, actualTargetUuid, false, "release_finalize");
        if (operation == null) {
            return CompletableFuture.completedFuture(failed(mutationDetail("release_finalize", result)));
        }
        RefreshAttempt finalizedRefresh = refreshIndex(
                "finalized_index_refresh");
        if (!finalizedRefresh.succeeded()) {
            return CompletableFuture.completedFuture(failed(finalizedRefresh.failure()));
        }
        return CompletableFuture.completedFuture(finalized(
                claim,
                operation,
                projectionRevision,
                finalizedRefresh.result().revision()
        ));
    }

    @Nonnull
    private RefreshAttempt refreshIndex(String stage) {
        final ManagedCoopResidentIndexRefreshService.RefreshResult result;
        try {
            result = indexRefresh.refresh();
        } catch (RuntimeException exception) {
            return new RefreshAttempt(null, detail(stage, exception));
        }
        if (result == null || !result.refreshed()) {
            String reason = result != null ? result.detail() : null;
            return new RefreshAttempt(null, stage + "_rejected" + suffix(reason));
        }
        return new RefreshAttempt(result, null);
    }

    @Nullable
    private OperationRecord requireOperation(@Nullable MutationResult result,
                                             SpawnReady claim,
                                             UUID actualTargetUuid,
                                             boolean allowProjected,
                                             String stage) {
        if (result == null || !result.succeeded() || result.operation() == null) {
            return null;
        }
        OperationRecord operation = result.operation();
        boolean identityMatches = operation.kind() == OperationKind.RELEASE
                && operation.operationId().equals(claim.operationId())
                && operation.profileId().equals(claim.profileId())
                && operation.authorityKey().equals(claim.authorityKey())
                && operation.coopId().equalsIgnoreCase(claim.coopId())
                && operation.residentSlot() == claim.residentSlot()
                && operation.sourceNpcUuid() == null
                && Objects.equals(operation.plannedTargetUuid(), claim.plannedTargetUuid())
                && Objects.equals(operation.actualTargetUuid(), actualTargetUuid)
                && Objects.equals(operation.snapshotHash(), claim.snapshotHash())
                && operation.expectedResidentGeneration() == claim.expectedResidentGeneration();
        boolean stateMatches = (allowProjected && operation.state() == OperationState.PROJECTION_CREATED)
                || operation.state() == OperationState.FINALIZED;
        long expectedGeneration = operation.state() == OperationState.PROJECTION_CREATED
                ? claim.operationGeneration() + 1L
                : claim.operationGeneration() + 2L;
        boolean lifecycleMatches = operation.generation() == expectedGeneration
                && (operation.state() == OperationState.FINALIZED ? !operation.active() : operation.active());
        if (!identityMatches || !stateMatches || !lifecycleMatches) {
            throw new IllegalStateException(stage + "_operation_identity_or_generation_mismatch");
        }
        return operation;
    }

    private void validateAttempt(ProjectionAttempt attempt) {
        if (attempt == null) {
            throw new IllegalArgumentException("projection attempt is required");
        }
        SpawnReady claim = attempt.spawnClaim();
        if (!claim.spawnRequired() || claim.durableState() != OperationState.SPAWN_CLAIMED
                || claim.actualTargetUuid() != null) {
            throw new IllegalArgumentException("projection requires an unconsumed SPAWN_CLAIMED claim");
        }
        requireText(claim.operationId(), "operationId");
        requireText(claim.profileId(), "profileId");
        requireText(claim.residentId(), "residentId");
        requireText(claim.coopId(), "coopId");
        String snapshotHash = requireText(claim.snapshotHash(), "snapshotHash");
        if (!snapshotHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("snapshotHash must be canonical SHA-256");
        }
        if (claim.residentSlot() < 0 || claim.expectedResidentGeneration() < 0L
                || claim.expectedResidentGeneration() > Long.MAX_VALUE - 2L
                || claim.releasingResidentGeneration() != claim.expectedResidentGeneration() + 1L
                || claim.operationGeneration() != 1L) {
            throw new IllegalArgumentException("spawn claim generations are inconsistent");
        }
        if (!attempt.actualTargetUuid().equals(claim.plannedTargetUuid())) {
            throw new IllegalArgumentException("actual projection UUID must equal the durable planned UUID");
        }
        String expectedOperationId = deterministicOperationId(claim);
        if (!expectedOperationId.equals(claim.operationId())) {
            throw new IllegalArgumentException("spawn claim operationId is not deterministic");
        }
    }

    @Nonnull
    private String deterministicOperationId(SpawnReady claim) {
        String identity = token(claim.residentId())
                + token(claim.profileId())
                + token(claim.authorityKey().authorityId())
                + token(normalize(claim.coopId(), "coopId"))
                + token(Integer.toString(claim.residentSlot()))
                + token(claim.sourceNpcUuid().toString())
                + token(claim.plannedTargetUuid().toString())
                + token(claim.snapshotHash())
                + token(Long.toString(claim.expectedResidentGeneration()));
        return RELEASE_OPERATION_PREFIX + ManagedCoopCaptureClaimValidator.snapshotSha256(identity);
    }

    @Nonnull
    private ProjectionOutcome finalized(SpawnReady claim,
                                        OperationRecord operation,
                                        long projectionRevision,
                                        long finalizedRevision) {
        return new ProjectionOutcome(
                OutcomeStatus.FINALIZED,
                new FinalizedProjection(
                        claim.operationId(),
                        claim.profileId(),
                        claim.residentId(),
                        claim.authorityKey(),
                        claim.coopId(),
                        claim.residentSlot(),
                        claim.sourceNpcUuid(),
                        claim.plannedTargetUuid(),
                        operation.actualTargetUuid(),
                        claim.snapshotHash(),
                        claim.expectedResidentGeneration(),
                        claim.expectedResidentGeneration() + 2L,
                        operation.generation(),
                        projectionRevision,
                        finalizedRevision
                ),
                null
        );
    }

    @Nonnull
    private <T> CompletionStage<T> committed(
            @Nullable PersistenceWriteQueue.WriteSubmission<T> submission,
            String stage) {
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.failedFuture(new StageFailure(stage + "_submission_missing"));
        }
        return submission.completion().thenApply(outcome -> {
            if (outcome == null || outcome.status() != PersistenceWriteQueue.WriteStatus.COMMITTED
                    || outcome.value() == null) {
                String reason = outcome != null ? outcome.failureReason() : null;
                throw new StageFailure(stage + "_not_committed" + suffix(reason));
            }
            return outcome.value();
        });
    }

    private void clearInFlight(InFlight flight) {
        inFlightByOperation.remove(flight.operationId, flight);
        inFlightByTarget.remove(flight.actualTargetUuid, flight);
    }

    @Nonnull
    private static ProjectionOutcome failed(String detail) {
        return new ProjectionOutcome(OutcomeStatus.FAILED, null, requireText(detail, "failure detail"));
    }

    @Nonnull
    private static ProjectionOutcome deduplicated(String detail) {
        return new ProjectionOutcome(OutcomeStatus.DEDUPLICATED, null, detail);
    }

    @Nonnull
    private static String mutationDetail(String stage, @Nullable MutationResult result) {
        if (result == null) {
            return stage + "_result_missing";
        }
        return stage + "_" + result.status().name().toLowerCase(Locale.ROOT) + suffix(result.detail());
    }

    @Nonnull
    private static String detail(String stage, Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return stage + "_failed" + suffix(message != null ? message : failureName(failure));
    }

    @Nonnull
    private static String failureName(@Nullable Throwable failure) {
        return failure == null ? "unknown" : failure.getClass().getSimpleName();
    }

    @Nonnull
    private static String suffix(@Nullable String value) {
        return value == null || value.isBlank() ? "" : ":" + value;
    }

    @Nonnull
    private static String normalize(@Nullable String value, String field) {
        return requireText(value, field).toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static String token(@Nullable String value) {
        String required = requireText(value, "projection identity token");
        return required.length() + ":" + required;
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    @Nonnull
    private static Throwable unwrap(Throwable failure) {
        return failure.getCause() != null ? failure.getCause() : failure;
    }

    interface OperationGateway {
        @Nonnull
        PersistenceWriteQueue.WriteSubmission<MutationResult> markProjectionCreated(
                @Nonnull String operationId,
                long expectedGeneration,
                @Nonnull UUID actualTargetUuid,
                long nowMs);

        @Nonnull
        PersistenceWriteQueue.WriteSubmission<MutationResult> finalizeRelease(
                @Nonnull String operationId,
                long expectedGeneration,
                long nowMs);
    }

    interface IndexRefreshGateway {
        @Nonnull
        ManagedCoopResidentIndexRefreshService.RefreshResult refresh();
    }

    private static final class RepositoryOperationGateway implements OperationGateway {
        private final CoopLifecycleOperationRepository repository;

        private RepositoryOperationGateway(CoopLifecycleOperationRepository repository) {
            this.repository = repository;
        }

        @Nonnull
        @Override
        public PersistenceWriteQueue.WriteSubmission<MutationResult> markProjectionCreated(
                @Nonnull String operationId,
                long expectedGeneration,
                @Nonnull UUID actualTargetUuid,
                long nowMs) {
            return repository.markProjectionCreated(
                    operationId, expectedGeneration, actualTargetUuid, nowMs);
        }

        @Nonnull
        @Override
        public PersistenceWriteQueue.WriteSubmission<MutationResult> finalizeRelease(
                @Nonnull String operationId,
                long expectedGeneration,
                long nowMs) {
            return repository.finalizeRelease(operationId, expectedGeneration, nowMs);
        }
    }

    private record InFlight(String operationId, UUID actualTargetUuid) {
    }

    private record RefreshAttempt(
            @Nullable ManagedCoopResidentIndexRefreshService.RefreshResult result,
            @Nullable String failure) {
        private boolean succeeded() {
            return result != null && failure == null;
        }
    }

    private static final class StageFailure extends RuntimeException {
        private StageFailure(String message) {
            super(message);
        }
    }
}
