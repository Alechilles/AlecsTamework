package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Creates and removes the visual-only saved-player model that rides the transformed player.
 */
public final class AvatarFlightRiderVisualService {
    public boolean spawn(@Nonnull Store<EntityStore> store,
                         @Nonnull Ref<EntityStore> ownerRef,
                         @Nonnull UUID ownerUuid,
                         @Nonnull TwAvatarFlightConfig config,
                         @Nullable Model savedModel) {
        TwAvatarFlightConfig.RiderVisualSettings settings = config.getRiderVisual();
        ComponentType<EntityStore, AvatarFlightRiderVisualComponent> visualType =
                AvatarFlightRiderVisualComponent.getComponentType();
        if (visualType == null) {
            return false;
        }
        remove(store, ownerRef);
        store.putComponent(ownerRef, visualType, marker(ownerUuid, null, false));
        if (!settings.isShowRider() || savedModel == null) {
            return false;
        }

        TransformComponent ownerTransform = store.getComponent(ownerRef, TransformComponent.getComponentType());
        if (ownerTransform == null || ownerTransform.getTransform() == null) {
            return false;
        }

        UUIDComponent riderUuid = UUIDComponent.randomUUID();
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.putComponent(UUIDComponent.getComponentType(), riderUuid);
        holder.putComponent(
                NetworkId.getComponentType(),
                new NetworkId(store.getExternalData().takeNextNetworkId())
        );
        holder.putComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), new NonSerialized());
        holder.putComponent(TransformComponent.getComponentType(), ownerTransform.clone());
        holder.putComponent(HeadRotation.getComponentType(), new HeadRotation(ownerTransform.getRotation()));
        Model riderModel = new Model(savedModel);
        holder.putComponent(ModelComponent.getComponentType(), new ModelComponent(riderModel));
        holder.putComponent(PersistentModel.getComponentType(), new PersistentModel(riderModel.toReference()));
        holder.putComponent(BoundingBox.getComponentType(), new BoundingBox(riderModel.getBoundingBox()));
        holder.ensureComponent(EntityModule.get().getVisibleComponentType());
        holder.putComponent(MountedComponent.getComponentType(), new MountedComponent(ownerRef,
                new Rotation3f(
                        (float) settings.getSeatOffsetX(),
                        (float) settings.getSeatOffsetY(),
                        (float) settings.getSeatOffsetZ()
                ),
                MountController.BlockMount
        ));
        AvatarFlightRiderVisualComponent riderMarker = marker(ownerUuid, riderUuid.getUuid(), true);
        holder.putComponent(visualType, riderMarker);

        store.addEntity(holder, AddReason.SPAWN);
        AvatarFlightRiderVisualComponent ownerMarker = marker(ownerUuid, riderUuid.getUuid(), false);
        store.putComponent(ownerRef, visualType, ownerMarker);
        return true;
    }

    public void remove(@Nonnull Store<EntityStore> store,
                       @Nonnull Ref<EntityStore> ownerRef) {
        ComponentType<EntityStore, AvatarFlightRiderVisualComponent> visualType =
                AvatarFlightRiderVisualComponent.getComponentType();
        if (visualType == null) {
            return;
        }
        AvatarFlightRiderVisualComponent visual = store.getComponent(ownerRef, visualType);
        if (visual == null) {
            return;
        }
        Ref<EntityStore> riderRef = resolveRiderRef(store, visual);
        if (riderRef != null && riderRef.isValid()) {
            store.removeEntity(riderRef, RemoveReason.REMOVE);
        }
        store.tryRemoveComponent(ownerRef, visualType);
    }

    @Nullable
    public static Ref<EntityStore> resolveRiderRef(@Nonnull Store<EntityStore> store,
                                                   @Nonnull AvatarFlightRiderVisualComponent visual) {
        if (visual.getRiderEntityUuid().isBlank()) {
            return null;
        }
        try {
            return store.getExternalData().getWorld().getEntityRef(UUID.fromString(visual.getRiderEntityUuid()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nonnull
    private static AvatarFlightRiderVisualComponent marker(@Nonnull UUID ownerUuid,
                                                          @Nullable UUID riderUuid,
                                                          boolean riderEntity) {
        AvatarFlightRiderVisualComponent visual = new AvatarFlightRiderVisualComponent();
        visual.setOwnerUuid(ownerUuid.toString());
        visual.setRiderEntityUuid(riderUuid == null ? "" : riderUuid.toString());
        visual.setRiderEntity(riderEntity);
        return visual;
    }
}
