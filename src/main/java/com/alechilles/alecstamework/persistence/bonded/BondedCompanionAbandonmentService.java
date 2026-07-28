package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Permanently abandons a bonded profile without allowing database cascades to
 * erase cleanup authority for a live projection.
 */
final class BondedCompanionAbandonmentService {
    private static final long OPERATION_RETENTION_MS =
            30L * 24L * 60L * 60L * 1000L;

    private final BondedCompanionStore store;
    private final BondedCompanionProjectionService projections;
    private final BondedCompanionChangePublisher changes;
    private final LongSupplier clock;

    BondedCompanionAbandonmentService(
            @Nonnull BondedCompanionStore store,
            @Nonnull BondedCompanionProjectionService projections,
            @Nonnull BondedCompanionChangePublisher changes,
            @Nonnull LongSupplier clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Nonnull
    BondedCompanionResult<Void> abandon(
            @Nonnull BondedCompanionActionRequest request
    ) {
        Objects.requireNonNull(request, "request");
        BondedCompanionRecord.Profile profile = find(request);
        if (profile == null) return failure(BondedCompanionResultCode.NOT_FOUND,
                "bonded-profile-not-found");
        if (profile.revision() != request.expectedRevision()) {
            return failure(BondedCompanionResultCode.REVISION_CONFLICT,
                    "bonded-profile-revision-conflict");
        }
        if (profile.state() == BondedCompanionState.ACTIVE) {
            BondedCompanionResult<Void> removal = removeActive(request, profile);
            if (removal != null) return removal;
            profile = find(request);
            if (profile == null) return failure(BondedCompanionResultCode.NOT_FOUND,
                    "bonded-profile-not-found");
        }
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> deleted =
                store.deleteProfile(request.ownerUuid(), request.rosterId(),
                        request.profileId(), profile.revision());
        if (deleted.code() != BondedCompanionStoreResult.Code.APPLIED
                || deleted.value() == null) {
            return storeFailure(deleted);
        }
        publish(deleted.value(), deleted.value().state(),
                "abandoned", BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED);
        return success();
    }

    /** Returns a failure only; {@code null} means removal was confirmed. */
    private BondedCompanionResult<Void> removeActive(
            BondedCompanionActionRequest request,
            BondedCompanionRecord.Profile profile
    ) {
        if (request.worldKey() == null) {
            return failure(BondedCompanionResultCode.WORLD_UNAVAILABLE,
                    "bonded-world-context-unavailable");
        }
        BondedCompanionRecord.Lease lease = store.findActiveLeases(
                request.ownerUuid(), request.rosterId()).stream()
                .filter(candidate -> request.profileId().equals(candidate.profileId()))
                .findFirst().orElse(null);
        if (lease == null || !request.worldKey().equals(lease.worldKey())) {
            return failure(BondedCompanionResultCode.WORLD_UNAVAILABLE,
                    "bonded-abandon-world-context-unavailable");
        }
        long now = clock.getAsLong();
        BondedCompanionOperation operation = BondedCompanionStoreOperationFactory
                .create(request.callerNamespace(), request.idempotencyKey(),
                        request.ownerUuid(), request.rosterId(), request.profileId(),
                        request.expectedRevision(), lease.leaseToken(),
                        lease.liveNpcUuid(), lease.worldKey(), now,
                        safeAdd(now, OPERATION_RETENTION_MS));
        BondedCompanionProjectionService.StoreResult stored = projections.store(
                new BondedCompanionProjectionService.StoreRequest(
                        expectation(profile, lease), request.expectedRevision(), now,
                        operation));
        if (stored.status()
                != BondedCompanionProjectionService.StoreStatus.STORED) {
            return failure(stored.status()
                    == BondedCompanionProjectionService.StoreStatus.DURABILITY_REJECTED
                    ? BondedCompanionResultCode.REVISION_CONFLICT
                    : BondedCompanionResultCode.WORLD_UNAVAILABLE,
                    stored.status()
                            == BondedCompanionProjectionService.StoreStatus
                            .STORED_CLEANUP_PENDING
                            ? "bonded-abandon-cleanup-pending"
                            : "bonded-abandon-live-removal-unconfirmed");
        }
        BondedCompanionRecord.Profile refreshed = find(request);
        if (refreshed == null || refreshed.state() != BondedCompanionState.STORED) {
            return failure(BondedCompanionResultCode.REVISION_CONFLICT,
                    "bonded-abandon-store-not-committed");
        }
        publish(refreshed, BondedCompanionState.ACTIVE, "abandon-stored",
                BondedCompanionChangePublisher.WorldEffectOutcome.CONFIRMED);
        return null;
    }

    private BondedCompanionProjectionValidator.LeaseExpectation expectation(
            BondedCompanionRecord.Profile profile,
            BondedCompanionRecord.Lease lease
    ) {
        return new BondedCompanionProjectionValidator.LeaseExpectation(
                profile.ownerUuid(), profile.rosterId(), profile.profileId(),
                lease.leaseToken(), lease.liveNpcUuid(), lease.worldKey(),
                lease.startedAtMs(), lease.expiresAtMs(),
                BondedCompanionProjectionValidator.LeasePhase.valueOf(
                        lease.projectionState().name()));
    }

    private BondedCompanionRecord.Profile find(
            BondedCompanionActionRequest request
    ) {
        return store.findProfile(request.ownerUuid(), request.rosterId(),
                request.profileId()).orElse(null);
    }

    private void publish(
            BondedCompanionRecord.Profile profile,
            BondedCompanionState oldState,
            String reason,
            BondedCompanionChangePublisher.WorldEffectOutcome outcome
    ) {
        changes.publishCommitted(new BondedCompanionChangedEvent(
                profile.profileId(), profile.ownerUuid(), profile.rosterId(),
                BondedCompanionStateView.valueOf(oldState.name()),
                BondedCompanionStateView.STORED, profile.revision(), reason), outcome);
    }

    private BondedCompanionResult<Void> storeFailure(
            BondedCompanionStoreResult<?> result
    ) {
        BondedCompanionResultCode code = switch (result.code()) {
            case NOT_FOUND -> BondedCompanionResultCode.NOT_FOUND;
            case NOT_OWNER -> BondedCompanionResultCode.NOT_OWNER;
            case REVISION_CONFLICT, IDEMPOTENCY_CONFLICT, CONFLICT ->
                    BondedCompanionResultCode.REVISION_CONFLICT;
            case INVALID_STATE -> BondedCompanionResultCode.INVALID_STATE;
            case VALIDATION_FAILED -> BondedCompanionResultCode.VALIDATION_FAILED;
            case STORAGE_FAILURE, APPLIED -> BondedCompanionResultCode.INTERNAL_FAILURE;
        };
        return failure(code, result.reason() == null
                ? "bonded-profile-delete-failed" : result.reason());
    }

    private static BondedCompanionResult<Void> success() {
        return new BondedCompanionResult<>(BondedCompanionResultCode.SUCCESS,
                null, null);
    }

    private static BondedCompanionResult<Void> failure(
            BondedCompanionResultCode code,
            String reason
    ) {
        return new BondedCompanionResult<>(code, null, reason);
    }

    private static long safeAdd(long value, long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
