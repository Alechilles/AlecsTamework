package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Production bridge for Hytale's holder callback that executes before {@code Store.addEntity}. */
final class HytalePlannedNpcProjectionSpawnGateway implements PlannedNpcProjectionSpawner.SpawnGateway {

    @Nonnull
    @Override
    public PlannedNpcProjectionSpawner.GatewayResult spawn(
            @Nonnull PlannedNpcProjectionSpawner.SpawnRequest request,
            @Nonnull PlannedNpcProjectionSpawner.PreAddInstaller installer) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return PlannedNpcProjectionSpawner.GatewayResult.failed(
                    PlannedNpcProjectionSpawner.Status.SPAWN_FAILED);
        }
        int roleIndex = npcPlugin.getIndex(request.roleId().trim());
        if (roleIndex < 0) {
            return PlannedNpcProjectionSpawner.GatewayResult.failed(
                    PlannedNpcProjectionSpawner.Status.ROLE_NOT_FOUND);
        }
        AtomicReference<CoopResidentStateRestorer.PostAddWork> postAddWork = new AtomicReference<>();
        Pair<Ref<EntityStore>, NPCEntity> result;
        try {
            result = npcPlugin.spawnEntity(
                    request.store(),
                    roleIndex,
                    request.position(),
                    request.rotation(),
                    null,
                    (npc, holder, callbackStore) -> postAddWork.set(
                            installer.install(new HolderPreAddTarget(npc, holder))
                    ),
                    null
            );
        } catch (RuntimeException exception) {
            return PlannedNpcProjectionSpawner.GatewayResult.failed(
                    PlannedNpcProjectionSpawner.Status.SPAWN_FAILED);
        }
        if (result == null || result.first() == null || result.second() == null) {
            return PlannedNpcProjectionSpawner.GatewayResult.failed(
                    PlannedNpcProjectionSpawner.Status.SPAWN_FAILED);
        }
        Ref<EntityStore> reference = result.first();
        NPCEntity npc = result.second();
        return new PlannedNpcProjectionSpawner.GatewayResult(
                PlannedNpcProjectionSpawner.Status.SPAWNED,
                new PlannedNpcProjectionSpawner.SpawnedProjection(
                        reference,
                        npc,
                        readUuidComponent(reference, request),
                        npc.getUuid(),
                        readProjectionMarker(reference, request),
                        postAddWork.get()
                )
        );
    }

    @Override
    public void quarantine(@Nonnull PlannedNpcProjectionSpawner.SpawnedProjection spawned) {
        if (spawned.npc() != null) {
            spawned.npc().setToDespawn();
        }
    }

    @Nullable
    private UUID readUuidComponent(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull PlannedNpcProjectionSpawner.SpawnRequest request) {
        try {
            ComponentType<EntityStore, UUIDComponent> type = UUIDComponent.getComponentType();
            UUIDComponent component = type != null ? request.store().getComponent(reference, type) : null;
            return component != null ? component.getUuid() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    @Nullable
    private TameworkProjectionIdentityComponent readProjectionMarker(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull PlannedNpcProjectionSpawner.SpawnRequest request) {
        try {
            ComponentType<EntityStore, TameworkProjectionIdentityComponent> type =
                    TameworkProjectionIdentityComponent.getComponentType();
            return type != null ? request.store().getComponent(reference, type) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Mutations here occur in NPCPlugin's pre-add holder callback. */
    private static final class HolderPreAddTarget implements PlannedNpcProjectionSpawner.PreAddTarget {
        private final NPCEntity npc;
        private final Holder<EntityStore> holder;

        private HolderPreAddTarget(@Nonnull NPCEntity npc, @Nonnull Holder<EntityStore> holder) {
            this.npc = npc;
            this.holder = holder;
        }

        @Override
        public void replaceUuidComponent(@Nonnull UUID plannedNpcUuid) {
            ComponentType<EntityStore, UUIDComponent> type = UUIDComponent.getComponentType();
            if (type == null) {
                throw new IllegalStateException("UUIDComponent type is not registered");
            }
            holder.putComponent(type, new UUIDComponent(plannedNpcUuid));
        }

        @Override
        public void setLegacyNpcUuid(@Nonnull UUID plannedNpcUuid) {
            npc.setLegacyUUID(plannedNpcUuid);
        }

        @Nonnull
        @Override
        public CoopResidentStateRestorer.PostAddWork restoreFullState(
                @Nonnull CoopResidentStateRestorer restorer,
                @Nonnull CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot,
                @Nonnull TameworkProjectionIdentityComponent projectionMarker) {
            return restorer.restoreToHolder(holder, snapshot, projectionMarker);
        }
    }
}
