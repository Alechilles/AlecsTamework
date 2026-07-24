package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3fc;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3dc;

/**
 * Spawns one pre-planned NPC projection and verifies its durable identity before exposing it.
 *
 * <p>The service deliberately returns deferred post-add work without executing it. Callers retain
 * responsibility for persistence finalization and presentation effects after their own commit.</p>
 */
public final class PlannedNpcProjectionSpawner {
    private final SpawnPlanner planner;
    private final SpawnGateway gateway;

    public PlannedNpcProjectionSpawner() {
        this(new PlannedNpcProjectionSpawnPlanner(), new HytalePlannedNpcProjectionSpawnGateway());
    }

    PlannedNpcProjectionSpawner(@Nonnull SpawnPlanner planner, @Nonnull SpawnGateway gateway) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** Attempts exactly one spawn for a complete, durably identified projection request. */
    @Nonnull
    public SpawnResult spawn(@Nullable SpawnRequest request) {
        if (!isValid(request)) {
            return SpawnResult.failed(Status.INVALID_REQUEST);
        }
        TameworkProjectionIdentityComponent expectedMarker = request.projectionMarker().clone();
        GatewayResult gatewayResult;
        try {
            gatewayResult = gateway.spawn(
                    request,
                    target -> planner.installBeforeAdd(request, target)
            );
        } catch (RuntimeException exception) {
            return SpawnResult.failed(Status.SPAWN_FAILED);
        }
        if (gatewayResult == null || gatewayResult.status() == null) {
            return SpawnResult.failed(Status.SPAWN_FAILED);
        }
        if (gatewayResult.status() != Status.SPAWNED) {
            return SpawnResult.failed(gatewayResult.status());
        }
        SpawnedProjection spawned = gatewayResult.spawned();
        if (!matchesExpectedIdentity(spawned, request.plannedNpcUuid(), expectedMarker)) {
            quarantine(spawned);
            return SpawnResult.failed(Status.IDENTITY_MISMATCH);
        }
        return new SpawnResult(
                Status.SPAWNED,
                spawned.reference(),
                spawned.npc(),
                spawned.postAddWork()
        );
    }

    private boolean isValid(@Nullable SpawnRequest request) {
        if (request == null
                || request.roleId() == null
                || request.roleId().isBlank()
                || request.plannedNpcUuid() == null
                || request.fullSnapshot() == null
                || request.fullSnapshot().npcUuid() == null
                || request.projectionMarker() == null
                || request.position() == null
                || request.rotation() == null
                || request.store() == null) {
            return false;
        }
        TameworkProjectionIdentityComponent marker = request.projectionMarker();
        String kind = marker.getProjectionKind();
        return isNonBlank(marker.getProfileId())
                && isNonBlank(marker.getOperationId())
                && (TameworkProjectionIdentityComponent.KIND_RECOVERY.equals(kind)
                    || TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE.equals(kind)
                    || TameworkProjectionIdentityComponent.KIND_CAPTURE_RELEASE.equals(kind));
    }

    private boolean matchesExpectedIdentity(@Nullable SpawnedProjection spawned,
                                            @Nonnull UUID plannedNpcUuid,
                                            @Nonnull TameworkProjectionIdentityComponent expectedMarker) {
        return spawned != null
                && spawned.reference() != null
                && spawned.npc() != null
                && spawned.postAddWork() != null
                && plannedNpcUuid.equals(spawned.uuidComponentValue())
                && plannedNpcUuid.equals(spawned.legacyNpcUuid())
                && markersEqual(expectedMarker, spawned.projectionMarker());
    }

    private boolean markersEqual(@Nonnull TameworkProjectionIdentityComponent expected,
                                 @Nullable TameworkProjectionIdentityComponent actual) {
        return actual != null
                && Objects.equals(expected.getProfileId(), actual.getProfileId())
                && Objects.equals(expected.getOperationId(), actual.getOperationId())
                && Objects.equals(expected.getProjectionKind(), actual.getProjectionKind())
                && Objects.equals(expected.getSlotKey(), actual.getSlotKey())
                && Objects.equals(expected.getSourceNpcUuid(), actual.getSourceNpcUuid())
                && expected.getGeneration() == actual.getGeneration();
    }

    private void quarantine(@Nullable SpawnedProjection spawned) {
        if (spawned == null) {
            return;
        }
        try {
            gateway.quarantine(spawned);
        } catch (RuntimeException ignored) {
            // The result remains failed closed even if the best-effort despawn request itself fails.
        }
    }

    private boolean isNonBlank(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    /** All inputs required to install identity and full state before the NPC enters its store. */
    public record SpawnRequest(@Nullable String roleId,
                               @Nullable UUID plannedNpcUuid,
                               @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot,
                               @Nullable TameworkProjectionIdentityComponent projectionMarker,
                               @Nullable Vector3dc position,
                               @Nullable Rotation3fc rotation,
                               @Nullable Store<EntityStore> store) {
    }

    /** Verified spawn handles plus deferred work that the caller may apply after finalization. */
    public record SpawnResult(@Nonnull Status status,
                              @Nullable Ref<EntityStore> reference,
                              @Nullable NPCEntity npc,
                              @Nullable CoopResidentStateRestorer.PostAddWork postAddWork) {
        @Nonnull
        static SpawnResult failed(@Nonnull Status status) {
            return new SpawnResult(status, null, null, null);
        }

        public boolean isSuccess() {
            return status == Status.SPAWNED;
        }
    }

    public enum Status {
        SPAWNED,
        INVALID_REQUEST,
        ROLE_NOT_FOUND,
        SPAWN_FAILED,
        IDENTITY_MISMATCH
    }

    @FunctionalInterface
    interface SpawnPlanner {
        @Nonnull
        CoopResidentStateRestorer.PostAddWork installBeforeAdd(
                @Nonnull SpawnRequest request,
                @Nonnull PreAddTarget target);
    }

    @FunctionalInterface
    interface PreAddInstaller {
        @Nonnull
        CoopResidentStateRestorer.PostAddWork install(@Nonnull PreAddTarget target);
    }

    interface PreAddTarget {
        void replaceUuidComponent(@Nonnull UUID plannedNpcUuid);

        void setLegacyNpcUuid(@Nonnull UUID plannedNpcUuid);

        @Nonnull
        CoopResidentStateRestorer.PostAddWork restoreFullState(
                @Nonnull CoopResidentStateRestorer restorer,
                @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                @Nonnull TameworkProjectionIdentityComponent projectionMarker);
    }

    interface SpawnGateway {
        @Nonnull
        GatewayResult spawn(@Nonnull SpawnRequest request, @Nonnull PreAddInstaller installer);

        void quarantine(@Nonnull SpawnedProjection spawned);
    }

    record GatewayResult(@Nonnull Status status, @Nullable SpawnedProjection spawned) {
        @Nonnull
        static GatewayResult failed(@Nonnull Status status) {
            return new GatewayResult(status, null);
        }
    }

    record SpawnedProjection(@Nullable Ref<EntityStore> reference,
                             @Nullable NPCEntity npc,
                             @Nullable UUID uuidComponentValue,
                             @Nullable UUID legacyNpcUuid,
                             @Nullable TameworkProjectionIdentityComponent projectionMarker,
                             @Nullable CoopResidentStateRestorer.PostAddWork postAddWork) {
    }
}
