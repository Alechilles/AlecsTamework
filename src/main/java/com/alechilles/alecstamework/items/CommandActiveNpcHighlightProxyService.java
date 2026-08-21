package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.compat.HytaleMountedComponentAccess;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Collection;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Creates and removes non-persistent model-particle anchors mounted above active NPCs. */
final class CommandActiveNpcHighlightProxyService {
    static final String MODEL_ASSET_ID = "Tamework_Command_Active_Highlight_Anchor";
    private static final double MAX_TRACKING_DRIFT_SQUARED = 48.0 * 48.0;

    @Nullable
    UUID create(@Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> parentNpcRef,
                @Nonnull Vector3f attachmentOffset) {
        if (store.getExternalData() == null) {
            return null;
        }
        TransformComponent parentTransform = store.getComponent(
                parentNpcRef, TransformComponent.getComponentType()
        );
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(MODEL_ASSET_ID);
        if (parentTransform == null || modelAsset == null
                || MountedComponent.getComponentType() == null) {
            return null;
        }

        UUID proxyUuid = UUID.randomUUID();
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(UUIDComponent.getComponentType(), new UUIDComponent(proxyUuid));
        holder.addComponent(
                NetworkId.getComponentType(),
                new NetworkId(store.getExternalData().takeNextNetworkId())
        );
        holder.addComponent(
                EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get()
        );
        holder.addComponent(
                TransformComponent.getComponentType(),
                new TransformComponent(
                        new Vector3d(parentTransform.getPosition()),
                        new Rotation3f(parentTransform.getRotation())
                )
        );
        holder.addComponent(
                ModelComponent.getComponentType(),
                new ModelComponent(Model.createUnitScaleModel(modelAsset))
        );
        holder.addComponent(
                MountedComponent.getComponentType(),
                HytaleMountedComponentAccess.createEntityMount(
                        parentNpcRef,
                        attachmentOffset.x,
                        attachmentOffset.y,
                        attachmentOffset.z,
                        MountController.Minecart
                )
        );
        holder.ensureComponent(EntityTrackerSystems.Visible.getComponentType());
        Ref<EntityStore> proxyRef = store.addEntity(holder, AddReason.SPAWN);
        return proxyRef != null && proxyRef.isValid() ? proxyUuid : null;
    }

    void removeAll(@Nonnull Store<EntityStore> store,
                   @Nonnull World world,
                   @Nonnull Collection<UUID> proxyUuids) {
        for (UUID proxyUuid : proxyUuids) {
            Ref<EntityStore> proxyRef = world.getEntityRef(proxyUuid);
            if (proxyRef != null && proxyRef.isValid()) {
                store.removeEntity(proxyRef, RemoveReason.REMOVE);
            }
        }
    }

    boolean requiresRecreation(@Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> parentNpcRef,
                               @Nonnull Ref<EntityStore> proxyRef) {
        TransformComponent parent = store.getComponent(
                parentNpcRef, TransformComponent.getComponentType()
        );
        TransformComponent proxy = store.getComponent(
                proxyRef, TransformComponent.getComponentType()
        );
        return parent == null || proxy == null
                || parent.getPosition().distanceSquared(proxy.getPosition())
                > MAX_TRACKING_DRIFT_SQUARED;
    }

    void syncAll(@Nonnull Store<EntityStore> store,
                 @Nonnull World world,
                 @Nonnull Collection<SyncTarget> targets) {
        for (SyncTarget target : targets) {
            Ref<EntityStore> parentRef = world.getEntityRef(target.parentNpcUuid());
            Ref<EntityStore> proxyRef = world.getEntityRef(target.proxyUuid());
            if (parentRef == null || !parentRef.isValid()
                    || proxyRef == null || !proxyRef.isValid()) {
                continue;
            }
            TransformComponent parent = store.getComponent(
                    parentRef, TransformComponent.getComponentType()
            );
            TransformComponent proxy = store.getComponent(
                    proxyRef, TransformComponent.getComponentType()
            );
            if (parent != null && proxy != null) {
                proxy.getPosition().set(parent.getPosition());
                proxy.getRotation().set(parent.getRotation());
            }
        }
    }

    record SyncTarget(@Nonnull UUID proxyUuid, @Nonnull UUID parentNpcUuid) {
    }
}
