package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.CaptureAttempt;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.CaptureOutcome;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.OutcomeStatus;
import com.alechilles.alecstamework.items.ManagedCoopOccupancyService.CapturePlacement;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationReason;
import com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService.CancellationResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.ownership.CoopPopulationCaptureAdmissionService.SourceKind;
import com.alechilles.alecstamework.ownership.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owning-world-thread intake boundary for durable managed-coop capture.
 *
 * <p>Capacity is inspected first, then any active breeding job is cancelled and both parents are
 * fingerprint-restored before the source snapshot callback runs. Only immutable values cross into
 * the asynchronous persistence coordinator; live stores and references leave the stack first.</p>
 */
public final class ManagedCoopCaptureRuntimeAdapter {
    /** Immutable live identity copied before the persistence boundary. */
    public record Candidate(@Nonnull UUID sourceNpcUuid,
                            @Nonnull String roleId,
                            double sourceX,
                            double sourceZ,
                            @Nullable UUID ownerUuid,
                            @Nullable String displayName,
                            @Nonnull String[] toolIds,
                            @Nullable String stableProfileId) {
        public Candidate {
            Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
            roleId = requireText(roleId, "roleId");
            if (!Double.isFinite(sourceX) || !Double.isFinite(sourceZ)) {
                throw new IllegalArgumentException("source coordinates must be finite");
            }
            toolIds = toolIds == null ? new String[0] : toolIds.clone();
            stableProfileId = normalizeOptional(stableProfileId);
        }

        @Override
        public String[] toolIds() {
            return toolIds.clone();
        }
    }

    private final ManagedCoopOccupancyService occupancy;
    private final BreedingCaptureCancellationService breedingCancellation;
    private final CoopResidentStateSnapshotService snapshots;
    private final CoopResidentStateSnapshotCodec snapshotCodec;
    private final CaptureGateway captureGateway;

    public ManagedCoopCaptureRuntimeAdapter(
            @Nonnull ManagedCoopOccupancyService occupancy,
            @Nonnull BreedingCaptureCancellationService breedingCancellation,
            @Nonnull CoopResidentStateSnapshotService snapshots,
            @Nonnull ManagedCoopCaptureCoordinator coordinator) {
        this(
                occupancy,
                breedingCancellation,
                snapshots,
                new CoopResidentStateSnapshotCodec(),
                Objects.requireNonNull(coordinator, "coordinator")::coordinate
        );
    }

    ManagedCoopCaptureRuntimeAdapter(
            @Nonnull ManagedCoopOccupancyService occupancy,
            @Nonnull BreedingCaptureCancellationService breedingCancellation,
            @Nonnull CoopResidentStateSnapshotService snapshots,
            @Nonnull CoopResidentStateSnapshotCodec snapshotCodec,
            @Nonnull CaptureGateway captureGateway) {
        this.occupancy = Objects.requireNonNull(occupancy, "occupancy");
        this.breedingCancellation = Objects.requireNonNull(
                breedingCancellation, "breedingCancellation");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshotCodec");
        this.captureGateway = Objects.requireNonNull(captureGateway, "captureGateway");
    }

    /**
     * Cancels breeding, captures complete state, and submits one durable capture operation.
     * Failure before submission never requests source retirement.
     */
    @Nonnull
    public CompletableFuture<CaptureOutcome> capture(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> sourceRef,
            @Nonnull ManagedCoopContext context,
            @Nonnull Candidate candidate) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(sourceRef, "sourceRef");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(candidate, "candidate");
        boolean cancellationStarted = false;
        try {
            store.assertThread();
            if (!sourceRef.isValid()) {
                return failed("capture_source_reference_invalid");
            }
            CapturePlacement placement = occupancy.resolveCapturePlacement(
                    context,
                    candidate.sourceNpcUuid(),
                    candidate.stableProfileId()
            );
            if (!placement.permitted()) {
                return failed(placement.detail());
            }
            CompletableFuture<CancellationResult> cancellation =
                    breedingCancellation.cancelForCapturedParentDurably(
                            store,
                            candidate.sourceNpcUuid(),
                            candidate.stableProfileId(),
                            CancellationReason.COOP_CAPTURE
                    );
            cancellationStarted = true;
            CancellationResult current = cancellation.getNow(null);
            if (current != null) {
                CompletableFuture<CaptureOutcome> outcome = current.safeToCapture()
                        ? continueCapture(store, context, candidate)
                        : failed("breeding_capture_cancellation_rejected");
                return withCaptureFence(store, candidate, outcome);
            }
            World world = store.getExternalData() == null
                    ? null : store.getExternalData().getWorld();
            if (world == null) {
                return withCaptureFence(
                        store,
                        candidate,
                        failed("managed_coop_capture_world_unavailable")
                );
            }
            return withCaptureFence(
                    store,
                    candidate,
                    awaitDurableCancellation(
                            world, store, context, candidate, cancellation
                    )
            );
        } catch (RuntimeException exception) {
            if (cancellationStarted) {
                breedingCancellation.releaseCaptureFence(
                        store,
                        candidate.sourceNpcUuid(),
                        candidate.stableProfileId(),
                        false
                );
            }
            return failed(failureDetail("managed_coop_capture_runtime", exception));
        }
    }

    private CompletableFuture<CaptureOutcome> withCaptureFence(
            Object storeScope,
            Candidate candidate,
            CompletableFuture<CaptureOutcome> completion) {
        CompletableFuture<CaptureOutcome> fenced = new CompletableFuture<>();
        completion.whenComplete((outcome, failure) -> {
            breedingCancellation.releaseCaptureFence(
                    storeScope,
                    candidate.sourceNpcUuid(),
                    candidate.stableProfileId(),
                    failure == null && outcome != null && outcome.isRetirementReady()
            );
            if (failure != null || outcome == null) {
                fenced.complete(failedOutcome("managed_coop_capture_completion_failed"));
            } else {
                fenced.complete(outcome);
            }
        });
        return fenced;
    }

    private CompletableFuture<CaptureOutcome> awaitDurableCancellation(
            World world,
            Store<EntityStore> store,
            ManagedCoopContext context,
            Candidate candidate,
            CompletableFuture<CancellationResult> cancellation) {
        CompletableFuture<CaptureOutcome> result = new CompletableFuture<>();
        cancellation.thenApply(value -> value).completeOnTimeout(
                null,
                OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos(),
                TimeUnit.NANOSECONDS
        ).whenComplete((terminal, failure) -> {
            if (failure != null || terminal == null || !terminal.safeToCapture()) {
                result.complete(failedOutcome("breeding_capture_cancellation_not_durable"));
                return;
            }
            LeaseBoundWorldDispatcher.execute(
                    world,
                    () -> forward(continueCapture(store, context, candidate), result),
                    () -> result.complete(failedOutcome(
                            "managed_coop_capture_world_dispatch_rejected"))
            );
        });
        return result;
    }

    private CompletableFuture<CaptureOutcome> continueCapture(
            Store<EntityStore> store,
            ManagedCoopContext context,
            Candidate candidate) {
        store.assertThread();
        World world = store.getExternalData() == null
                ? null : store.getExternalData().getWorld();
        Ref<EntityStore> currentRef = world == null
                ? null : world.getEntityRef(candidate.sourceNpcUuid());
        if (currentRef == null || !currentRef.isValid()) {
            return failed("capture_source_reference_invalid_after_cancellation");
        }
        CapturePlacement placement = occupancy.resolveCapturePlacement(
                context, candidate.sourceNpcUuid(), candidate.stableProfileId()
        );
        if (!placement.permitted()) {
            return failed(placement.detail());
        }
        CoopResidentStateSnapshot snapshot = snapshots.captureSnapshotForManagedCoopPersistence(
                currentRef,
                store,
                candidate.sourceNpcUuid(),
                context.coopId(),
                placement.residentSlot(),
                candidate.roleId()
        );
        CaptureAttempt attempt = buildAttempt(context, placement, candidate, snapshot);
        CompletableFuture<CaptureOutcome> completion = captureGateway.coordinate(attempt);
        return completion != null
                ? completion
                : failed("managed_coop_capture_completion_missing");
    }

    private void forward(CompletableFuture<CaptureOutcome> source,
                         CompletableFuture<CaptureOutcome> target) {
        source.whenComplete((outcome, failure) -> {
            if (failure != null || outcome == null) {
                target.complete(failedOutcome("managed_coop_capture_completion_failed"));
            } else {
                target.complete(outcome);
            }
        });
    }

    @Nonnull
    CaptureAttempt buildAttempt(ManagedCoopContext context,
                                CapturePlacement placement,
                                Candidate candidate,
                                @Nullable CoopResidentStateSnapshot snapshot) {
        if (placement == null || !placement.permitted()) {
            throw new IllegalArgumentException("permitted capture placement is required");
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("managed coop snapshot is required");
        }
        validateSnapshot(context, placement.residentSlot(), candidate, snapshot);
        String snapshotJson = snapshotCodec.encode(snapshot);
        String snapshotHash = ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson);
        return new CaptureAttempt(
                context.authorityKey(),
                context.coopId(),
                placement.residentSlot(),
                candidate.sourceNpcUuid(),
                candidate.roleId(),
                candidate.ownerUuid(),
                candidate.displayName(),
                candidate.toolIds(),
                SourceKind.LIVE_ENTITY,
                new ClaimChunkCoordinate(
                        context.worldName(),
                        ChunkUtil.chunkCoordinate((int) Math.floor(candidate.sourceX())),
                        ChunkUtil.chunkCoordinate((int) Math.floor(candidate.sourceZ()))
                ),
                candidate.ownerUuid() == null && candidate.stableProfileId() == null,
                snapshotJson,
                snapshotHash,
                Integer.parseInt(CoopResidentStateSnapshotCodec.CURRENT_VERSION),
                placement.expectedResidentGeneration(),
                placement.existingResidentId(),
                snapshot.capturedAtMs()
        );
    }

    private void validateSnapshot(ManagedCoopContext context,
                                  int residentSlot,
                                  Candidate candidate,
                                  CoopResidentStateSnapshot snapshot) {
        if (residentSlot < 0
                || !candidate.sourceNpcUuid().equals(snapshot.npcUuid())
                || !context.coopId().equals(normalize(snapshot.coopId()))
                || residentSlot != snapshot.residentSlot()
                || !normalize(candidate.roleId()).equals(normalize(snapshot.roleId()))) {
            throw new IllegalArgumentException("managed coop snapshot identity mismatch");
        }
    }

    @Nonnull
    private CompletableFuture<CaptureOutcome> failed(String detail) {
        return CompletableFuture.completedFuture(failedOutcome(detail));
    }

    private CaptureOutcome failedOutcome(String detail) {
        return new CaptureOutcome(
                OutcomeStatus.FAILED, null, requireText(detail, "detail")
        );
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        return requireText(value, "identifier").toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String normalizeOptional(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    @Nonnull
    private static String failureDetail(String stage, RuntimeException failure) {
        String message = failure.getMessage();
        return stage + "_failed:" + (message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message);
    }

    @FunctionalInterface
    interface CaptureGateway {
        @Nonnull
        CompletableFuture<CaptureOutcome> coordinate(@Nonnull CaptureAttempt attempt);
    }
}
