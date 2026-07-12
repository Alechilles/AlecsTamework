package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationReason;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.SnapshotHandoff;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Captures a canonical portable snapshot/envelope before an eligible NPC enters a capture crate. */
public final class ManagedCoopCapturedItemAuthoringService {
    public enum AuthoringStatus {
        PREPARED,
        NOT_ELIGIBLE,
        FAILED
    }

    public record AuthoringResult(@Nonnull AuthoringStatus status,
                                  @Nullable String profileId,
                                  @Nullable String envelopeJson,
                                  @Nullable String detail,
                                  @Nullable CaptureCompletion captureCompletion) {
        public AuthoringResult {
            Objects.requireNonNull(status, "status");
        }

        public boolean prepared() {
            return status == AuthoringStatus.PREPARED
                    && profileId != null && envelopeJson != null;
        }

        /** Completes the retained breeding fence exactly once for this authoring attempt. */
        public void completeCapture(boolean captured) {
            if (captureCompletion != null) {
                captureCompletion.complete(captured);
            }
        }
    }

    /** One-shot completion for the breeding fence retained across capture-crate mutation. */
    public static final class CaptureCompletion {
        private final CompletionGateway gateway;
        private final Object storeScope;
        private final UUID sourceNpcUuid;
        private final String profileId;
        private final AtomicBoolean completed = new AtomicBoolean();

        CaptureCompletion(CompletionGateway gateway,
                          Object storeScope,
                          UUID sourceNpcUuid,
                          String profileId) {
            this.gateway = Objects.requireNonNull(gateway, "gateway");
            this.storeScope = Objects.requireNonNull(storeScope, "storeScope");
            this.sourceNpcUuid = Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
            this.profileId = Objects.requireNonNull(profileId, "profileId");
        }

        private void complete(boolean captured) {
            if (completed.compareAndSet(false, true)) {
                gateway.complete(storeScope, sourceNpcUuid, profileId, captured);
            }
        }
    }

    private final ProfileGateway profiles;
    private final SnapshotGateway snapshots;
    private final CompletionGateway completions;
    private final ManagedCoopCapturedItemEnvelopeCodec envelopes;

    public ManagedCoopCapturedItemAuthoringService(
            @Nonnull NpcProfileRepository profiles,
            @Nonnull CoopResidentStateSnapshotService snapshots,
            @Nonnull BreedingCaptureCancellationService breedingCancellation) {
        this(
                profiles::resolveProfileId,
                snapshotGateway(snapshots, breedingCancellation),
                breedingCancellation::releaseCaptureFence,
                new ManagedCoopCapturedItemEnvelopeCodec()
        );
    }

    ManagedCoopCapturedItemAuthoringService(
            @Nonnull ProfileGateway profiles,
            @Nonnull SnapshotGateway snapshots,
            @Nonnull ManagedCoopCapturedItemEnvelopeCodec envelopes) {
        this(profiles, snapshots, (scope, uuid, profile, captured) -> { }, envelopes);
    }

    ManagedCoopCapturedItemAuthoringService(
            @Nonnull ProfileGateway profiles,
            @Nonnull SnapshotGateway snapshots,
            @Nonnull CompletionGateway completions,
            @Nonnull ManagedCoopCapturedItemEnvelopeCodec envelopes) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.completions = Objects.requireNonNull(completions, "completions");
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
    }

    /**
     * Returns NOT_ELIGIBLE only when no canonical Tamework profile exists. Once a profile exists,
     * any incomplete snapshot fails closed so callers never fall back to lossy vanilla capture.
     */
    @Nonnull
    public AuthoringResult prepare(@Nonnull Ref<EntityStore> targetRef,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull UUID sourceNpcUuid,
                                   @Nonnull String roleId) {
        Objects.requireNonNull(targetRef, "targetRef");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
        if (roleId == null || roleId.isBlank()) {
            return failed("captured_item_role_missing");
        }
        CaptureCompletion completion = null;
        try {
            store.assertThread();
            String profileId = profiles.resolve(sourceNpcUuid);
            if (profileId == null || profileId.isBlank()) {
                return new AuthoringResult(
                        AuthoringStatus.NOT_ELIGIBLE, null, null,
                        "canonical_profile_not_found", null);
            }
            completion = new CaptureCompletion(
                    completions, store, sourceNpcUuid, profileId
            );
            SnapshotHandoff<CoopResidentStateSnapshot> handoff = snapshots.capture(
                    targetRef, store, sourceNpcUuid, profileId, roleId);
            return withCompletion(
                    authorFromHandoff(profileId, sourceNpcUuid, roleId, handoff), completion
            );
        } catch (RuntimeException exception) {
            if (completion != null) {
                completion.complete(false);
            }
            return failed("captured_item_authoring_failed:" + exceptionName(exception));
        }
    }

    @Nonnull
    AuthoringResult authorFromHandoff(
            @Nonnull String profileId,
            @Nonnull UUID sourceNpcUuid,
            @Nonnull String roleId,
            @Nullable SnapshotHandoff<CoopResidentStateSnapshot> handoff) {
        if (handoff == null || handoff.cancellation() == null) {
            return failed("breeding_capture_cancellation_missing");
        }
        if (!handoff.cancellation().safeToCapture()) {
            return failed("breeding_capture_cancellation_not_durable");
        }
        CoopResidentStateSnapshot snapshot = handoff.snapshot();
        if (!portableSnapshotMatches(sourceNpcUuid, roleId, snapshot)) {
            return failed("portable_snapshot_incomplete_or_mismatched");
        }
        try {
            String envelope = envelopes.encode(profileId, snapshot);
            return new AuthoringResult(
                    AuthoringStatus.PREPARED, profileId.trim(), envelope, null, null);
        } catch (RuntimeException exception) {
            return failed("captured_item_authoring_failed:" + exceptionName(exception));
        }
    }

    private AuthoringResult withCompletion(AuthoringResult result,
                                           CaptureCompletion completion) {
        return new AuthoringResult(
                result.status(), result.profileId(), result.envelopeJson(), result.detail(),
                completion
        );
    }

    private boolean portableSnapshotMatches(UUID sourceNpcUuid,
                                            String roleId,
                                            @Nullable CoopResidentStateSnapshot snapshot) {
        return snapshot != null
                && sourceNpcUuid.equals(snapshot.npcUuid())
                && snapshot.coopId() == null
                && snapshot.residentSlot() == -1
                && snapshot.roleId() != null
                && snapshot.roleId().trim().equalsIgnoreCase(roleId.trim());
    }

    private AuthoringResult failed(String detail) {
        return new AuthoringResult(AuthoringStatus.FAILED, null, null, detail, null);
    }

    @Nonnull
    private static SnapshotGateway snapshotGateway(
            @Nonnull CoopResidentStateSnapshotService snapshots,
            @Nonnull BreedingCaptureCancellationService breedingCancellation) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(breedingCancellation, "breedingCancellation");
        return (targetRef, store, sourceNpcUuid, profileId, roleId) ->
                breedingCancellation.cancelThenCaptureSnapshotRetainingFence(
                        store,
                        sourceNpcUuid,
                        profileId,
                        CancellationReason.CAPTURE_CRATE,
                        () -> snapshots.captureSnapshotForPersistence(
                                targetRef, store, sourceNpcUuid, roleId)
                );
    }

    private String exceptionName(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    interface ProfileGateway {
        @Nullable
        String resolve(@Nonnull UUID sourceNpcUuid);
    }

    @FunctionalInterface
    interface SnapshotGateway {
        @Nullable
        SnapshotHandoff<CoopResidentStateSnapshot> capture(
                @Nonnull Ref<EntityStore> targetRef,
                @Nonnull Store<EntityStore> store,
                @Nonnull UUID sourceNpcUuid,
                @Nonnull String profileId,
                @Nonnull String roleId);
    }

    @FunctionalInterface
    interface CompletionGateway {
        void complete(@Nonnull Object storeScope,
                      @Nonnull UUID sourceNpcUuid,
                      @Nonnull String profileId,
                      boolean captured);
    }
}
