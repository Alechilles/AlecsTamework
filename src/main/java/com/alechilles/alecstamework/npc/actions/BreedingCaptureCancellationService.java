package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJobRegistry;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.npc.breeding.TameworkBreedingServices;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Cancels an active breeding job before a managed-coop capture snapshots either parent.
 *
 * <p>Callers must invoke this service synchronously on the owning world thread. The registry is
 * terminated before any live parent mutation, which releases every job reservation and makes a
 * delayed callback terminal before cooldown rollback begins. Rollback is fingerprint guarded by
 * the injected parent gateway so a newer breeding attempt is never overwritten.
 */
public final class BreedingCaptureCancellationService {
    private final BreedingBirthJobRegistry jobRegistry;
    private final ParentRollbackGateway parentRollbackGateway;

    /** Uses the one runtime registry shared by manual and passive breeding entrypoints. */
    public BreedingCaptureCancellationService() {
        this(
                TameworkBreedingServices.shared().jobRegistry(),
                new BreedingCaptureParentRollbackService()
        );
    }

    BreedingCaptureCancellationService(@Nonnull BreedingBirthJobRegistry jobRegistry,
                                       @Nonnull ParentRollbackGateway parentRollbackGateway) {
        this.jobRegistry = Objects.requireNonNull(jobRegistry, "jobRegistry");
        this.parentRollbackGateway = Objects.requireNonNull(
                parentRollbackGateway,
                "parentRollbackGateway"
        );
    }

    /** Cancels by current entity UUID before the caller captures a coop snapshot. */
    @Nonnull
    public CancellationResult cancelForCapturedParent(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID capturedParentUuid,
            @Nonnull CancellationReason reason) {
        return cancelForCapturedParent(store, capturedParentUuid, null, reason);
    }

    /**
     * Cancels by current UUID, falling back to stable profile identity after a UUID remap.
     *
     * <p>The supplied store asserts its owning thread before any registry or ECS work begins.
     */
    @Nonnull
    public CancellationResult cancelForCapturedParent(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID capturedParentUuid,
            @Nullable String stableProfileId,
            @Nonnull CancellationReason reason) {
        Objects.requireNonNull(store, "store");
        store.assertThread();
        return cancelForCapturedParentInScope(
                store,
                capturedParentUuid,
                stableProfileId,
                reason
        );
    }

    /**
     * Performs cancellation and rollback before invoking the snapshot callback.
     *
     * <p>The callback always runs for safe idempotent misses, allowing coop capture to continue
     * when the parent had no active breeding job. Snapshot exceptions are propagated unchanged.
     */
    @Nonnull
    public <T> SnapshotHandoff<T> cancelThenCaptureSnapshot(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID capturedParentUuid,
            @Nonnull CancellationReason reason,
            @Nonnull SnapshotCapture<T> snapshotCapture) {
        return cancelThenCaptureSnapshot(
                store,
                capturedParentUuid,
                null,
                reason,
                snapshotCapture
        );
    }

    @Nonnull
    public <T> SnapshotHandoff<T> cancelThenCaptureSnapshot(
            @Nonnull Store<EntityStore> store,
            @Nonnull UUID capturedParentUuid,
            @Nullable String stableProfileId,
            @Nonnull CancellationReason reason,
            @Nonnull SnapshotCapture<T> snapshotCapture) {
        Objects.requireNonNull(store, "store");
        store.assertThread();
        return cancelThenCaptureSnapshotInScope(
                store,
                capturedParentUuid,
                stableProfileId,
                reason,
                snapshotCapture
        );
    }

    @Nonnull
    CancellationResult cancelForCapturedParentInScope(
            @Nonnull Object storeScope,
            @Nonnull UUID capturedParentUuid,
            @Nullable String stableProfileId,
            @Nonnull CancellationReason reason) {
        Objects.requireNonNull(storeScope, "storeScope");
        Objects.requireNonNull(capturedParentUuid, "capturedParentUuid");
        Objects.requireNonNull(reason, "reason");
        String normalizedProfileId = normalizeProfileId(stableProfileId);

        BreedingBirthJobRegistry.TerminalResult terminal =
                jobRegistry.cancelByParentUuid(storeScope, capturedParentUuid);
        MatchKind matchKind = terminal.status() == BreedingBirthJobRegistry.TerminalStatus.APPLIED
                ? MatchKind.ENTITY_UUID
                : MatchKind.NONE;
        if (terminal.status() == BreedingBirthJobRegistry.TerminalStatus.NOT_FOUND
                && normalizedProfileId != null) {
            terminal = jobRegistry.cancelByProfileId(storeScope, normalizedProfileId);
            if (terminal.status() == BreedingBirthJobRegistry.TerminalStatus.APPLIED) {
                matchKind = MatchKind.PROFILE_ID;
            }
        }
        if (terminal.status() != BreedingBirthJobRegistry.TerminalStatus.APPLIED
                || terminal.job().isEmpty()) {
            return withoutRollback(terminal, reason);
        }

        BreedingBirthJob cancelledJob = terminal.job().orElseThrow();
        boolean capturedIsFirst = isCapturedParent(
                cancelledJob.firstParent(),
                capturedParentUuid,
                normalizedProfileId,
                matchKind
        );
        ParentRollbackReport first = rollbackSafely(
                storeScope,
                cancelledJob,
                true,
                capturedIsFirst ? capturedParentUuid : cancelledJob.firstParent().entityUuid()
        );
        ParentRollbackReport second = rollbackSafely(
                storeScope,
                cancelledJob,
                false,
                capturedIsFirst ? cancelledJob.secondParent().entityUuid() : capturedParentUuid
        );
        return new CancellationResult(
                CancellationStatus.CANCELLED,
                reason,
                matchKind,
                Optional.of(cancelledJob.jobId()),
                Optional.of(capturedIsFirst ? first : second),
                Optional.of(capturedIsFirst ? second : first)
        );
    }

    @Nonnull
    <T> SnapshotHandoff<T> cancelThenCaptureSnapshotInScope(
            @Nonnull Object storeScope,
            @Nonnull UUID capturedParentUuid,
            @Nullable String stableProfileId,
            @Nonnull CancellationReason reason,
            @Nonnull SnapshotCapture<T> snapshotCapture) {
        Objects.requireNonNull(snapshotCapture, "snapshotCapture");
        CancellationResult cancellation = cancelForCapturedParentInScope(
                storeScope,
                capturedParentUuid,
                stableProfileId,
                reason
        );
        return new SnapshotHandoff<>(cancellation, snapshotCapture.capture());
    }

    @Nonnull
    private ParentRollbackReport rollbackSafely(Object storeScope,
                                                BreedingBirthJob job,
                                                boolean firstParent,
                                                UUID liveEntityUuid) {
        BreedingParentIdentity identity = firstParent
                ? job.firstParent()
                : job.secondParent();
        BreedingParentIdentity liveIdentity = identity.entityUuid().equals(liveEntityUuid)
                ? identity
                : new BreedingParentIdentity(liveEntityUuid, identity.profileId());
        try {
            ParentRollbackOutcome outcome = Objects.requireNonNull(
                    parentRollbackGateway.rollback(storeScope, job, firstParent, liveEntityUuid),
                    "parent rollback outcome"
            );
            return new ParentRollbackReport(
                    liveIdentity,
                    outcome.status(),
                    outcome.pairingStateCleared()
            );
        } catch (RuntimeException exception) {
            return new ParentRollbackReport(liveIdentity, ParentRollbackStatus.ERROR, false);
        }
    }

    @Nonnull
    private CancellationResult withoutRollback(BreedingBirthJobRegistry.TerminalResult terminal,
                                               CancellationReason reason) {
        CancellationStatus status = switch (terminal.status()) {
            case NOT_FOUND -> CancellationStatus.NOT_FOUND;
            case ALREADY_TERMINAL -> CancellationStatus.ALREADY_TERMINAL;
            case SCOPE_CLOSED -> CancellationStatus.SCOPE_CLOSED;
            case NOT_READY, APPLIED -> CancellationStatus.REJECTED;
        };
        return new CancellationResult(
                status,
                reason,
                MatchKind.NONE,
                terminal.job().map(BreedingBirthJob::jobId),
                Optional.empty(),
                Optional.empty()
        );
    }

    private boolean isCapturedParent(BreedingParentIdentity identity,
                                     UUID capturedParentUuid,
                                     @Nullable String stableProfileId,
                                     MatchKind matchKind) {
        if (matchKind == MatchKind.ENTITY_UUID) {
            return identity.entityUuid().equals(capturedParentUuid);
        }
        return matchKind == MatchKind.PROFILE_ID
                && stableProfileId != null
                && identity.profileId().equals(stableProfileId);
    }

    @Nullable
    private static String normalizeProfileId(@Nullable String profileId) {
        if (profileId == null) {
            return null;
        }
        String normalized = profileId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** Why an active birth job is being cancelled. */
    public enum CancellationReason {
        COOP_CAPTURE,
        CAPTURE_CRATE
    }

    /** High-level result of the registry-first cancellation. */
    public enum CancellationStatus {
        CANCELLED,
        NOT_FOUND,
        ALREADY_TERMINAL,
        SCOPE_CLOSED,
        REJECTED
    }

    /** Identity index that found and atomically terminated the active job. */
    public enum MatchKind {
        ENTITY_UUID,
        PROFILE_ID,
        NONE
    }

    /** Outcome of one fingerprint-guarded live-parent restoration attempt. */
    public enum ParentRollbackStatus {
        RESTORED,
        SKIPPED_PARENT_MISSING,
        SKIPPED_IDENTITY_MISMATCH,
        SKIPPED_NEWER_STATE,
        SKIPPED_NO_PROVISIONAL_STATE,
        SKIPPED_RESTORE_FAILED,
        ERROR
    }

    /** Immutable report for either the captured parent or its partner. */
    public record ParentRollbackReport(
            @Nonnull BreedingParentIdentity identity,
            @Nonnull ParentRollbackStatus status,
            boolean pairingStateCleared) {
        public ParentRollbackReport {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(status, "status");
        }

        public boolean restored() {
            return status == ParentRollbackStatus.RESTORED;
        }
    }

    /** Full cancellation result, with rollback reports present only for a newly cancelled job. */
    public record CancellationResult(
            @Nonnull CancellationStatus status,
            @Nonnull CancellationReason reason,
            @Nonnull MatchKind matchKind,
            @Nonnull Optional<UUID> jobId,
            @Nonnull Optional<ParentRollbackReport> capturedParent,
            @Nonnull Optional<ParentRollbackReport> partner) {
        public CancellationResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(matchKind, "matchKind");
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(capturedParent, "capturedParent");
            Objects.requireNonNull(partner, "partner");
        }

        public boolean cancelled() {
            return status == CancellationStatus.CANCELLED;
        }
    }

    /** Cancellation report paired with the snapshot captured strictly after rollback. */
    public record SnapshotHandoff<T>(@Nonnull CancellationResult cancellation, T snapshot) {
        public SnapshotHandoff {
            Objects.requireNonNull(cancellation, "cancellation");
        }
    }

    /** Synchronous coop snapshot callback. Implementations must not defer work off-thread. */
    @FunctionalInterface
    public interface SnapshotCapture<T> {
        T capture();
    }

    record ParentRollbackOutcome(@Nonnull ParentRollbackStatus status,
                                 boolean pairingStateCleared) {
        ParentRollbackOutcome {
            Objects.requireNonNull(status, "status");
        }
    }

    @FunctionalInterface
    interface ParentRollbackGateway {
        @Nonnull
        ParentRollbackOutcome rollback(@Nonnull Object storeScope,
                                       @Nonnull BreedingBirthJob job,
                                       boolean firstParent,
                                       @Nonnull UUID liveEntityUuid);
    }
}
