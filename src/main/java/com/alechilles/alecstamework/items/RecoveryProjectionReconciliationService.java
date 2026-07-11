package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.LoadStatus;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryFinalization;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryOperation;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryState;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.TransitionResult;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.TransitionStatus;

/**
 * Reconciles an already-added recovery projection against its exact durable operation.
 *
 * <p>This service never creates, removes, or mutates an entity. It receives only immutable values
 * captured on the entity-store thread, then advances an existing recovery operation after every
 * identity and generation field matches. A projection is finalized only after the projection-created
 * write commits.</p>
 */
public final class RecoveryProjectionReconciliationService {
    private final RecoveryOperations operations;
    private final Executor readExecutor;

    public RecoveryProjectionReconciliationService(
            @Nonnull NpcRecoveryOperationRepository operationRepository) {
        this(new RepositoryRecoveryOperations(operationRepository), ForkJoinPool.commonPool());
    }

    RecoveryProjectionReconciliationService(@Nonnull RecoveryOperations operations,
                                            @Nonnull Executor readExecutor) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.readExecutor = Objects.requireNonNull(readExecutor, "readExecutor");
    }

    /** Reconciles one immutable add-event observation without retaining live ECS objects. */
    @Nonnull
    public CompletionStage<Result> reconcile(@Nullable Observation observation) {
        Result preliminary = validateObservation(observation);
        if (preliminary != null) {
            return CompletableFuture.completedFuture(preliminary);
        }
        return CompletableFuture.supplyAsync(
                        () -> operations.loadByOperationId(observation.operationId()),
                        readExecutor
                )
                .thenCompose(load -> reconcileLoaded(observation, load))
                .exceptionally(failure -> Result.failed(
                        Status.READ_FAILED,
                        "recovery_reconciliation_exception:" + failure.getClass().getSimpleName()
                ));
    }

    @Nullable
    private Result validateObservation(@Nullable Observation observation) {
        if (observation == null) {
            return Result.failed(Status.INVALID_OBSERVATION, "observation_required");
        }
        if (!TameworkProjectionIdentityComponent.KIND_RECOVERY.equals(observation.projectionKind())) {
            return Result.failed(Status.IGNORED_NON_RECOVERY, "projection_kind_not_recovery");
        }
        if (!hasText(observation.profileId()) || !hasText(observation.operationId())
                || observation.sourceNpcUuid() == null || observation.generation() < 0L
                || observation.generation() > Long.MAX_VALUE - 2L
                || observation.uuidComponentUuid() == null || observation.legacyNpcUuid() == null) {
            return Result.failed(Status.INVALID_OBSERVATION, "incomplete_recovery_identity");
        }
        if (!observation.uuidComponentUuid().equals(observation.legacyNpcUuid())) {
            return Result.failed(Status.IDENTITY_CONFLICT, "projection_uuid_components_disagree");
        }
        if (!observation.toolIdsAvailable()) {
            return Result.failed(Status.TOOL_LINK_SNAPSHOT_UNAVAILABLE, "command_links_type_unavailable");
        }
        return null;
    }

    @Nonnull
    private CompletionStage<Result> reconcileLoaded(
            @Nonnull Observation observation,
            @Nullable NpcRecoveryOperationRepository.LoadResult load) {
        if (load == null || load.status() == LoadStatus.FAILED) {
            return completed(Status.READ_FAILED, "recovery_operation_read_failed");
        }
        if (load.status() == LoadStatus.NOT_FOUND || load.operation() == null) {
            return completed(Status.OPERATION_NOT_FOUND, "recovery_operation_not_found");
        }
        RecoveryOperation operation = load.operation();
        String conflict = validateIdentity(observation, operation);
        if (conflict != null) {
            return completed(Status.IDENTITY_CONFLICT, conflict);
        }
        return switch (operation.state()) {
            case SPAWN_CLAIMED -> recordProjection(observation, operation);
            case PROJECTION_CREATED, FINALIZED -> finalizeProjection(observation, operation);
            default -> completed(
                    Status.STATE_CONFLICT,
                    "unsupported_recovery_state:" + operation.state().name()
            );
        };
    }

    @Nullable
    private String validateIdentity(@Nonnull Observation observation,
                                    @Nonnull RecoveryOperation operation) {
        if (!observation.operationId().equals(operation.operationId())) {
            return "operation_id_mismatch";
        }
        if (!observation.profileId().equals(operation.profileId())) {
            return "profile_id_mismatch";
        }
        if (!Objects.equals(observation.sourceNpcUuid(), operation.sourceNpcUuid())) {
            return "source_uuid_mismatch";
        }
        if (!observation.uuidComponentUuid().equals(operation.plannedTargetUuid())) {
            return "planned_target_uuid_mismatch";
        }
        long expectedGeneration = expectedOperationGeneration(observation, operation.state());
        if (expectedGeneration < 0L || operation.generation() != expectedGeneration) {
            return "operation_generation_mismatch";
        }
        if (!stateShapeMatches(observation, operation)) {
            return "operation_state_shape_mismatch";
        }
        return null;
    }

    private long expectedOperationGeneration(@Nonnull Observation observation,
                                             @Nonnull RecoveryState state) {
        return switch (state) {
            case SPAWN_CLAIMED -> observation.generation();
            case PROJECTION_CREATED -> observation.generation() + 1L;
            case FINALIZED -> observation.generation() + 2L;
            default -> -1L;
        };
    }

    private boolean stateShapeMatches(@Nonnull Observation observation,
                                      @Nonnull RecoveryOperation operation) {
        return switch (operation.state()) {
            case SPAWN_CLAIMED -> operation.active() && operation.actualTargetUuid() == null;
            case PROJECTION_CREATED -> operation.active()
                    && observation.uuidComponentUuid().equals(operation.actualTargetUuid());
            case FINALIZED -> !operation.active()
                    && observation.uuidComponentUuid().equals(operation.actualTargetUuid());
            default -> false;
        };
    }

    @Nonnull
    private CompletionStage<Result> recordProjection(@Nonnull Observation observation,
                                                     @Nonnull RecoveryOperation operation) {
        PersistenceWriteQueue.WriteSubmission<TransitionResult> submission;
        try {
            submission = operations.recordProjectionCreated(
                    operation.operationId(),
                    operation.profileId(),
                    observation.uuidComponentUuid(),
                    observation.generation()
            );
        } catch (RuntimeException exception) {
            return completed(Status.WRITE_REJECTED, "projection_write_threw");
        }
        return awaitTransition(submission, "projection_created").thenCompose(step -> {
            if (step.failure() != null) {
                return CompletableFuture.completedFuture(step.failure());
            }
            TransitionResult transition = step.transition();
            if (!isAppliedOrReplayed(transition) || transition.operation() == null) {
                return completed(Status.TRANSITION_CONFLICT, transitionDetail("projection_created", transition));
            }
            RecoveryOperation projected = transition.operation();
            String conflict = validateIdentity(observation, projected);
            if (conflict != null) {
                return completed(Status.IDENTITY_CONFLICT, conflict);
            }
            return finalizeProjection(observation, projected);
        });
    }

    @Nonnull
    private CompletionStage<Result> finalizeProjection(@Nonnull Observation observation,
                                                       @Nonnull RecoveryOperation operation) {
        if (operation.state() != RecoveryState.PROJECTION_CREATED
                && operation.state() != RecoveryState.FINALIZED) {
            return completed(Status.STATE_CONFLICT, "projection_not_ready_to_finalize");
        }
        PersistenceWriteQueue.WriteSubmission<TransitionResult> submission;
        try {
            submission = operations.finalizeRecovery(new RecoveryFinalization(
                    operation.operationId(),
                    operation.profileId(),
                    operation.sourceNpcUuid(),
                    observation.uuidComponentUuid(),
                    observation.uuidComponentUuid(),
                    observation.generation() + 1L,
                    observation.toolIds()
            ));
        } catch (RuntimeException exception) {
            return completed(Status.WRITE_REJECTED, "finalization_write_threw");
        }
        return awaitTransition(submission, "finalize").thenApply(step -> {
            if (step.failure() != null) {
                return step.failure();
            }
            TransitionResult transition = step.transition();
            if (!isAppliedOrReplayed(transition) || transition.operation() == null) {
                return Result.failed(Status.TRANSITION_CONFLICT, transitionDetail("finalize", transition));
            }
            RecoveryOperation finalized = transition.operation();
            if (finalized.state() != RecoveryState.FINALIZED
                    || finalized.active()
                    || !observation.uuidComponentUuid().equals(finalized.actualTargetUuid())) {
                return Result.failed(Status.STATE_CONFLICT, "finalization_returned_invalid_state");
            }
            Status status = transition.status() == TransitionStatus.REPLAYED
                    ? Status.FINALIZED_REPLAYED
                    : Status.FINALIZED;
            return new Result(status, "recovery_projection_finalized", finalized);
        });
    }

    @Nonnull
    private CompletionStage<TransitionStep> awaitTransition(
            @Nullable PersistenceWriteQueue.WriteSubmission<TransitionResult> submission,
            @Nonnull String operationName) {
        if (submission == null || !submission.accepted()) {
            return CompletableFuture.completedFuture(TransitionStep.failed(
                    Status.WRITE_REJECTED, operationName + "_write_rejected"));
        }
        return submission.completion().handle((outcome, failure) -> {
            if (failure != null || outcome == null || !outcome.isCommitted()) {
                return TransitionStep.failed(Status.WRITE_FAILED, operationName + "_write_failed");
            }
            if (outcome.value() == null) {
                return TransitionStep.failed(Status.WRITE_FAILED, operationName + "_missing_result");
            }
            return TransitionStep.succeeded(outcome.value());
        });
    }

    private boolean isAppliedOrReplayed(@Nullable TransitionResult transition) {
        return transition != null && (transition.status() == TransitionStatus.APPLIED
                || transition.status() == TransitionStatus.REPLAYED);
    }

    @Nonnull
    private String transitionDetail(@Nonnull String prefix, @Nullable TransitionResult transition) {
        return transition == null
                ? prefix + "_missing_transition"
                : prefix + "_" + transition.status().name().toLowerCase();
    }

    @Nonnull
    private CompletionStage<Result> completed(@Nonnull Status status, @Nonnull String detail) {
        return CompletableFuture.completedFuture(Result.failed(status, detail));
    }

    private boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    /** Immutable values captured while the added NPC is still on its entity-store thread. */
    public record Observation(@Nullable String projectionKind,
                              @Nullable String profileId,
                              @Nullable String operationId,
                              @Nullable UUID sourceNpcUuid,
                              long generation,
                              @Nullable UUID uuidComponentUuid,
                              @Nullable UUID legacyNpcUuid,
                              boolean toolIdsAvailable,
                              @Nonnull List<String> toolIds) {
        public Observation {
            TreeSet<String> canonical = new TreeSet<>();
            if (toolIds != null) {
                for (String toolId : toolIds) {
                    if (toolId != null && !toolId.isBlank()) {
                        canonical.add(toolId.trim());
                    }
                }
            }
            toolIds = List.copyOf(canonical);
        }

        @Nonnull
        public static Observation fromToolIds(@Nullable String projectionKind,
                                              @Nullable String profileId,
                                              @Nullable String operationId,
                                              @Nullable UUID sourceNpcUuid,
                                              long generation,
                                              @Nullable UUID uuidComponentUuid,
                                              @Nullable UUID legacyNpcUuid,
                                              boolean toolIdsAvailable,
                                              @Nullable Collection<String> toolIds) {
            return new Observation(
                    projectionKind, profileId, operationId, sourceNpcUuid, generation,
                    uuidComponentUuid, legacyNpcUuid, toolIdsAvailable,
                    toolIds == null ? List.of() : new ArrayList<>(toolIds)
            );
        }
    }

    public enum Status {
        FINALIZED,
        FINALIZED_REPLAYED,
        IGNORED_NON_RECOVERY,
        INVALID_OBSERVATION,
        TOOL_LINK_SNAPSHOT_UNAVAILABLE,
        OPERATION_NOT_FOUND,
        READ_FAILED,
        IDENTITY_CONFLICT,
        STATE_CONFLICT,
        WRITE_REJECTED,
        WRITE_FAILED,
        TRANSITION_CONFLICT
    }

    /** Typed terminal result for diagnostics and coordinator follow-up. */
    public record Result(@Nonnull Status status,
                         @Nonnull String detail,
                         @Nullable RecoveryOperation operation) {
        @Nonnull
        static Result failed(@Nonnull Status status, @Nonnull String detail) {
            return new Result(status, detail, null);
        }

        public boolean isFinalized() {
            return status == Status.FINALIZED || status == Status.FINALIZED_REPLAYED;
        }
    }

    interface RecoveryOperations {
        @Nonnull
        NpcRecoveryOperationRepository.LoadResult loadByOperationId(@Nullable String operationId);

        @Nonnull
        PersistenceWriteQueue.WriteSubmission<TransitionResult> recordProjectionCreated(
                @Nonnull String operationId,
                @Nonnull String profileId,
                @Nonnull UUID actualTargetUuid,
                long expectedGeneration);

        @Nonnull
        PersistenceWriteQueue.WriteSubmission<TransitionResult> finalizeRecovery(
                @Nonnull RecoveryFinalization finalization);
    }

    private static final class RepositoryRecoveryOperations implements RecoveryOperations {
        private final NpcRecoveryOperationRepository repository;

        private RepositoryRecoveryOperations(@Nonnull NpcRecoveryOperationRepository repository) {
            this.repository = Objects.requireNonNull(repository, "repository");
        }

        @Override
        public NpcRecoveryOperationRepository.LoadResult loadByOperationId(@Nullable String operationId) {
            return repository.loadByOperationId(operationId);
        }

        @Override
        public PersistenceWriteQueue.WriteSubmission<TransitionResult> recordProjectionCreated(
                @Nonnull String operationId,
                @Nonnull String profileId,
                @Nonnull UUID actualTargetUuid,
                long expectedGeneration) {
            return repository.recordProjectionCreated(
                    operationId, profileId, actualTargetUuid, expectedGeneration);
        }

        @Override
        public PersistenceWriteQueue.WriteSubmission<TransitionResult> finalizeRecovery(
                @Nonnull RecoveryFinalization finalization) {
            return repository.finalizeRecovery(finalization);
        }
    }

    private record TransitionStep(@Nullable TransitionResult transition,
                                  @Nullable Result failure) {
        @Nonnull
        static TransitionStep succeeded(@Nonnull TransitionResult transition) {
            return new TransitionStep(transition, null);
        }

        @Nonnull
        static TransitionStep failed(@Nonnull Status status, @Nonnull String detail) {
            return new TransitionStep(null, Result.failed(status, detail));
        }
    }
}
