package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies and removes avatar-flight rider visuals from the transformed player model.
 */
public final class AvatarFlightRiderVisualService {
    private static final String RIDER_PROXY_MODEL =
            "NPC/Tamework/AvatarFlight/RiderProxy.blockymodel";
    private static final String RIDER_PROXY_TEXTURE =
            "NPC/Tamework/AvatarFlight/RiderProxy_Texture.png";

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
        if (!settings.isShowRider()) {
            return false;
        }

        return appendRiderAttachment(store, ownerRef, savedModel);
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

    private static boolean appendRiderAttachment(@Nonnull Store<EntityStore> store,
                                                 @Nonnull Ref<EntityStore> ownerRef,
                                                 @Nullable Model savedModel) {
        ModelComponent component = store.getComponent(ownerRef, ModelComponent.getComponentType());
        if (component == null || component.getModel() == null) {
            return false;
        }
        Model withRider = modelWithRiderAttachment(component.getModel(), savedModel);
        if (withRider == null) {
            return false;
        }
        store.putComponent(ownerRef, ModelComponent.getComponentType(), new ModelComponent(withRider));
        return true;
    }

    @Nullable
    private static Model modelWithRiderAttachment(@Nonnull Model baseModel,
                                                 @Nullable Model savedModel) {
        ModelAttachment riderAttachment = riderAttachment(savedModel);
        if (riderAttachment == null) {
            return null;
        }
        ModelAttachment[] baseAttachments = baseModel.getAttachments();
        ModelAttachment[] attachments = baseAttachments == null
                ? new ModelAttachment[1]
                : Arrays.copyOf(baseAttachments, baseAttachments.length + 1);
        attachments[attachments.length - 1] = riderAttachment;
        return new Model(
                baseModel.getModelAssetId(),
                baseModel.getScale(),
                baseModel.getRandomAttachmentIds(),
                attachments,
                baseModel.getBoundingBox(),
                baseModel.getModel(),
                baseModel.getTexture(),
                baseModel.getGradientSet(),
                baseModel.getGradientId(),
                baseModel.getEyeHeight(),
                baseModel.getCrouchOffset(),
                baseModel.getSittingOffset(),
                baseModel.getSleepingOffset(),
                baseModel.getAnimationSetMap(),
                baseModel.getCamera(),
                baseModel.getLight(),
                baseModel.getParticles(),
                baseModel.getTrails(),
                baseModel.getPhysicsValues(),
                baseModel.getDetailBoxes(),
                baseModel.getPhobia(),
                baseModel.getPhobiaModelAssetId()
        );
    }

    @Nullable
    private static ModelAttachment riderAttachment(@Nullable Model savedModel) {
        String gradientSet = savedModel == null ? null : savedModel.getGradientSet();
        String gradientId = savedModel == null ? null : savedModel.getGradientId();
        return new ModelAttachment(
                RIDER_PROXY_MODEL,
                RIDER_PROXY_TEXTURE,
                gradientSet,
                gradientId,
                1.0
        );
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
