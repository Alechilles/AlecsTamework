package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies and removes avatar-flight rider visuals from the transformed player model.
 */
public final class AvatarFlightRiderVisualService {
    private static final String PLAYER_MODEL = "Characters/Player.blockymodel";
    private static final String PLAYER_MOUNT_ANCHOR_MODEL =
            "Tamework/AvatarFlight/Rider/Player_MountAnchor.blockymodel";

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
            logRiderAttachmentSkipped("show_rider_disabled", null, null);
            return false;
        }
        if (savedModel == null) {
            logRiderAttachmentSkipped("missing_saved_model", null, null);
            return false;
        }

        PlayerSkinComponent skin = store.getComponent(ownerRef, PlayerSkinComponent.getComponentType());
        ModelAttachment[] equipmentAttachments = AvatarFlightEquipmentAttachmentResolver.resolve(ownerRef, store);
        return appendRiderAttachment(store, ownerRef, savedModel, skin, equipmentAttachments);
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
                                                 @Nonnull Model savedModel,
                                                 @Nullable PlayerSkinComponent skin,
                                                 @Nonnull ModelAttachment[] equipmentAttachments) {
        ModelComponent component = store.getComponent(ownerRef, ModelComponent.getComponentType());
        if (component == null || component.getModel() == null) {
            logRiderAttachmentSkipped("missing_owner_model", null, savedModel);
            return false;
        }
        Model withRider = modelWithRiderAttachment(component.getModel(), savedModel, skin, equipmentAttachments);
        if (withRider == null) {
            logRiderAttachmentSkipped("missing_rider_model_texture", component.getModel(), savedModel);
            return false;
        }
        store.putComponent(ownerRef, ModelComponent.getComponentType(), new ModelComponent(withRider));
        logRiderAttachment(component.getModel(), savedModel, skin, equipmentAttachments);
        return true;
    }

    @Nullable
    private static Model modelWithRiderAttachment(@Nonnull Model baseModel,
                                                  @Nonnull Model savedModel,
                                                  @Nullable PlayerSkinComponent skin,
                                                  @Nonnull ModelAttachment[] equipmentAttachments) {
        ModelAttachment[] riderAttachments = riderAttachments(savedModel, skin, equipmentAttachments);
        if (riderAttachments == null) {
            return null;
        }
        ModelAttachment[] baseAttachments = baseModel.getAttachments();
        int baseCount = baseAttachments == null ? 0 : baseAttachments.length;
        ModelAttachment[] attachments = baseAttachments == null
                ? new ModelAttachment[riderAttachments.length]
                : Arrays.copyOf(baseAttachments, baseCount + riderAttachments.length);
        System.arraycopy(riderAttachments, 0, attachments, baseCount, riderAttachments.length);
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
    private static ModelAttachment[] riderAttachments(@Nonnull Model savedModel,
                                                      @Nullable PlayerSkinComponent skin,
                                                      @Nonnull ModelAttachment[] equipmentAttachments) {
        String model = savedModel.getModel();
        String texture = savedModel.getTexture();
        if (model == null || model.isBlank() || texture == null || texture.isBlank()) {
            return null;
        }
        String riderModel = riderAttachmentModel(model);
        AvatarFlightPlayerSkinAttachmentResolver.ResolvedAppearance skinAppearance =
                AvatarFlightPlayerSkinAttachmentResolver.resolve(
                        skin == null ? null : skin.getPlayerSkin(),
                        riderModel,
                        texture,
                        savedModel.getGradientSet(),
                        savedModel.getGradientId()
                );
        if (skinAppearance != null) {
            return withBody(skinAppearance.body(), skinAppearance.attachments(), equipmentAttachments);
        }
        ModelAttachment body = new ModelAttachment(
                riderModel, texture, savedModel.getGradientSet(), savedModel.getGradientId(), 1.0
        );
        ModelAttachment[] appearanceAttachments = savedModel.getAttachments();
        if (appearanceAttachments == null || appearanceAttachments.length == 0) {
            return withBody(body, new ModelAttachment[0], equipmentAttachments);
        }
        return withBody(body, appearanceAttachments, equipmentAttachments);
    }

    @Nonnull
    private static ModelAttachment[] withBody(@Nonnull ModelAttachment body,
                                              @Nonnull ModelAttachment[] appearanceAttachments,
                                              @Nonnull ModelAttachment[] equipmentAttachments) {
        ModelAttachment[] attachments = new ModelAttachment[
                appearanceAttachments.length + equipmentAttachments.length + 1
        ];
        attachments[0] = body;
        System.arraycopy(appearanceAttachments, 0, attachments, 1, appearanceAttachments.length);
        System.arraycopy(
                equipmentAttachments,
                0,
                attachments,
                appearanceAttachments.length + 1,
                equipmentAttachments.length
        );
        return attachments;
    }

    @Nonnull
    private static String riderAttachmentModel(@Nonnull String savedModelPath) {
        if (PLAYER_MODEL.equals(savedModelPath)) {
            return PLAYER_MOUNT_ANCHOR_MODEL;
        }
        return savedModelPath;
    }

    private static void logRiderAttachment(@Nonnull Model baseModel,
                                           @Nonnull Model savedModel,
                                           @Nullable PlayerSkinComponent skin,
                                           @Nonnull ModelAttachment[] equipmentAttachments) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null) {
            return;
        }
        int savedAttachmentCount = savedModel.getAttachments() == null ? 0 : savedModel.getAttachments().length;
        AvatarFlightPlayerSkinAttachmentResolver.ResolvedAppearance resolvedAppearance =
                AvatarFlightPlayerSkinAttachmentResolver.resolve(
                        skin == null ? null : skin.getPlayerSkin(),
                        riderAttachmentModel(savedModel.getModel()),
                        savedModel.getTexture(),
                        savedModel.getGradientSet(),
                        savedModel.getGradientId()
                );
        instance.getLogger().at(Level.INFO).log(String.format(
                "TameworkAvatarFlight debug: riderAttachment baseModelAsset=%s riderModelAsset=%s "
                        + "riderModel=%s attachmentModel=%s riderTexture=%s riderGradientSet=%s riderGradientId=%s "
                        + "riderAppearanceAttachmentCount=%s riderSkinAttachmentCount=%s "
                        + "riderEquipmentAttachmentCount=%s",
                baseModel.getModelAssetId(),
                savedModel.getModelAssetId(),
                savedModel.getModel(),
                riderAttachmentModel(savedModel.getModel()),
                savedModel.getTexture(),
                savedModel.getGradientSet(),
                savedModel.getGradientId(),
                savedAttachmentCount,
                resolvedAppearance == null ? 0 : resolvedAppearance.attachments().length,
                equipmentAttachments.length
        ));
    }

    private static void logRiderAttachmentSkipped(@Nonnull String reason,
                                                  @Nullable Model baseModel,
                                                  @Nullable Model savedModel) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(String.format(
                "TameworkAvatarFlight debug: riderAttachmentSkipped reason=%s baseModelAsset=%s "
                        + "riderModelAsset=%s riderModel=%s riderTexture=%s",
                reason,
                baseModel == null ? "<null>" : baseModel.getModelAssetId(),
                savedModel == null ? "<null>" : savedModel.getModelAssetId(),
                savedModel == null ? "<null>" : savedModel.getModel(),
                savedModel == null ? "<null>" : savedModel.getTexture()
        ));
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
