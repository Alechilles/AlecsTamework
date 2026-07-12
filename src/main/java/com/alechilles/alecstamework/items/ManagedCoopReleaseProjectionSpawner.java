package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3fc;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3dc;

/**
 * Managed-release-only pre-add spawner that installs a claimed population capability.
 *
 * <p>Unlike the general projection spawner, this collaborator never despawns an uncertain
 * result. The owning adapter must probe the exact planned marker after every non-success and then
 * either adopt it or retain the admission in a degraded fail-closed state.</p>
 */
final class ManagedCoopReleaseProjectionSpawner {
    enum Status {
        SPAWNED,
        INVALID_REQUEST,
        ROLE_NOT_FOUND,
        SPAWN_FAILED,
        HOLDER_WRITE_FAILED,
        IDENTITY_MISMATCH
    }

    record Result(@Nonnull Status status,
                  boolean holderWriteSucceeded,
                  @Nullable String detail) {
        Result {
            Objects.requireNonNull(status, "status");
        }

        boolean spawned() {
            return status == Status.SPAWNED;
        }
    }

    record Request(@Nonnull String roleId,
                   @Nonnull UUID plannedNpcUuid,
                   @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                   @Nonnull TameworkProjectionIdentityComponent marker,
                   @Nonnull Vector3dc position,
                   @Nonnull Rotation3fc rotation,
                   @Nonnull Store<EntityStore> store,
                   @Nonnull HolderWriter holderWriter) {
        Request {
            roleId = requireText(roleId, "roleId");
            Objects.requireNonNull(plannedNpcUuid, "plannedNpcUuid");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(marker, "marker");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(holderWriter, "holderWriter");
        }
    }

    @FunctionalInterface
    interface HolderWriter {
        boolean write(@Nonnull Holder<EntityStore> holder);
    }

    @FunctionalInterface
    interface PreAddInstaller {
        @Nonnull
        CoopResidentStateRestorer.PostAddWork install(
                @Nonnull NPCEntity npc,
                @Nonnull Holder<EntityStore> holder);
    }

    interface SpawnGateway {
        @Nonnull
        GatewayResult spawn(@Nonnull Request request, @Nonnull PreAddInstaller installer);
    }

    /** Installs exact projection state before the population holder capability is written. */
    @FunctionalInterface
    interface PreAddStateInstaller {
        @Nonnull
        CoopResidentStateRestorer.PostAddWork install(
                @Nonnull Request request,
                @Nonnull NPCEntity npc,
                @Nonnull Holder<EntityStore> holder);
    }

    record GatewayResult(@Nonnull Status status,
                         @Nullable SpawnedProjection spawned,
                         @Nullable String detail) {
        GatewayResult {
            Objects.requireNonNull(status, "status");
        }

        static GatewayResult failed(Status status, @Nullable String detail) {
            return new GatewayResult(status, null, detail);
        }
    }

    record SpawnedProjection(@Nonnull Ref<EntityStore> reference,
                             @Nonnull NPCEntity npc,
                             @Nullable UUID uuidComponentValue,
                             @Nullable UUID legacyNpcUuid,
                             @Nullable TameworkProjectionIdentityComponent marker,
                             @Nullable CoopResidentStateRestorer.PostAddWork postAddWork) {
    }

    private final PreAddStateInstaller stateInstaller;
    private final SpawnGateway gateway;

    ManagedCoopReleaseProjectionSpawner() {
        this(defaultInstaller(new CoopResidentStateRestorer()), new HytaleSpawnGateway());
    }

    ManagedCoopReleaseProjectionSpawner(@Nonnull CoopResidentStateRestorer restorer,
                                        @Nonnull SpawnGateway gateway) {
        this(defaultInstaller(restorer), gateway);
    }

    ManagedCoopReleaseProjectionSpawner(@Nonnull PreAddStateInstaller stateInstaller,
                                        @Nonnull SpawnGateway gateway) {
        this.stateInstaller = Objects.requireNonNull(stateInstaller, "stateInstaller");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** Performs exactly one synchronous spawn attempt and reports whether holder admission ran. */
    @Nonnull
    Result spawn(@Nullable Request request) {
        if (!valid(request)) {
            return new Result(Status.INVALID_REQUEST, false,
                    "managed_release_spawn_request_invalid");
        }
        AtomicBoolean holderWriteAttempted = new AtomicBoolean();
        AtomicBoolean holderWriteSucceeded = new AtomicBoolean();
        final GatewayResult gatewayResult;
        try {
            gatewayResult = gateway.spawn(request, (npc, holder) -> {
                CoopResidentStateRestorer.PostAddWork work =
                        stateInstaller.install(request, npc, holder);
                holderWriteAttempted.set(true);
                if (!request.holderWriter().write(holder)) {
                    throw new HolderWriteRejectedException();
                }
                holderWriteSucceeded.set(true);
                return work;
            });
        } catch (RuntimeException exception) {
            Status status = holderWriteAttempted.get() && !holderWriteSucceeded.get()
                    ? Status.HOLDER_WRITE_FAILED : Status.SPAWN_FAILED;
            return new Result(status, holderWriteSucceeded.get(),
                    failureDetail("managed_release_spawn", exception));
        }
        if (gatewayResult == null) {
            return new Result(Status.SPAWN_FAILED, holderWriteSucceeded.get(),
                    "managed_release_spawn_result_missing");
        }
        Status status = gatewayResult.status();
        if (holderWriteAttempted.get() && !holderWriteSucceeded.get()) {
            status = Status.HOLDER_WRITE_FAILED;
        }
        if (status != Status.SPAWNED) {
            return new Result(status, holderWriteSucceeded.get(), gatewayResult.detail());
        }
        SpawnedProjection spawned = gatewayResult.spawned();
        if (!matches(request, spawned)) {
            return new Result(Status.IDENTITY_MISMATCH, holderWriteSucceeded.get(),
                    "managed_release_spawn_identity_mismatch");
        }
        return new Result(Status.SPAWNED, true, null);
    }

    private static boolean valid(@Nullable Request request) {
        if (request == null || request.snapshot().npcUuid() == null) {
            return false;
        }
        TameworkProjectionIdentityComponent marker = request.marker();
        return TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE.equals(
                marker.getProjectionKind())
                && marker.getProfileId() != null && !marker.getProfileId().isBlank()
                && marker.getOperationId() != null && !marker.getOperationId().isBlank();
    }

    private static boolean matches(Request request, @Nullable SpawnedProjection spawned) {
        return spawned != null
                && spawned.reference() != null
                && spawned.npc() != null
                && spawned.postAddWork() != null
                && request.plannedNpcUuid().equals(spawned.uuidComponentValue())
                && request.plannedNpcUuid().equals(spawned.legacyNpcUuid())
                && markersEqual(request.marker(), spawned.marker());
    }

    private static boolean markersEqual(TameworkProjectionIdentityComponent expected,
                                        @Nullable TameworkProjectionIdentityComponent actual) {
        return actual != null
                && Objects.equals(expected.getProfileId(), actual.getProfileId())
                && Objects.equals(expected.getOperationId(), actual.getOperationId())
                && Objects.equals(expected.getProjectionKind(), actual.getProjectionKind())
                && Objects.equals(expected.getSlotKey(), actual.getSlotKey())
                && Objects.equals(expected.getSourceNpcUuid(), actual.getSourceNpcUuid())
                && expected.getGeneration() == actual.getGeneration();
    }

    private static void installUuid(Holder<EntityStore> holder, UUID plannedNpcUuid) {
        ComponentType<EntityStore, UUIDComponent> type = UUIDComponent.getComponentType();
        if (type == null) {
            throw new IllegalStateException("UUIDComponent type is not registered");
        }
        holder.putComponent(type, new UUIDComponent(plannedNpcUuid));
    }

    private static PreAddStateInstaller defaultInstaller(
            CoopResidentStateRestorer restorer) {
        Objects.requireNonNull(restorer, "restorer");
        return (request, npc, holder) -> {
            installUuid(holder, request.plannedNpcUuid());
            npc.setLegacyUUID(request.plannedNpcUuid());
            return restorer.restoreToHolder(
                    holder, request.snapshot(), request.marker());
        };
    }

    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String failureDetail(String stage, Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return stage + "_failed:" + (message != null && !message.isBlank()
                ? message : failure != null ? failure.getClass().getSimpleName() : "unknown");
    }

    /** Production bridge for the NPCPlugin holder callback executed before store insertion. */
    private static final class HytaleSpawnGateway implements SpawnGateway {
        @Nonnull
        @Override
        public GatewayResult spawn(@Nonnull Request request,
                                   @Nonnull PreAddInstaller installer) {
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin == null) {
                return GatewayResult.failed(Status.SPAWN_FAILED,
                        "managed_release_npc_plugin_unavailable");
            }
            int roleIndex = plugin.getIndex(request.roleId());
            if (roleIndex < 0) {
                return GatewayResult.failed(Status.ROLE_NOT_FOUND,
                        "managed_release_role_not_found");
            }
            AtomicReference<CoopResidentStateRestorer.PostAddWork> postAdd =
                    new AtomicReference<>();
            Pair<Ref<EntityStore>, NPCEntity> result;
            try {
                result = plugin.spawnEntity(
                        request.store(),
                        roleIndex,
                        request.position(),
                        request.rotation(),
                        null,
                        (npc, holder, callbackStore) ->
                                postAdd.set(installer.install(npc, holder)),
                        null);
            } catch (RuntimeException exception) {
                throw exception;
            }
            if (result == null || result.first() == null || result.second() == null) {
                return GatewayResult.failed(Status.SPAWN_FAILED,
                        "managed_release_spawn_handles_missing");
            }
            Ref<EntityStore> reference = result.first();
            NPCEntity npc = result.second();
            return new GatewayResult(
                    Status.SPAWNED,
                    new SpawnedProjection(
                            reference,
                            npc,
                            readUuid(reference, request.store()),
                            npc.getUuid(),
                            readMarker(reference, request.store()),
                            postAdd.get()),
                    null);
        }

        @Nullable
        private static UUID readUuid(Ref<EntityStore> reference, Store<EntityStore> store) {
            ComponentType<EntityStore, UUIDComponent> type = UUIDComponent.getComponentType();
            UUIDComponent component = type != null ? store.getComponent(reference, type) : null;
            return component != null ? component.getUuid() : null;
        }

        @Nullable
        private static TameworkProjectionIdentityComponent readMarker(
                Ref<EntityStore> reference,
                Store<EntityStore> store) {
            ComponentType<EntityStore, TameworkProjectionIdentityComponent> type =
                    TameworkProjectionIdentityComponent.getComponentType();
            return type != null ? store.getComponent(reference, type) : null;
        }
    }

    private static final class HolderWriteRejectedException extends RuntimeException {
        private HolderWriteRejectedException() {
            super("population holder write rejected");
        }
    }
}
