package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.CaptureRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureProfileRepository.ProfileIdentity;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureProfileRepository.ProfileSeed;
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
 * Coordinates durable managed-coop capture admission up to the source-retirement callback boundary.
 *
 * <p>This class deliberately has no world or ECS dependencies. Its asynchronous completion carries
 * only immutable stable identity data; a later runtime adapter must resolve live game state on the
 * owning world thread.</p>
 */
public final class ManagedCoopCaptureCoordinator {
    public enum OutcomeStatus {
        RETIREMENT_READY,
        DEDUPLICATED,
        FAILED
    }

    /** Immutable capture input safe to retain across persistence completions. */
    public record CaptureAttempt(@Nonnull ManagedCoopAuthorityKey authorityKey,
                                 @Nonnull String coopId,
                                 int residentSlot,
                                 @Nonnull UUID sourceNpcUuid,
                                 @Nonnull String roleId,
                                 @Nullable UUID ownerUuid,
                                 @Nullable String displayName,
                                 @Nonnull String[] toolIds,
                                 @Nonnull String snapshotJson,
                                 @Nonnull String snapshotHash,
                                 int snapshotVersion,
                                 long expectedResidentGeneration,
                                 long capturedAtMs) {
        public CaptureAttempt {
            Objects.requireNonNull(authorityKey, "authorityKey");
            Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
            coopId = requireText(coopId, "coopId").toLowerCase(Locale.ROOT);
            roleId = requireText(roleId, "roleId").toLowerCase(Locale.ROOT);
            snapshotJson = requireTextPreserving(snapshotJson, "snapshotJson");
            snapshotHash = requireText(snapshotHash, "snapshotHash");
            if (residentSlot < 0 || snapshotVersion < 1 || expectedResidentGeneration < 0L) {
                throw new IllegalArgumentException("slot, snapshot version, and generation must be valid");
            }
            toolIds = toolIds == null ? new String[0] : toolIds.clone();
        }

        @Override
        public String[] toolIds() {
            return toolIds.clone();
        }
    }

    /** Stable callback payload emitted only after source-retirement state is durably committed. */
    public record RetirementReady(@Nonnull UUID sourceNpcUuid,
                                  @Nonnull String profileId,
                                  @Nonnull String residentId,
                                  @Nonnull String operationId,
                                  @Nonnull ManagedCoopAuthorityKey authorityKey,
                                  @Nonnull String coopId,
                                  int residentSlot,
                                  @Nonnull String snapshotHash,
                                  long operationGeneration,
                                  @Nonnull OperationState durableState,
                                  long indexRevision) {
    }

    /** Completion result for one admission attempt. */
    public record CaptureOutcome(@Nonnull OutcomeStatus status,
                                 @Nullable RetirementReady retirementReady,
                                 @Nullable String detail) {
        public boolean isRetirementReady() {
            return status == OutcomeStatus.RETIREMENT_READY && retirementReady != null;
        }
    }

    private final ProfileGateway profiles;
    private final OperationGateway operations;
    private final IndexRefreshGateway indexRefresh;
    private final LongSupplier clock;
    private final ConcurrentHashMap<UUID, InFlight> inFlightBySource = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, InFlight> inFlightByProfile = new ConcurrentHashMap<>();

    public ManagedCoopCaptureCoordinator(
            @Nonnull ManagedCoopCaptureProfileRepository profiles,
            @Nonnull CoopLifecycleOperationRepository operations,
            @Nonnull ManagedCoopResidentIndexRefreshService indexRefresh) {
        this(
                Objects.requireNonNull(profiles, "profiles")::ensureProfile,
                new RepositoryOperationGateway(Objects.requireNonNull(operations, "operations")),
                Objects.requireNonNull(indexRefresh, "indexRefresh")::refresh,
                System::currentTimeMillis
        );
    }

    ManagedCoopCaptureCoordinator(@Nonnull ProfileGateway profiles,
                                  @Nonnull OperationGateway operations,
                                  @Nonnull IndexRefreshGateway indexRefresh,
                                  @Nonnull LongSupplier clock) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.indexRefresh = Objects.requireNonNull(indexRefresh, "indexRefresh");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Starts one capture admission. Completing with RETIREMENT_READY is the callback boundary;
     * no source retirement may occur for any other outcome.
     */
    @Nonnull
    public CompletableFuture<CaptureOutcome> coordinate(@Nonnull CaptureAttempt attempt) {
        if (attempt == null) {
            return CompletableFuture.completedFuture(failed("capture_attempt_required"));
        }
        InFlight flight = new InFlight(attempt.sourceNpcUuid());
        if (inFlightBySource.putIfAbsent(attempt.sourceNpcUuid(), flight) != null) {
            return CompletableFuture.completedFuture(deduplicated("capture_source_already_in_flight"));
        }

        CompletionStage<CaptureOutcome> pipeline;
        try {
            ProfileSeed seed = new ProfileSeed(
                    attempt.sourceNpcUuid(),
                    attempt.ownerUuid(),
                    attempt.roleId(),
                    attempt.displayName(),
                    attempt.toolIds()
            );
            pipeline = committed(profiles.ensureProfile(seed), "profile_ensure")
                    .thenCompose(identity -> afterProfile(flight, attempt, identity));
        } catch (RuntimeException exception) {
            pipeline = CompletableFuture.completedFuture(failed(detail("profile_ensure", exception)));
        }

        return pipeline.handle((outcome, failure) -> failure == null
                        ? outcome
                        : failed(detail("capture_coordinate", unwrap(failure))))
                .whenComplete((ignored, failure) -> clearInFlight(flight))
                .toCompletableFuture();
    }

    private CompletionStage<CaptureOutcome> afterProfile(InFlight flight,
                                                         CaptureAttempt attempt,
                                                         ProfileIdentity identity) {
        if (identity == null || identity.profileId() == null || identity.profileId().isBlank()
                || !attempt.sourceNpcUuid().equals(identity.sourceNpcUuid())) {
            return CompletableFuture.completedFuture(failed("profile_ensure_identity_mismatch"));
        }
        String profileId = identity.profileId();
        InFlight existing = inFlightByProfile.putIfAbsent(profileId, flight);
        if (existing != null && existing != flight) {
            return CompletableFuture.completedFuture(deduplicated("capture_profile_already_in_flight"));
        }
        flight.profileId = profileId;

        final CaptureRequest request;
        try {
            request = buildCaptureRequest(attempt, profileId);
            ManagedCoopCaptureClaimValidator.validate(request);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failed(detail("capture_request", exception)));
        }
        return committed(operations.claimCapture(request), "capture_claim")
                .thenCompose(result -> afterClaim(attempt, request, result));
    }

    private CompletionStage<CaptureOutcome> afterClaim(CaptureAttempt attempt,
                                                       CaptureRequest request,
                                                       MutationResult result) {
        OperationRecord operation = requireSuccessfulOperation(result, request, "capture_claim");
        if (operation == null) {
            return CompletableFuture.completedFuture(failed(mutationDetail("capture_claim", result)));
        }

        final ManagedCoopResidentIndexRefreshService.RefreshResult refresh;
        try {
            refresh = indexRefresh.refresh();
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failed(detail("resident_index_refresh", exception)));
        }
        if (refresh == null || !refresh.refreshed()) {
            String reason = refresh != null ? refresh.detail() : null;
            return CompletableFuture.completedFuture(failed(
                    "resident_index_refresh_rejected" + suffix(reason)
            ));
        }
        if (operation.state() == OperationState.SOURCE_RETIRE_REQUESTED
                || operation.state() == OperationState.COMPLETE) {
            return CompletableFuture.completedFuture(ready(request, operation, refresh.revision()));
        }
        return committed(
                operations.requestSourceRetirement(
                        operation.operationId(), operation.generation(), clock.getAsLong()),
                "source_retirement_request"
        ).thenApply(retired -> {
            OperationRecord durable = requireSuccessfulOperation(
                    retired, request, "source_retirement_request");
            return durable == null
                    ? failed(mutationDetail("source_retirement_request", retired))
                    : ready(request, durable, refresh.revision());
        });
    }

    @Nullable
    private OperationRecord requireSuccessfulOperation(@Nullable MutationResult result,
                                                       CaptureRequest request,
                                                       String stage) {
        if (result == null || !result.succeeded() || result.operation() == null) {
            return null;
        }
        OperationRecord operation = result.operation();
        boolean identityMatches = operation.kind() == OperationKind.CAPTURE
                && operation.operationId().equals(request.operationId())
                && operation.profileId().equals(request.profileId())
                && operation.authorityKey().equals(request.authorityKey())
                && operation.coopId().equalsIgnoreCase(request.coopId())
                && operation.residentSlot() == request.residentSlot()
                && Objects.equals(operation.sourceNpcUuid(), request.sourceNpcUuid())
                && Objects.equals(operation.snapshotHash(), request.snapshotHash())
                && operation.expectedResidentGeneration() == request.expectedResidentGeneration();
        boolean stateMatches = operation.state() == OperationState.SLOT_COMMITTED
                || operation.state() == OperationState.SOURCE_RETIRE_REQUESTED
                || operation.state() == OperationState.COMPLETE;
        boolean activeMatches = operation.state() == OperationState.COMPLETE
                ? !operation.active()
                : operation.active();
        if (!identityMatches || !stateMatches || !activeMatches) {
            throw new IllegalStateException(stage + "_operation_identity_or_state_mismatch");
        }
        return operation;
    }

    @Nonnull
    private CaptureRequest buildCaptureRequest(CaptureAttempt attempt, String profileId) {
        String residentId = ManagedCoopCaptureClaimValidator.residentId(profileId);
        CaptureRequest provisional = new CaptureRequest(
                "pending",
                residentId,
                attempt.authorityKey(),
                attempt.coopId(),
                attempt.residentSlot(),
                profileId,
                attempt.roleId(),
                attempt.sourceNpcUuid(),
                attempt.snapshotJson(),
                attempt.snapshotHash(),
                attempt.snapshotVersion(),
                attempt.expectedResidentGeneration(),
                attempt.capturedAtMs()
        );
        return new CaptureRequest(
                ManagedCoopCaptureClaimValidator.operationId(provisional),
                residentId,
                provisional.authorityKey(),
                provisional.coopId(),
                provisional.residentSlot(),
                provisional.profileId(),
                provisional.roleId(),
                provisional.sourceNpcUuid(),
                provisional.snapshotJson(),
                provisional.snapshotHash(),
                provisional.snapshotVersion(),
                provisional.expectedResidentGeneration(),
                provisional.nowMs()
        );
    }

    @Nonnull
    private CaptureOutcome ready(CaptureRequest request,
                                 OperationRecord operation,
                                 long indexRevision) {
        if (operation.state() != OperationState.SOURCE_RETIRE_REQUESTED
                && operation.state() != OperationState.COMPLETE) {
            return failed("source_retirement_state_not_committed");
        }
        return new CaptureOutcome(
                OutcomeStatus.RETIREMENT_READY,
                new RetirementReady(
                        request.sourceNpcUuid(),
                        request.profileId(),
                        request.residentId(),
                        request.operationId(),
                        request.authorityKey(),
                        request.coopId(),
                        request.residentSlot(),
                        request.snapshotHash(),
                        operation.generation(),
                        operation.state(),
                        indexRevision
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
        inFlightBySource.remove(flight.sourceNpcUuid, flight);
        if (flight.profileId != null) {
            inFlightByProfile.remove(flight.profileId, flight);
        }
    }

    @Nonnull
    private static CaptureOutcome failed(String detail) {
        return new CaptureOutcome(OutcomeStatus.FAILED, null, requireText(detail, "failure detail"));
    }

    @Nonnull
    private static CaptureOutcome deduplicated(String detail) {
        return new CaptureOutcome(OutcomeStatus.DEDUPLICATED, null, detail);
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

    interface ProfileGateway {
        @Nonnull
        PersistenceWriteQueue.WriteSubmission<ProfileIdentity> ensureProfile(@Nonnull ProfileSeed seed);
    }

    interface OperationGateway {
        @Nonnull
        PersistenceWriteQueue.WriteSubmission<MutationResult> claimCapture(@Nonnull CaptureRequest request);

        @Nonnull
        PersistenceWriteQueue.WriteSubmission<MutationResult> requestSourceRetirement(
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
        public PersistenceWriteQueue.WriteSubmission<MutationResult> claimCapture(
                @Nonnull CaptureRequest request) {
            return repository.claimCapture(request);
        }

        @Nonnull
        @Override
        public PersistenceWriteQueue.WriteSubmission<MutationResult> requestSourceRetirement(
                @Nonnull String operationId,
                long expectedGeneration,
                long nowMs) {
            return repository.requestCaptureSourceRetirement(operationId, expectedGeneration, nowMs);
        }
    }

    private static final class InFlight {
        private final UUID sourceNpcUuid;
        @Nullable
        private volatile String profileId;

        private InFlight(UUID sourceNpcUuid) {
            this.sourceNpcUuid = sourceNpcUuid;
        }
    }

    private static final class StageFailure extends RuntimeException {
        private StageFailure(String message) {
            super(message);
        }
    }
}
