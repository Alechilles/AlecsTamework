package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopLifecycleRecoveryEvidence.Decision;
import com.alechilles.alecstamework.items.ManagedCoopLifecycleRecoveryEvidence.DecisionStatus;
import com.alechilles.alecstamework.items.ManagedCoopLifecycleRecoveryEvidence.RecoveryCommand;
import com.alechilles.alecstamework.items.ManagedCoopLifecycleRecoveryPlanner.ActionKind;
import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseProjectionCommand;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resumes one interrupted managed-coop capture or release from durable, coherent evidence. */
public final class ManagedCoopLifecycleRecoveryService {
    public enum RecoveryStatus {
        NONE,
        CAPTURE_COMPLETED,
        RELEASE_COMPLETED,
        DEDUPLICATED,
        WAITING,
        RESERVED_IMPORT,
        BLOCKED,
        FAILED
    }

    /** Immutable low-noise result for one world recovery sweep. */
    public record Outcome(@Nonnull RecoveryStatus status,
                          @Nullable String operationId,
                          @Nullable String detail) {
        public Outcome {
            Objects.requireNonNull(status, "status");
        }

        public boolean completed() {
            return status == RecoveryStatus.CAPTURE_COMPLETED
                    || status == RecoveryStatus.RELEASE_COMPLETED
                    || status == RecoveryStatus.DEDUPLICATED;
        }
    }

    private final ManagedCoopLifecycleRecoveryEvidence evidence;
    private final CaptureAdvanceGateway captureAdvances;
    private final RefreshGateway refresh;
    private final EntityRetirementGateway entityRetirements;
    private final ItemRetirementGateway itemRetirements;
    private final ReleaseRecoveryGateway releaseRecovery;
    private final ProjectionGateway projections;
    private final LongSupplier clock;
    private final Predicate<String> processReleaseInFlight;
    private final ConcurrentHashMap<String, CompletableFuture<Outcome>> inFlight =
            new ConcurrentHashMap<>();

    public ManagedCoopLifecycleRecoveryService(
            @Nonnull CoopLifecycleOperationRepository repository,
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopLifecycleOperationIndex operationIndex,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes,
            @Nonnull ManagedCoopCaptureSourceRetirementService entityRetirements,
            @Nonnull ManagedCoopItemCaptureRecoveryService itemRetirements,
            @Nonnull ManagedCoopReleaseRecoveryService releaseRecovery,
            @Nonnull ManagedCoopReleaseRuntimeAdapter releaseAdapter,
            @Nonnull ManagedCoopReleasePopulationCoordinator releasePopulations) {
        this(repository, residentIndex, operationIndex, compositeIndexes,
                entityRetirements, itemRetirements, releaseRecovery, releaseAdapter,
                releasePopulations, ignored -> false);
    }

    public ManagedCoopLifecycleRecoveryService(
            @Nonnull CoopLifecycleOperationRepository repository,
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopLifecycleOperationIndex operationIndex,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes,
            @Nonnull ManagedCoopCaptureSourceRetirementService entityRetirements,
            @Nonnull ManagedCoopItemCaptureRecoveryService itemRetirements,
            @Nonnull ManagedCoopReleaseRecoveryService releaseRecovery,
            @Nonnull ManagedCoopReleaseRuntimeAdapter releaseAdapter,
            @Nonnull ManagedCoopReleasePopulationCoordinator releasePopulations,
            @Nonnull Predicate<String> processReleaseInFlight) {
        this(
                new ManagedCoopLifecycleRecoveryEvidence(
                        new ManagedCoopLifecycleRecoveryPlanner(),
                        residentIndex, operationIndex, compositeIndexes::isTrusted),
                (operationId, generation, nowMs) -> committed(
                        repository.requestCaptureSourceRetirement(
                                operationId, generation, nowMs)),
                () -> refresh(compositeIndexes),
                entityRetirements::retire,
                itemRetirements::recover,
                releaseRecovery::resume,
                new HytaleManagedCoopReleaseProjectionGateway(
                        releaseAdapter, residentIndex, compositeIndexes,
                        releasePopulations, releaseRecovery::projectionCurrent)::project,
                System::currentTimeMillis,
                processReleaseInFlight
        );
    }

    ManagedCoopLifecycleRecoveryService(
            @Nonnull ManagedCoopLifecycleRecoveryEvidence evidence,
            @Nonnull CaptureAdvanceGateway captureAdvances,
            @Nonnull RefreshGateway refresh,
            @Nonnull EntityRetirementGateway entityRetirements,
            @Nonnull ItemRetirementGateway itemRetirements,
            @Nonnull ReleaseRecoveryGateway releaseRecovery,
            @Nonnull ProjectionGateway projections,
            @Nonnull LongSupplier clock) {
        this(evidence, captureAdvances, refresh, entityRetirements, itemRetirements,
                releaseRecovery, projections, clock, ignored -> false);
    }

    ManagedCoopLifecycleRecoveryService(
            @Nonnull ManagedCoopLifecycleRecoveryEvidence evidence,
            @Nonnull CaptureAdvanceGateway captureAdvances,
            @Nonnull RefreshGateway refresh,
            @Nonnull EntityRetirementGateway entityRetirements,
            @Nonnull ItemRetirementGateway itemRetirements,
            @Nonnull ReleaseRecoveryGateway releaseRecovery,
            @Nonnull ProjectionGateway projections,
            @Nonnull LongSupplier clock,
            @Nonnull Predicate<String> processReleaseInFlight) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.captureAdvances = Objects.requireNonNull(captureAdvances, "captureAdvances");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.entityRetirements = Objects.requireNonNull(entityRetirements, "entityRetirements");
        this.itemRetirements = Objects.requireNonNull(itemRetirements, "itemRetirements");
        this.releaseRecovery = Objects.requireNonNull(releaseRecovery, "releaseRecovery");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.processReleaseInFlight = Objects.requireNonNull(
                processReleaseInFlight, "processReleaseInFlight");
    }

    /** Selects and resumes at most one deterministic active operation for this loaded world. */
    @Nonnull
    public CompletableFuture<Outcome> recover(
            @Nonnull String worldName,
            @Nonnull List<ManagedCoopContext> contexts) {
        final Decision decision;
        try {
            decision = evidence.select(worldName, contexts);
        } catch (RuntimeException exception) {
            return completed(RecoveryStatus.FAILED, null,
                    detail("lifecycle_recovery_evidence", exception));
        }
        if (decision.status() != DecisionStatus.READY || decision.command() == null) {
            return immediate(decision);
        }
        RecoveryCommand command = decision.command();
        if (runtimeOwnsRelease(command)) {
            return completed(
                    RecoveryStatus.WAITING,
                    command.operation().operationId(),
                    "release_recovery_owned_by_runtime_attempt");
        }
        CompletableFuture<Outcome> proposed = new CompletableFuture<>();
        CompletableFuture<Outcome> existing = inFlight.putIfAbsent(
                command.operation().operationId(), proposed);
        if (existing != null) {
            return completed(
                    RecoveryStatus.DEDUPLICATED,
                    command.operation().operationId(),
                    "lifecycle_recovery_already_in_flight");
        }
        final CompletionStage<Outcome> pipeline;
        try {
            pipeline = Objects.requireNonNull(start(command), "recovery pipeline");
        } catch (RuntimeException exception) {
            finish(command, proposed, new Outcome(
                    RecoveryStatus.FAILED,
                    command.operation().operationId(),
                    detail("lifecycle_recovery_start", exception)));
            return proposed;
        }
        pipeline.handle((outcome, failure) -> failure == null
                        ? outcome
                        : new Outcome(
                                RecoveryStatus.FAILED,
                                command.operation().operationId(),
                                detail("lifecycle_recovery", unwrap(failure))))
                .whenComplete((outcome, failure) -> finish(command, proposed, outcome))
                .toCompletableFuture();
        return proposed;
    }

    private boolean runtimeOwnsRelease(RecoveryCommand command) {
        if (command.action() != ActionKind.RESUME_RELEASE) {
            return false;
        }
        try {
            return processReleaseInFlight.test(command.operation().profileId());
        } catch (RuntimeException exception) {
            return true;
        }
    }

    @Nonnull
    private CompletionStage<Outcome> start(RecoveryCommand command) {
        return switch (command.action()) {
            case REQUEST_CAPTURE_SOURCE_RETIREMENT -> requestCaptureRetirement(command);
            case RESUME_CAPTURE_SOURCE_RETIREMENT -> resumeCapture(command);
            case RESUME_RELEASE -> resumeRelease(command);
            default -> CompletableFuture.completedFuture(new Outcome(
                    RecoveryStatus.BLOCKED, command.operation().operationId(),
                    "lifecycle_recovery_action_not_executable"));
        };
    }

    @Nonnull
    private CompletionStage<Outcome> requestCaptureRetirement(RecoveryCommand command) {
        final CompletionStage<MutationResult> stage;
        try {
            stage = captureAdvances.request(
                    command.operation().operationId(),
                    command.operation().generation(),
                    clock.getAsLong());
        } catch (RuntimeException exception) {
            return completedStage(RecoveryStatus.FAILED, command,
                    detail("capture_retirement_request", exception));
        }
        if (stage == null) {
            return completedStage(RecoveryStatus.FAILED, command,
                    "capture_retirement_request_missing");
        }
        return stage.thenCompose(result -> afterCaptureAdvance(command, result));
    }

    @Nonnull
    private CompletionStage<Outcome> afterCaptureAdvance(
            RecoveryCommand command,
            @Nullable MutationResult result) {
        if (!advancedCaptureMatches(command.operation(), result)) {
            return completedStage(RecoveryStatus.FAILED, command,
                    mutationDetail("capture_retirement_request", result));
        }
        final RefreshDecision refreshed;
        try {
            refreshed = refresh.refresh();
        } catch (RuntimeException exception) {
            return completedStage(RecoveryStatus.FAILED, command,
                    detail("capture_retirement_refresh", exception));
        }
        if (refreshed == null || !refreshed.refreshed()) {
            return completedStage(RecoveryStatus.FAILED, command,
                    "capture_retirement_refresh_rejected" + suffix(
                            refreshed != null ? refreshed.detail() : null));
        }
        Decision current = evidence.captureById(command.operation().operationId());
        if (current.status() != DecisionStatus.READY || current.command() == null) {
            return completedStage(RecoveryStatus.BLOCKED, command,
                    current.detail() != null
                            ? current.detail() : "capture_retirement_evidence_not_current");
        }
        return resumeCapture(current.command());
    }

    @Nonnull
    private CompletionStage<Outcome> resumeCapture(RecoveryCommand command) {
        RetirementReady ready = retirementReady(command);
        if (ready == null || command.resident() == null || command.captureSource() == null) {
            return completedStage(RecoveryStatus.BLOCKED, command,
                    "capture_recovery_command_invalid");
        }
        if (command.captureSource() == ManagedCoopCaptureSourceEvidence.Status.CAPTURED_ITEM) {
            return itemRetirements.recover(ready, command.resident())
                    .thenApply(outcome -> mapItem(command, outcome));
        }
        if (command.captureSource() == ManagedCoopCaptureSourceEvidence.Status.ENTITY_SOURCE) {
            return entityRetirements.retire(ready)
                    .thenApply(outcome -> mapEntity(command, outcome));
        }
        return completedStage(RecoveryStatus.BLOCKED, command,
                "capture_recovery_source_marker_invalid");
    }

    @Nonnull
    private CompletionStage<Outcome> resumeRelease(RecoveryCommand command) {
        if (command.releaseSite() == null) {
            return completedStage(RecoveryStatus.WAITING, command,
                    "release_recovery_waiting_for_loaded_coop");
        }
        return releaseRecovery.resume(command.operation()).thenCompose(recovered -> {
            if (recovered == null || !recovered.ready()
                    || recovered.spawnClaim() == null || recovered.resident() == null
                    || recovered.projectionToken() == null) {
                RecoveryStatus status = recovered != null
                        && recovered.status() == ManagedCoopReleaseRecoveryService.Status.DEDUPLICATED
                        ? RecoveryStatus.DEDUPLICATED : RecoveryStatus.FAILED;
                return completedStage(status, command,
                        recovered != null ? recovered.detail()
                                : "release_recovery_outcome_missing");
            }
            if (!recoveredReleaseMatches(command, recovered)) {
                return completedStage(RecoveryStatus.BLOCKED, command,
                        "release_recovery_identity_mismatch");
            }
            return projections.project(new ReleaseProjectionCommand(
                            recovered.spawnClaim(), recovered.resident(), command.releaseSite(),
                            recovered.projectionToken()))
                    .thenApply(outcome -> outcome != null && outcome.finalized()
                            ? new Outcome(RecoveryStatus.RELEASE_COMPLETED,
                                    command.operation().operationId(), outcome.detail())
                            : new Outcome(RecoveryStatus.FAILED,
                                    command.operation().operationId(),
                                    outcome != null ? outcome.detail()
                                            : "release_projection_outcome_missing"));
        });
    }

    private boolean recoveredReleaseMatches(
            RecoveryCommand command,
            ManagedCoopReleaseRecoveryService.RecoveryOutcome recovered) {
        OperationRecord operation = command.operation();
        SpawnReady claim = recovered.spawnClaim();
        ResidentRecord resident = recovered.resident();
        return claim != null && resident != null && operation.kind() == OperationKind.RELEASE
                && claim.spawnRequired() && claim.durableState() == OperationState.SPAWN_CLAIMED
                && claim.operationGeneration() == 1L && claim.actualTargetUuid() == null
                && claim.operationId().equals(operation.operationId())
                && claim.profileId().equals(operation.profileId())
                && claim.residentId().equals(resident.residentId())
                && claim.authorityKey().equals(operation.authorityKey())
                && claim.coopId().equalsIgnoreCase(operation.coopId())
                && claim.residentSlot() == operation.residentSlot()
                && Objects.equals(claim.plannedTargetUuid(), operation.plannedTargetUuid())
                && Objects.equals(claim.snapshotHash(), operation.snapshotHash())
                && claim.expectedResidentGeneration() == operation.expectedResidentGeneration()
                && claim.releasingResidentGeneration() == resident.generation()
                && resident.generation() == operation.expectedResidentGeneration() + 1L
                && resident.active() && resident.state() == ResidentState.RELEASING
                && resident.profileId().equals(operation.profileId())
                && resident.authorityKey().equals(operation.authorityKey())
                && resident.coopId().equalsIgnoreCase(operation.coopId())
                && resident.residentSlot() == operation.residentSlot()
                && claim.sourceNpcUuid().equals(resident.residentUuid())
                && Objects.equals(resident.sourceNpcUuid(), claim.sourceNpcUuid())
                && resident.deployedNpcUuid() == null
                && Objects.equals(resident.snapshotHash(), operation.snapshotHash());
    }

    @Nullable
    private RetirementReady retirementReady(RecoveryCommand command) {
        OperationRecord operation = command.operation();
        ResidentRecord resident = command.resident();
        if (resident == null || operation.kind() != OperationKind.CAPTURE
                || operation.state() != OperationState.SOURCE_RETIRE_REQUESTED
                || operation.generation() != 2L || command.residentRevision() <= 0L) {
            return null;
        }
        return new RetirementReady(
                operation.sourceNpcUuid(), operation.profileId(), resident.residentId(),
                operation.operationId(), operation.authorityKey(), operation.coopId(),
                operation.residentSlot(), operation.snapshotHash(), operation.generation(),
                operation.state(), command.residentRevision());
    }

    private Outcome mapEntity(
            RecoveryCommand command,
            @Nullable ManagedCoopCaptureSourceRetirementService.Outcome outcome) {
        if (outcome == null) {
            return outcome(RecoveryStatus.FAILED, command, "entity_retirement_outcome_missing");
        }
        return switch (outcome.status()) {
            case COMPLETED, ALREADY_COMPLETE ->
                    outcome(RecoveryStatus.CAPTURE_COMPLETED, command, outcome.detail());
            case BLOCKED -> outcome(RecoveryStatus.BLOCKED, command, outcome.detail());
            case FAILED -> outcome(RecoveryStatus.FAILED, command, outcome.detail());
        };
    }

    private Outcome mapItem(
            RecoveryCommand command,
            @Nullable ManagedCoopItemCaptureRecoveryService.Outcome outcome) {
        if (outcome == null) {
            return outcome(RecoveryStatus.FAILED, command, "item_recovery_outcome_missing");
        }
        return switch (outcome.status()) {
            case COMPLETED -> outcome(RecoveryStatus.CAPTURE_COMPLETED, command, outcome.detail());
            case DEDUPLICATED -> outcome(RecoveryStatus.DEDUPLICATED, command, outcome.detail());
            case WAITING -> outcome(RecoveryStatus.WAITING, command, outcome.detail());
            case BLOCKED -> outcome(RecoveryStatus.BLOCKED, command, outcome.detail());
            case FAILED -> outcome(RecoveryStatus.FAILED, command, outcome.detail());
        };
    }

    private boolean advancedCaptureMatches(OperationRecord before,
                                           @Nullable MutationResult result) {
        OperationRecord after = result != null ? result.operation() : null;
        return result != null && result.succeeded() && after != null
                && after.kind() == OperationKind.CAPTURE && after.active()
                && after.state() == OperationState.SOURCE_RETIRE_REQUESTED
                && after.generation() == before.generation() + 1L
                && after.operationId().equals(before.operationId())
                && after.profileId().equals(before.profileId())
                && after.authorityKey().equals(before.authorityKey())
                && after.coopId().equals(before.coopId())
                && after.residentSlot() == before.residentSlot()
                && Objects.equals(after.sourceNpcUuid(), before.sourceNpcUuid())
                && Objects.equals(after.snapshotHash(), before.snapshotHash())
                && after.expectedResidentGeneration()
                    == before.expectedResidentGeneration();
    }

    @Nonnull
    private CompletableFuture<Outcome> immediate(Decision decision) {
        RecoveryStatus status = switch (decision.status()) {
            case NONE -> RecoveryStatus.NONE;
            case WAITING -> RecoveryStatus.WAITING;
            case RESERVED_IMPORT -> RecoveryStatus.RESERVED_IMPORT;
            case BLOCKED -> RecoveryStatus.BLOCKED;
            case READY -> RecoveryStatus.FAILED;
        };
        return completed(status, null, decision.detail());
    }

    private void finish(RecoveryCommand command,
                        CompletableFuture<Outcome> completion,
                        @Nullable Outcome outcome) {
        inFlight.remove(command.operation().operationId(), completion);
        completion.complete(outcome != null ? outcome : new Outcome(
                RecoveryStatus.FAILED, command.operation().operationId(),
                "lifecycle_recovery_outcome_missing"));
    }

    private Outcome outcome(RecoveryStatus status,
                            RecoveryCommand command,
                            @Nullable String detail) {
        return new Outcome(status, command.operation().operationId(), detail);
    }

    private CompletionStage<Outcome> completedStage(
            RecoveryStatus status,
            RecoveryCommand command,
            @Nullable String detail) {
        return CompletableFuture.completedFuture(outcome(status, command, detail));
    }

    private static CompletableFuture<Outcome> completed(
            RecoveryStatus status,
            @Nullable String operationId,
            @Nullable String detail) {
        return CompletableFuture.completedFuture(new Outcome(status, operationId, detail));
    }

    @Nonnull
    private static CompletionStage<MutationResult> committed(
            @Nullable PersistenceWriteQueue.WriteSubmission<MutationResult> submission) {
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("capture_retirement_submission_missing"));
        }
        return submission.completion().thenCompose(outcome -> {
            if (outcome == null || !outcome.isCommitted() || outcome.value() == null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        outcome != null && outcome.failureReason() != null
                                ? outcome.failureReason()
                                : "capture_retirement_not_committed"));
            }
            return CompletableFuture.completedFuture(outcome.value());
        });
    }

    @Nonnull
    private static RefreshDecision refresh(
            ManagedCoopCompositeIndexRefreshService composite) {
        ManagedCoopCompositeIndexRefreshService.RefreshResult result = composite.refresh();
        return new RefreshDecision(
                result != null && result.refreshed() && composite.isTrusted(),
                result != null ? result.detail() : "composite_refresh_result_missing");
    }

    private static String mutationDetail(String stage, @Nullable MutationResult result) {
        return stage + (result == null ? "_result_missing"
                : "_" + result.status().name().toLowerCase(Locale.ROOT)
                + suffix(result.detail()));
    }

    private static String detail(String stage, Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return stage + (message == null || message.isBlank()
                ? ":" + (failure != null ? failure.getClass().getSimpleName() : "unknown")
                : ":" + message);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static String suffix(@Nullable String detail) {
        return detail == null || detail.isBlank() ? "" : ":" + detail;
    }

    @FunctionalInterface
    interface CaptureAdvanceGateway {
        @Nonnull
        CompletionStage<MutationResult> request(
                @Nonnull String operationId, long expectedGeneration, long nowMs);
    }

    @FunctionalInterface
    interface RefreshGateway {
        @Nonnull
        RefreshDecision refresh();
    }

    record RefreshDecision(boolean refreshed, @Nullable String detail) {
    }

    @FunctionalInterface
    interface EntityRetirementGateway {
        @Nonnull
        CompletionStage<ManagedCoopCaptureSourceRetirementService.Outcome> retire(
                @Nonnull RetirementReady ready);
    }

    @FunctionalInterface
    interface ItemRetirementGateway {
        @Nonnull
        CompletionStage<ManagedCoopItemCaptureRecoveryService.Outcome> recover(
                @Nonnull RetirementReady ready, @Nonnull ResidentRecord resident);
    }

    @FunctionalInterface
    interface ReleaseRecoveryGateway {
        @Nonnull
        CompletionStage<ManagedCoopReleaseRecoveryService.RecoveryOutcome> resume(
                @Nonnull OperationRecord operation);
    }

    @FunctionalInterface
    interface ProjectionGateway {
        @Nonnull
        CompletionStage<ManagedCoopReleaseSpawnOrchestrator.Outcome> project(
                @Nonnull ReleaseProjectionCommand command);
    }
}
