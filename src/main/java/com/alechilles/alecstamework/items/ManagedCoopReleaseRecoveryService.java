package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reconstructs the original immutable release claim for active operations after interruption.
 *
 * <p>A {@code PROJECTION_CREATED} row is intentionally normalized to its original generation-one
 * spawn claim. The normal live guard must then find the exact marked planned UUID, causing the
 * spawn orchestrator to adopt and finalize that projection without invoking its spawn callback.</p>
 */
public final class ManagedCoopReleaseRecoveryService {
    public enum Status {
        READY,
        DEDUPLICATED,
        FAILED
    }

    /** Stable callback payload safe to pass to an owning-world-thread runtime adapter. */
    public record RecoveryOutcome(@Nonnull Status status,
                                  @Nullable SpawnReady spawnClaim,
                                  @Nullable ResidentRecord resident,
                                  @Nullable String detail) {
        public boolean ready() {
            return status == Status.READY && spawnClaim != null && resident != null;
        }
    }

    private final ManagedCoopResidentIndex residents;
    private final ManagedCoopLifecycleOperationIndex operations;
    private final TrustGate trust;
    private final ClaimGateway claims;
    private final RefreshGateway refresh;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public ManagedCoopReleaseRecoveryService(
            @Nonnull CoopLifecycleOperationRepository repository,
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopLifecycleOperationIndex operationIndex,
            @Nonnull ManagedCoopCompositeIndexRefreshService compositeIndexes) {
        this(
                residentIndex,
                operationIndex,
                compositeIndexes::isTrusted,
                (operationId, generation, nowMs) ->
                        committedClaim(repository, operationId, generation, nowMs),
                compositeIndexes::refresh,
                System::currentTimeMillis
        );
    }

    ManagedCoopReleaseRecoveryService(
            @Nonnull ManagedCoopResidentIndex residents,
            @Nonnull ManagedCoopLifecycleOperationIndex operations,
            @Nonnull TrustGate trust,
            @Nonnull ClaimGateway claims,
            @Nonnull RefreshGateway refresh,
            @Nonnull LongSupplier clock) {
        this.residents = Objects.requireNonNull(residents, "residents");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.trust = Objects.requireNonNull(trust, "trust");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Resumes one exact active release operation without deriving a new target or operation ID. */
    @Nonnull
    public CompletableFuture<RecoveryOutcome> resume(@Nullable OperationRecord requested) {
        final Evidence evidence;
        try {
            evidence = authorize(requested);
        } catch (RuntimeException exception) {
            return completed(failed(detail("release_recovery_authorization", exception)));
        }
        if (inFlight.putIfAbsent(evidence.operation().operationId(), Boolean.TRUE) != null) {
            return completed(new RecoveryOutcome(
                    Status.DEDUPLICATED, null, null, "release_recovery_already_in_flight"));
        }

        CompletableFuture<RecoveryOutcome> recovery = evidence.operation().state()
                == OperationState.PREPARED
                ? claimPrepared(evidence)
                : completed(ready(evidence));
        return recovery.handle((outcome, failure) -> failure == null
                        ? outcome
                        : failed(detail("release_recovery", unwrap(failure))))
                .whenComplete((ignored, failure) ->
                        inFlight.remove(evidence.operation().operationId()))
                .toCompletableFuture();
    }

    @Nonnull
    private CompletableFuture<RecoveryOutcome> claimPrepared(Evidence evidence) {
        final CompletableFuture<MutationResult> completion;
        try {
            completion = claims.claim(
                    evidence.operation().operationId(),
                    evidence.operation().generation(),
                    clock.getAsLong()
            );
        } catch (RuntimeException exception) {
            return completed(failed(detail("release_spawn_reclaim", exception)));
        }
        if (completion == null) {
            return completed(failed("release_spawn_reclaim_completion_missing"));
        }
        return completion.thenApply(result -> afterClaim(evidence, result));
    }

    @Nonnull
    private RecoveryOutcome afterClaim(Evidence before, @Nullable MutationResult result) {
        OperationRecord claimed = result != null ? result.operation() : null;
        if (result == null || !result.succeeded()
                || !matchesOperationIdentity(before.operation(), claimed)
                || claimed.state() != OperationState.SPAWN_CLAIMED
                || claimed.generation() != 1L || !claimed.active()) {
            return failed(mutationDetail("release_spawn_reclaim", result));
        }
        ManagedCoopCompositeIndexRefreshService.RefreshResult refreshed = refresh.refresh();
        if (refreshed == null || !refreshed.refreshed()) {
            return failed("release_recovery_index_refresh_rejected" + suffix(
                    refreshed != null ? refreshed.detail() : null));
        }
        final Evidence current;
        try {
            current = authorize(claimed);
        } catch (RuntimeException exception) {
            return failed(detail("release_recovery_post_claim", exception));
        }
        return ready(current);
    }

    @Nonnull
    private Evidence authorize(@Nullable OperationRecord requested) {
        if (requested == null || !trusted()) {
            throw new IllegalStateException("managed coop indexes are not coherently trusted");
        }
        ManagedCoopLifecycleOperationIndex.Snapshot operationSnapshot = operations.snapshot();
        ManagedCoopResidentIndex.Snapshot residentSnapshot = residents.snapshot();
        OperationRecord indexed = operationSnapshot.operationById(requested.operationId());
        if (!stableEvidence(operationSnapshot, residentSnapshot)
                || indexed == null || !indexed.equals(requested)) {
            throw new IllegalStateException("release operation is not the current indexed record");
        }
        validateOperation(indexed);
        ResidentRecord resident = residentSnapshot.residentByProfile(indexed.profileId());
        validateResident(indexed, resident);
        if (!canonicalOperationId(indexed, resident.sourceNpcUuid())) {
            throw new IllegalArgumentException("release operation ID is not canonical");
        }
        if (!stableEvidence(operationSnapshot, residentSnapshot)) {
            throw new IllegalStateException("managed coop index trust changed during recovery");
        }
        return new Evidence(indexed, resident, residentSnapshot.revision());
    }

    /** Rejects a mixed pair if a coherent refresh completed between the two snapshot reads. */
    private boolean stableEvidence(ManagedCoopLifecycleOperationIndex.Snapshot operationSnapshot,
                                   ManagedCoopResidentIndex.Snapshot residentSnapshot) {
        return trusted()
                && operationSnapshot.trusted()
                && operations.snapshot().revision() == operationSnapshot.revision()
                && residents.snapshot().revision() == residentSnapshot.revision();
    }

    private void validateOperation(OperationRecord operation) {
        boolean stateValid = operation.state() == OperationState.PREPARED
                && operation.generation() == 0L && operation.actualTargetUuid() == null
                || operation.state() == OperationState.SPAWN_CLAIMED
                && operation.generation() == 1L && operation.actualTargetUuid() == null
                || operation.state() == OperationState.PROJECTION_CREATED
                && operation.generation() == 2L
                && Objects.equals(operation.plannedTargetUuid(), operation.actualTargetUuid());
        if (operation.kind() != OperationKind.RELEASE || !operation.active() || !stateValid
                || operation.plannedTargetUuid() == null || operation.sourceNpcUuid() != null
                || operation.expectedResidentGeneration() < 0L
                || operation.expectedResidentGeneration() > Long.MAX_VALUE - 2L) {
            throw new IllegalArgumentException("active release operation is not replay safe");
        }
    }

    private void validateResident(OperationRecord operation, @Nullable ResidentRecord resident) {
        boolean matches = resident != null && resident.active()
                && resident.state() == ResidentState.RELEASING
                && resident.generation() == operation.expectedResidentGeneration() + 1L
                && resident.residentId().equals(canonicalResidentId(operation.profileId()))
                && resident.profileId().equals(operation.profileId())
                && resident.authorityKey().equals(operation.authorityKey())
                && resident.coopId().equalsIgnoreCase(operation.coopId())
                && resident.residentSlot() == operation.residentSlot()
                && resident.sourceNpcUuid() != null
                && resident.sourceNpcUuid().equals(resident.residentUuid())
                && resident.deployedNpcUuid() == null
                && Objects.equals(resident.snapshotHash(), operation.snapshotHash())
                && resident.snapshotVersion() >= 1
                && !operation.plannedTargetUuid().equals(resident.sourceNpcUuid());
        if (!matches) {
            throw new IllegalArgumentException("release resident does not match active operation");
        }
    }

    @Nonnull
    private RecoveryOutcome ready(Evidence evidence) {
        OperationRecord operation = evidence.operation();
        ResidentRecord resident = evidence.resident();
        // Reconstruct the original claim even when the durable row already reached PROJECTION_CREATED.
        SpawnReady originalClaim = new SpawnReady(
                operation.operationId(),
                operation.profileId(),
                resident.residentId(),
                operation.authorityKey(),
                operation.coopId(),
                operation.residentSlot(),
                resident.sourceNpcUuid(),
                operation.plannedTargetUuid(),
                null,
                operation.snapshotHash(),
                operation.expectedResidentGeneration(),
                operation.expectedResidentGeneration() + 1L,
                1L,
                OperationState.SPAWN_CLAIMED,
                evidence.residentRevision(),
                true
        );
        return new RecoveryOutcome(Status.READY, originalClaim, resident, null);
    }

    private boolean canonicalOperationId(OperationRecord operation, UUID sourceNpcUuid) {
        String identity = token(canonicalResidentId(operation.profileId()))
                + token(operation.profileId())
                + token(operation.authorityKey().authorityId())
                + token(normalize(operation.coopId()))
                + token(Integer.toString(operation.residentSlot()))
                + token(sourceNpcUuid.toString())
                + token(operation.plannedTargetUuid().toString())
                + token(operation.snapshotHash())
                + token(Long.toString(operation.expectedResidentGeneration()));
        String expected = "managed-coop-release:"
                + ManagedCoopCaptureClaimValidator.snapshotSha256(identity);
        return expected.equals(operation.operationId());
    }

    private boolean trusted() {
        try {
            return trust.isTrusted() && residents.isTrusted() && operations.isTrusted();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean matchesOperationIdentity(OperationRecord expected,
                                                    @Nullable OperationRecord actual) {
        return actual != null
                && expected.operationId().equals(actual.operationId())
                && expected.profileId().equals(actual.profileId())
                && expected.authorityKey().equals(actual.authorityKey())
                && expected.coopId().equalsIgnoreCase(actual.coopId())
                && expected.residentSlot() == actual.residentSlot()
                && Objects.equals(expected.plannedTargetUuid(), actual.plannedTargetUuid())
                && Objects.equals(expected.snapshotHash(), actual.snapshotHash())
                && expected.expectedResidentGeneration() == actual.expectedResidentGeneration();
    }

    @Nonnull
    private static CompletableFuture<MutationResult> committedClaim(
            CoopLifecycleOperationRepository repository,
            String operationId,
            long generation,
            long nowMs) {
        PersistenceWriteQueue.WriteSubmission<MutationResult> submission =
                repository.claimReleaseSpawn(
                        operationId, generation, nowMs);
        if (submission == null || submission.completion() == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("release spawn claim submission missing"));
        }
        return submission.completion().thenApply(outcome -> {
            if (outcome == null || outcome.status() != PersistenceWriteQueue.WriteStatus.COMMITTED
                    || outcome.value() == null) {
                throw new IllegalStateException("release spawn claim was not committed");
            }
            return outcome.value();
        });
    }

    @Nonnull
    private static String canonicalResidentId(String profileId) {
        return ManagedCoopCaptureClaimValidator.residentId(profileId);
    }

    @Nonnull
    private static String normalize(String value) {
        return requireText(value, "identifier").toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static String token(String value) {
        String required = requireText(value, "identity token");
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
    private static RecoveryOutcome failed(String detail) {
        return new RecoveryOutcome(Status.FAILED, null, null, requireText(detail, "detail"));
    }

    @Nonnull
    private static CompletableFuture<RecoveryOutcome> completed(RecoveryOutcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    @Nonnull
    private static String mutationDetail(String stage, @Nullable MutationResult result) {
        return stage + "_" + (result == null ? "result_missing"
                : result.status().name().toLowerCase(Locale.ROOT) + suffix(result.detail()));
    }

    @Nonnull
    private static String detail(String stage, Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return stage + "_failed:" + (message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message);
    }

    @Nonnull
    private static Throwable unwrap(Throwable failure) {
        return failure.getCause() != null ? failure.getCause() : failure;
    }

    @Nonnull
    private static String suffix(@Nullable String detail) {
        return detail == null || detail.isBlank() ? "" : ":" + detail;
    }

    private record Evidence(OperationRecord operation,
                            ResidentRecord resident,
                            long residentRevision) {
    }

    @FunctionalInterface
    interface TrustGate {
        boolean isTrusted();
    }

    @FunctionalInterface
    interface ClaimGateway {
        @Nullable
        CompletableFuture<MutationResult> claim(
                @Nonnull String operationId, long generation, long nowMs);
    }

    @FunctionalInterface
    interface RefreshGateway {
        @Nullable
        ManagedCoopCompositeIndexRefreshService.RefreshResult refresh();
    }
}
