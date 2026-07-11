package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Admission;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.SpawnAttempt;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Owning-world-thread adapter for spawning a durably claimed managed-coop release projection.
 *
 * <p>Live lookup and spawning finish synchronously in {@link #release}. The returned persistence
 * future retains only immutable claim/UUID data. Its presentation dispatcher must re-resolve the
 * resident, entity reference, NPC, and store on the owning world thread before invoking
 * {@link PlannedNpcProjectionPostAddService} or equivalent presentation work.</p>
 */
public final class ManagedCoopReleaseRuntimeAdapter {
    /** Immutable placement copied into fresh Hytale value objects immediately before spawn. */
    public record SpawnPlacement(double x,
                                 double y,
                                 double z,
                                 float pitch,
                                 float yaw,
                                 float roll) {
        public SpawnPlacement {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(pitch) || !Float.isFinite(yaw)
                    || !Float.isFinite(roll)) {
                throw new IllegalArgumentException("spawn placement must be finite");
            }
        }
    }

    /** Stable identity the live guard must check synchronously against the current world. */
    public record LiveIdentityRequest(@Nonnull String operationId,
                                      @Nonnull String profileId,
                                      @Nonnull String projectionKind,
                                      @Nonnull UUID plannedTargetUuid,
                                      @Nonnull String authoritySlotKey,
                                      @Nonnull UUID sourceNpcUuid,
                                      long operationGeneration) {
    }

    /** Typed outcome of planned-UUID, profile-alias, and projection-marker lookup. */
    public enum LiveIdentityStatus {
        CLEAR_TO_SPAWN,
        MATCHING_MARKED_PROJECTION,
        CONFLICT,
        LOOKUP_FAILED
    }

    /**
     * A clear result means both that the planned UUID is absent and no live profile alias exists.
     * A matching result means the sole live alias uses the planned UUID and exact expected marker.
     */
    public record LiveIdentityDecision(@Nonnull LiveIdentityStatus status,
                                       @Nullable UUID observedTargetUuid,
                                       @Nullable String detail) {
        public LiveIdentityDecision {
            Objects.requireNonNull(status, "status");
            if (status == LiveIdentityStatus.MATCHING_MARKED_PROJECTION
                    && observedTargetUuid == null) {
                throw new IllegalArgumentException("matching projection UUID is required");
            }
            if (status != LiveIdentityStatus.MATCHING_MARKED_PROJECTION
                    && observedTargetUuid != null) {
                throw new IllegalArgumentException("unexpected observed projection UUID");
            }
        }

        @Nonnull
        public static LiveIdentityDecision clearToSpawn() {
            return new LiveIdentityDecision(LiveIdentityStatus.CLEAR_TO_SPAWN, null, null);
        }

        @Nonnull
        public static LiveIdentityDecision matching(@Nonnull UUID observedTargetUuid) {
            return new LiveIdentityDecision(
                    LiveIdentityStatus.MATCHING_MARKED_PROJECTION,
                    Objects.requireNonNull(observedTargetUuid, "observedTargetUuid"),
                    null
            );
        }

        @Nonnull
        public static LiveIdentityDecision conflict(@Nonnull String detail) {
            return new LiveIdentityDecision(
                    LiveIdentityStatus.CONFLICT, null, requireText(detail, "detail"));
        }

        @Nonnull
        public static LiveIdentityDecision lookupFailed(@Nonnull String detail) {
            return new LiveIdentityDecision(
                    LiveIdentityStatus.LOOKUP_FAILED, null, requireText(detail, "detail"));
        }
    }

    /** Performs live UUID/profile/marker inspection while the owning store is borrowed. */
    @FunctionalInterface
    public interface LiveIdentityGuard {
        @Nonnull
        LiveIdentityDecision inspect(
                @Nonnull LiveIdentityRequest request,
                @Nonnull Store<EntityStore> owningStore);
    }

    /** Fails closed if the caller is not currently on the store's owning world thread. */
    @FunctionalInterface
    public interface OwningWorldThreadGuard {
        boolean isOwningWorldThread(@Nonnull Store<EntityStore> owningStore);
    }

    private final CoopResidentStateSnapshotCodec snapshotCodec;
    private final PlannedNpcProjectionSpawner projectionSpawner;
    private final ManagedCoopReleaseSpawnOrchestrator orchestrator;
    private final LiveIdentityGuard liveIdentityGuard;
    private final OwningWorldThreadGuard worldThreadGuard;
    private final LongSupplier clock;

    public ManagedCoopReleaseRuntimeAdapter(
            @Nonnull ManagedCoopReleaseProjectionCoordinator projectionCoordinator,
            @Nonnull LiveIdentityGuard liveIdentityGuard,
            @Nonnull OwningWorldThreadGuard worldThreadGuard,
            @Nonnull ManagedCoopReleaseSpawnOrchestrator.PresentationDispatcher
                    presentationDispatcher) {
        this(
                new CoopResidentStateSnapshotCodec(),
                new PlannedNpcProjectionSpawner(),
                new ManagedCoopReleaseSpawnOrchestrator(
                        Objects.requireNonNull(projectionCoordinator, "projectionCoordinator"),
                        presentationDispatcher),
                liveIdentityGuard,
                worldThreadGuard,
                System::currentTimeMillis
        );
    }

    ManagedCoopReleaseRuntimeAdapter(
            @Nonnull CoopResidentStateSnapshotCodec snapshotCodec,
            @Nonnull PlannedNpcProjectionSpawner projectionSpawner,
            @Nonnull ManagedCoopReleaseSpawnOrchestrator orchestrator,
            @Nonnull LiveIdentityGuard liveIdentityGuard,
            @Nonnull OwningWorldThreadGuard worldThreadGuard,
            @Nonnull LongSupplier clock) {
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshotCodec");
        this.projectionSpawner = Objects.requireNonNull(projectionSpawner, "projectionSpawner");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.liveIdentityGuard = Objects.requireNonNull(liveIdentityGuard, "liveIdentityGuard");
        this.worldThreadGuard = Objects.requireNonNull(worldThreadGuard, "worldThreadGuard");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Verifies, admits, and spawns synchronously. The store is never carried into the returned
     * future; post-finalization presentation is dispatched by immutable identity instead.
     */
    @Nonnull
    public CompletableFuture<Outcome> release(
            @Nonnull SpawnReady claim,
            @Nonnull ResidentRecord resident,
            @Nonnull SpawnPlacement placement,
            @Nonnull Store<EntityStore> owningStore) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(resident, "resident");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(owningStore, "owningStore");
        try {
            owningStore.assertThread();
            if (!worldThreadGuard.isOwningWorldThread(owningStore)) {
                return orchestrator.rejected("managed_coop_release_wrong_world_thread");
            }
            VerifiedSnapshot verified = verifySnapshot(claim, resident);
            TameworkProjectionIdentityComponent marker = projectionMarker(claim);
            LiveIdentityDecision decision = requireDecision(liveIdentityGuard.inspect(
                    liveIdentityRequest(claim), owningStore));
            Admission admission = admission(claim, decision);
            if (verified.projectionAlreadyCommitted()
                    && decision.status() != LiveIdentityStatus.MATCHING_MARKED_PROJECTION) {
                admission = Admission.blocked(
                        "deployed_resident_requires_matching_live_projection");
            }
            long recordedAtMs = clock.getAsLong();
            return orchestrator.coordinate(
                    claim,
                    admission,
                    () -> spawn(claim, verified.snapshot(), marker, placement, owningStore),
                    recordedAtMs
            );
        } catch (RuntimeException exception) {
            return orchestrator.rejected(failureDetail("managed_coop_release_runtime", exception));
        }
    }

    @Nonnull
    private SpawnAttempt spawn(SpawnReady claim,
                               CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                               TameworkProjectionIdentityComponent marker,
                               SpawnPlacement placement,
                               Store<EntityStore> owningStore) {
        PlannedNpcProjectionSpawner.SpawnResult result = projectionSpawner.spawn(
                new PlannedNpcProjectionSpawner.SpawnRequest(
                        snapshot.roleId(),
                        claim.plannedTargetUuid(),
                        snapshot,
                        marker,
                        new Vector3d(placement.x(), placement.y(), placement.z()),
                        new Rotation3f(
                                placement.pitch(), placement.yaw(), placement.roll()),
                        owningStore
                )
        );
        if (result == null || !result.isSuccess() || result.npc() == null) {
            String status = result != null && result.status() != null
                    ? result.status().name().toLowerCase(Locale.ROOT) : "missing_result";
            return SpawnAttempt.failed("planned_projection_spawn_" + status);
        }
        // SPAWNED is returned only after the spawner verifies component and legacy UUIDs.
        return SpawnAttempt.spawned(claim.plannedTargetUuid());
    }

    @Nonnull
    private VerifiedSnapshot verifySnapshot(SpawnReady claim, ResidentRecord resident) {
        boolean projectionAlreadyCommitted = validateClaimResidentIdentity(claim, resident);
        String snapshotJson = requireTextPreserving(resident.snapshotJson(), "snapshotJson");
        String expectedHash = requireText(resident.snapshotHash(), "snapshotHash");
        if (!expectedHash.matches("[0-9a-f]{64}")
                || !expectedHash.equals(claim.snapshotHash())
                || !expectedHash.equals(
                    ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson))) {
            throw new IllegalArgumentException("resident snapshot hash is not verified");
        }
        CoopResidentStateSnapshotCodec.DecodeResult decoded = snapshotCodec.decode(snapshotJson);
        if (decoded.status() != CoopResidentStateSnapshotCodec.Status.FOUND
                || decoded.snapshot() == null) {
            String reason = decoded.failure() != null
                    ? decoded.failure().name().toLowerCase(Locale.ROOT)
                    : decoded.status().name().toLowerCase(Locale.ROOT);
            throw new IllegalArgumentException("resident snapshot decode failed: " + reason);
        }
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot = decoded.snapshot();
        String roleId = normalize(snapshot.roleId(), "snapshot.roleId");
        boolean metadataMatches = claim.sourceNpcUuid().equals(snapshot.npcUuid())
                && normalize(claim.coopId(), "claim.coopId")
                    .equals(normalize(snapshot.coopId(), "snapshot.coopId"))
                && claim.residentSlot() == snapshot.residentSlot()
                && (resident.roleId() == null
                    || normalize(resident.roleId(), "resident.roleId").equals(roleId));
        if (!metadataMatches) {
            throw new IllegalArgumentException("resident snapshot metadata does not match claim");
        }
        return new VerifiedSnapshot(snapshot, projectionAlreadyCommitted);
    }

    private boolean validateClaimResidentIdentity(SpawnReady claim, ResidentRecord resident) {
        boolean releasingState = resident.active()
                && resident.state() == ResidentState.RELEASING
                && resident.generation() == claim.releasingResidentGeneration()
                && claim.sourceNpcUuid().equals(resident.residentUuid())
                && resident.deployedNpcUuid() == null;
        boolean deployedState = resident.active()
                && resident.state() == ResidentState.DEPLOYED
                && resident.generation() == claim.expectedResidentGeneration() + 2L
                && claim.plannedTargetUuid().equals(resident.residentUuid())
                && claim.plannedTargetUuid().equals(resident.deployedNpcUuid());
        boolean identityMatches = claim.durableState() == OperationState.SPAWN_CLAIMED
                && claim.spawnRequired()
                && claim.actualTargetUuid() == null
                && claim.operationGeneration() == 1L
                && claim.releasingResidentGeneration() == claim.expectedResidentGeneration() + 1L
                && claim.residentId().equals(resident.residentId())
                && claim.profileId().equals(resident.profileId())
                && claim.authorityKey().equals(resident.authorityKey())
                && claim.coopId().equalsIgnoreCase(resident.coopId())
                && claim.residentSlot() == resident.residentSlot()
                && claim.sourceNpcUuid().equals(resident.sourceNpcUuid())
                && resident.snapshotVersion()
                    == Integer.parseInt(CoopResidentStateSnapshotCodec.CURRENT_VERSION)
                && !claim.plannedTargetUuid().equals(claim.sourceNpcUuid());
        if ((!releasingState && !deployedState) || !identityMatches) {
            throw new IllegalArgumentException(
                    "release requires the matching committed RELEASING or DEPLOYED resident");
        }
        return deployedState;
    }

    @Nonnull
    static TameworkProjectionIdentityComponent projectionMarker(SpawnReady claim) {
        return new TameworkProjectionIdentityComponent(
                claim.profileId(),
                claim.operationId(),
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                claim.authorityKey().slotKey(claim.residentSlot()),
                claim.sourceNpcUuid(),
                claim.operationGeneration()
        );
    }

    @Nonnull
    private LiveIdentityRequest liveIdentityRequest(SpawnReady claim) {
        return new LiveIdentityRequest(
                claim.operationId(),
                claim.profileId(),
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                claim.plannedTargetUuid(),
                claim.authorityKey().slotKey(claim.residentSlot()),
                claim.sourceNpcUuid(),
                claim.operationGeneration()
        );
    }

    @Nonnull
    private Admission admission(SpawnReady claim, LiveIdentityDecision decision) {
        return switch (decision.status()) {
            case CLEAR_TO_SPAWN -> Admission.clearToSpawn();
            case MATCHING_MARKED_PROJECTION -> claim.plannedTargetUuid().equals(
                    decision.observedTargetUuid())
                    ? Admission.matching(decision.observedTargetUuid())
                    : Admission.blocked("live_marked_projection_uuid_conflict");
            case CONFLICT -> Admission.blocked(
                    decision.detail() != null
                            ? decision.detail() : "live_profile_or_uuid_conflict");
            case LOOKUP_FAILED -> Admission.blocked(
                    decision.detail() != null
                            ? decision.detail() : "live_identity_lookup_failed");
        };
    }

    @Nonnull
    private LiveIdentityDecision requireDecision(@Nullable LiveIdentityDecision decision) {
        if (decision == null || decision.status() == null) {
            throw new IllegalStateException("live identity guard returned no decision");
        }
        return decision;
    }

    @Nonnull
    private String normalize(@Nullable String value, String field) {
        return requireText(value, field).toLowerCase(Locale.ROOT);
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
    private static String failureDetail(String stage, Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return stage + "_failed:" + (message != null && !message.isBlank()
                ? message : failure.getClass().getSimpleName());
    }

    private record VerifiedSnapshot(
            CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
            boolean projectionAlreadyCommitted) {
    }
}
