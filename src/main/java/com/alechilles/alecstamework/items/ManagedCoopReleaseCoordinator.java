package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.ReleaseRequest;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * Coordinates a durable managed-coop release up to the projection-spawn callback boundary.
 *
 * <p>Only stable persistence values cross asynchronous completions. Live game state is resolved by
 * a later world-thread adapter after a SPAWN_READY result.</p>
 */
public final class ManagedCoopReleaseCoordinator {
    private static final String OPERATION_PREFIX = "managed-coop-release:";

    public enum OutcomeStatus {
        SPAWN_READY,
        ALREADY_PROJECTED,
        DEDUPLICATED,
        FAILED
    }

    /** Immutable release input built from one committed HOUSED resident snapshot. */
    public record ReleaseAttempt(@Nonnull ResidentRecord resident,
                                 @Nonnull UUID plannedTargetUuid,
                                 long requestedAtMs) {
        public ReleaseAttempt {
            Objects.requireNonNull(resident, "resident");
            Objects.requireNonNull(plannedTargetUuid, "plannedTargetUuid");
        }
    }

    /** Stable projection callback payload; spawnRequired is false for already-projected replay. */
    public record SpawnReady(@Nonnull String operationId,
                             @Nonnull String profileId,
                             @Nonnull String residentId,
                             @Nonnull ManagedCoopAuthorityKey authorityKey,
                             @Nonnull String coopId,
                             int residentSlot,
                             @Nonnull UUID sourceNpcUuid,
                             @Nonnull UUID plannedTargetUuid,
                             @Nullable UUID actualTargetUuid,
                             @Nonnull String snapshotHash,
                             long expectedResidentGeneration,
                             long releasingResidentGeneration,
                             long operationGeneration,
                             @Nonnull OperationState durableState,
                             long indexRevision,
                             boolean spawnRequired) {
    }

    /** Completion result for one release attempt. */
    public record ReleaseOutcome(@Nonnull OutcomeStatus status,
                                 @Nullable SpawnReady spawnReady,
                                 @Nullable String detail) {
        public boolean isSpawnReady() {
            return status == OutcomeStatus.SPAWN_READY && spawnReady != null && spawnReady.spawnRequired();
        }
    }

    private final OperationGateway operations;
    private final IndexRefreshGateway indexRefresh;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, InFlight> inFlightByResident = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, InFlight> inFlightByProfile = new ConcurrentHashMap<>();

    public ManagedCoopReleaseCoordinator(
            @Nonnull CoopLifecycleOperationRepository operations,
            @Nonnull ManagedCoopResidentIndexRefreshService indexRefresh) {
        this(
                new RepositoryOperationGateway(Objects.requireNonNull(operations, "operations")),
                Objects.requireNonNull(indexRefresh, "indexRefresh")::refresh,
                System::currentTimeMillis
        );
    }

    public ManagedCoopReleaseCoordinator(
            @Nonnull CoopLifecycleOperationRepository operations,
            @Nonnull ManagedCoopCompositeIndexRefreshService indexRefresh) {
        this(
                new RepositoryOperationGateway(Objects.requireNonNull(operations, "operations")),
                Objects.requireNonNull(indexRefresh, "indexRefresh")::refreshForLifecycleMutation,
                System::currentTimeMillis
        );
    }

    ManagedCoopReleaseCoordinator(@Nonnull OperationGateway operations,
                                  @Nonnull IndexRefreshGateway indexRefresh,
                                  @Nonnull LongSupplier clock) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.indexRefresh = Objects.requireNonNull(indexRefresh, "indexRefresh");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Completes with SPAWN_READY only after SPAWN_CLAIMED is committed. Later replay states are
     * reported as ALREADY_PROJECTED and never authorize another spawn.
     */
    @Nonnull
    public CompletableFuture<ReleaseOutcome> coordinate(@Nonnull ReleaseAttempt attempt) {
        final ReleaseRequest request;
        try {
            request = buildRequest(attempt);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failed(detail("release_request", exception)));
        }

        InFlight flight = new InFlight(request.residentId(), request.profileId());
        if (inFlightByResident.putIfAbsent(request.residentId(), flight) != null) {
            return CompletableFuture.completedFuture(deduplicated("release_resident_already_in_flight"));
        }
        InFlight profileFlight = inFlightByProfile.putIfAbsent(request.profileId(), flight);
        if (profileFlight != null && profileFlight != flight) {
            inFlightByResident.remove(request.residentId(), flight);
            return CompletableFuture.completedFuture(deduplicated("release_profile_already_in_flight"));
        }

        CompletionStage<ReleaseOutcome> pipeline;
        try {
            pipeline = committed(operations.prepareRelease(request), "release_prepare")
                    .thenCompose(result -> afterPrepare(attempt, request, result));
        } catch (RuntimeException exception) {
            pipeline = CompletableFuture.completedFuture(failed(detail("release_prepare", exception)));
        }
        return pipeline.handle((outcome, failure) -> failure == null
                        ? outcome
                        : failed(detail("release_coordinate", unwrap(failure))))
                .whenComplete((ignored, failure) -> clearInFlight(flight))
                .toCompletableFuture();
    }

    private CompletionStage<ReleaseOutcome> afterPrepare(ReleaseAttempt attempt,
                                                         ReleaseRequest request,
                                                         MutationResult result) {
        OperationRecord operation = requireOperation(result, request, true, "release_prepare");
        if (operation == null) {
            return CompletableFuture.completedFuture(failed(mutationDetail("release_prepare", result)));
        }

        RefreshAttempt refresh = refreshIndex("resident_index_refresh");
        if (!refresh.succeeded()) {
            return CompletableFuture.completedFuture(failed(refresh.failure()));
        }
        if (operation.state() != OperationState.PREPARED) {
            return CompletableFuture.completedFuture(outcome(
                    attempt, request, operation, refresh.result().revision()));
        }
        return committed(
                operations.claimSpawn(operation.operationId(), operation.generation(), clock.getAsLong()),
                "release_spawn_claim"
        ).thenApply(claimed -> afterSpawnClaim(attempt, request, claimed));
    }

    @Nonnull
    private ReleaseOutcome afterSpawnClaim(ReleaseAttempt attempt,
                                           ReleaseRequest request,
                                           MutationResult result) {
        OperationRecord durable = requireOperation(result, request, false, "release_spawn_claim");
        if (durable == null) {
            return failed(mutationDetail("release_spawn_claim", result));
        }
        RefreshAttempt refresh = refreshIndex("spawn_claim_index_refresh");
        return refresh.succeeded()
                ? outcome(attempt, request, durable, refresh.result().revision())
                : failed(refresh.failure());
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
                                             ReleaseRequest request,
                                             boolean allowPrepared,
                                             String stage) {
        if (result == null || !result.succeeded() || result.operation() == null) {
            return null;
        }
        OperationRecord operation = result.operation();
        boolean identityMatches = operation.kind() == OperationKind.RELEASE
                && operation.operationId().equals(request.operationId())
                && operation.profileId().equals(request.profileId())
                && operation.authorityKey().equals(request.authorityKey())
                && operation.coopId().equalsIgnoreCase(request.coopId())
                && operation.residentSlot() == request.residentSlot()
                && operation.sourceNpcUuid() == null
                && Objects.equals(operation.plannedTargetUuid(), request.plannedTargetUuid())
                && Objects.equals(operation.snapshotHash(), request.snapshotHash())
                && operation.expectedResidentGeneration() == request.expectedResidentGeneration();
        boolean stateMatches = (allowPrepared && operation.state() == OperationState.PREPARED)
                || operation.state() == OperationState.SPAWN_CLAIMED
                || operation.state() == OperationState.PROJECTION_CREATED
                || operation.state() == OperationState.FINALIZED;
        boolean activeMatches = operation.state() == OperationState.FINALIZED
                ? !operation.active()
                : operation.active();
        boolean projectionMatches = (operation.state() == OperationState.PROJECTION_CREATED
                || operation.state() == OperationState.FINALIZED)
                ? operation.actualTargetUuid() != null
                : operation.actualTargetUuid() == null;
        if (!identityMatches || !stateMatches || !activeMatches || !projectionMatches) {
            throw new IllegalStateException(stage + "_operation_identity_or_state_mismatch");
        }
        return operation;
    }

    @Nonnull
    private ReleaseRequest buildRequest(@Nonnull ReleaseAttempt attempt) {
        if (attempt == null) {
            throw new IllegalArgumentException("release attempt is required");
        }
        ResidentRecord resident = attempt.resident();
        validateResident(resident, attempt.plannedTargetUuid());
        String coopId = normalize(resident.coopId(), "coopId");
        String identity = token(resident.residentId())
                + token(resident.profileId())
                + token(resident.authorityKey().authorityId())
                + token(coopId)
                + token(Integer.toString(resident.residentSlot()))
                + token(resident.sourceNpcUuid().toString())
                + token(attempt.plannedTargetUuid().toString())
                + token(resident.snapshotHash())
                + token(Long.toString(resident.generation()));
        String operationId = OPERATION_PREFIX + ManagedCoopCaptureClaimValidator.snapshotSha256(identity);
        return new ReleaseRequest(
                operationId,
                resident.residentId(),
                resident.authorityKey(),
                coopId,
                resident.residentSlot(),
                resident.profileId(),
                attempt.plannedTargetUuid(),
                resident.snapshotHash(),
                resident.generation(),
                attempt.requestedAtMs()
        );
    }

    private void validateResident(ResidentRecord resident, UUID plannedTargetUuid) {
        if (resident == null || !resident.active() || resident.state() != ResidentState.HOUSED
                || resident.residentSlot() < 0 || resident.generation() < 0L
                || resident.generation() == Long.MAX_VALUE || resident.snapshotVersion() < 1) {
            throw new IllegalArgumentException("release requires one active committed HOUSED resident");
        }
        requireText(resident.residentId(), "residentId");
        requireText(resident.profileId(), "profileId");
        normalize(resident.coopId(), "coopId");
        String snapshotJson = requireTextPreserving(resident.snapshotJson(), "snapshotJson");
        String snapshotHash = requireText(resident.snapshotHash(), "snapshotHash");
        if (!snapshotHash.matches("[0-9a-f]{64}")
                || !snapshotHash.equals(ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson))) {
            throw new IllegalArgumentException("resident snapshot hash is missing or unverified");
        }
        validateSnapshotMetadata(resident, snapshotJson);
        UUID sourceUuid = resident.sourceNpcUuid();
        if (sourceUuid == null || !sourceUuid.equals(resident.residentUuid())
                || resident.deployedNpcUuid() != null) {
            throw new IllegalArgumentException("HOUSED resident source identity is inconsistent");
        }
        if (plannedTargetUuid == null || plannedTargetUuid.equals(resident.residentUuid())
                || plannedTargetUuid.equals(sourceUuid)) {
            throw new IllegalArgumentException("planned target UUID must be a new projection identity");
        }
    }

    private void validateSnapshotMetadata(ResidentRecord resident, String snapshotJson) {
        final JsonObject snapshot;
        try {
            JsonElement parsed = JsonParser.parseString(snapshotJson);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("resident snapshot root must be an object");
            }
            snapshot = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("resident snapshot JSON is invalid", exception);
        }
        String version = snapshotString(snapshot, "version");
        String npcUuid = snapshotString(snapshot, "npcUuid");
        String coopId = normalize(snapshotString(snapshot, "coopId"), "snapshot.coopId");
        String roleId = normalize(snapshotString(snapshot, "roleId"), "snapshot.roleId");
        int slot = snapshotInt(snapshot, "residentSlot");
        if (!Integer.toString(resident.snapshotVersion()).equals(version)
                || !resident.residentUuid().toString().equalsIgnoreCase(npcUuid)
                || !normalize(resident.coopId(), "coopId").equals(coopId)
                || resident.residentSlot() != slot
                || (resident.roleId() != null
                    && !normalize(resident.roleId(), "roleId").equals(roleId))) {
            throw new IllegalArgumentException("resident snapshot metadata does not match resident context");
        }
    }

    @Nonnull
    private String snapshotString(JsonObject snapshot, String field) {
        JsonElement value = snapshot.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("resident snapshot field must be a string: " + field);
        }
        return requireText(value.getAsString(), "snapshot." + field);
    }

    private int snapshotInt(JsonObject snapshot, String field) {
        JsonElement value = snapshot.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("resident snapshot field must be an integer: " + field);
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("resident snapshot field must be an integer: " + field, exception);
        }
    }

    @Nonnull
    private ReleaseOutcome outcome(ReleaseAttempt attempt,
                                   ReleaseRequest request,
                                   OperationRecord operation,
                                   long indexRevision) {
        boolean spawnRequired = operation.state() == OperationState.SPAWN_CLAIMED;
        if (!spawnRequired && operation.state() != OperationState.PROJECTION_CREATED
                && operation.state() != OperationState.FINALIZED) {
            return failed("release_operation_not_spawn_safe");
        }
        SpawnReady ready = new SpawnReady(
                request.operationId(),
                request.profileId(),
                request.residentId(),
                request.authorityKey(),
                request.coopId(),
                request.residentSlot(),
                attempt.resident().sourceNpcUuid(),
                request.plannedTargetUuid(),
                operation.actualTargetUuid(),
                request.snapshotHash(),
                request.expectedResidentGeneration(),
                request.expectedResidentGeneration() + 1L,
                operation.generation(),
                operation.state(),
                indexRevision,
                spawnRequired
        );
        return new ReleaseOutcome(
                spawnRequired ? OutcomeStatus.SPAWN_READY : OutcomeStatus.ALREADY_PROJECTED,
                ready,
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
        inFlightByResident.remove(flight.residentId, flight);
        inFlightByProfile.remove(flight.profileId, flight);
    }

    @Nonnull
    private static ReleaseOutcome failed(String detail) {
        return new ReleaseOutcome(OutcomeStatus.FAILED, null, requireText(detail, "failure detail"));
    }

    @Nonnull
    private static ReleaseOutcome deduplicated(String detail) {
        return new ReleaseOutcome(OutcomeStatus.DEDUPLICATED, null, detail);
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
        String required = requireText(value, "release identity token");
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
    private static String requireTextPreserving(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Nonnull
    private static Throwable unwrap(Throwable failure) {
        return failure.getCause() != null ? failure.getCause() : failure;
    }

    interface OperationGateway {
        @Nonnull
        PersistenceWriteQueue.WriteSubmission<MutationResult> prepareRelease(@Nonnull ReleaseRequest request);

        @Nonnull
        PersistenceWriteQueue.WriteSubmission<MutationResult> claimSpawn(
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
        public PersistenceWriteQueue.WriteSubmission<MutationResult> prepareRelease(
                @Nonnull ReleaseRequest request) {
            return repository.prepareRelease(request);
        }

        @Nonnull
        @Override
        public PersistenceWriteQueue.WriteSubmission<MutationResult> claimSpawn(
                @Nonnull String operationId,
                long expectedGeneration,
                long nowMs) {
            return repository.claimReleaseSpawn(operationId, expectedGeneration, nowMs);
        }
    }

    private record InFlight(String residentId, String profileId) {
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
