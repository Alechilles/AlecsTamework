package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies and restores player model state for avatar-flight test sessions.
 */
public final class AvatarFlightModelService {
    private static final ConcurrentHashMap<UUID, Model> SAVED_MODELS = new ConcurrentHashMap<>();
    private static final float POSE_ANIMATION_SPEED = 1.0f;
    private static final float POSE_ANIMATION_BLEND_SECONDS = 0.75f;
    private static final float POSE_ANIMATION_WEIGHT = 1.0f;
    private static final int[] NO_FOOTSTEPS = new int[0];

    public boolean apply(@Nonnull Store<EntityStore> store,
                         @Nonnull Ref<EntityStore> ref,
                         @Nonnull UUID playerUuid,
                         @Nonnull TwAvatarFlightConfig config) {
        TwAvatarFlightConfig.ModelSettings modelSettings = config.getModel();
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(modelSettings.getModelId());
        if (modelAsset == null) {
            return false;
        }
        ModelComponent currentModel = store.getComponent(ref, ModelComponent.getComponentType());
        if (currentModel != null && currentModel.getModel() != null) {
            SAVED_MODELS.putIfAbsent(playerUuid, new Model(currentModel.getModel()));
        }
        float scale = clampScale(modelAsset, (float) modelSettings.getScale());
        store.putComponent(ref, ModelComponent.getComponentType(),
                new ModelComponent(createAvatarFlightModel(modelAsset, scale, config.getAnimation())));
        return true;
    }

    public boolean restore(@Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull UUID playerUuid) {
        Model savedModel = SAVED_MODELS.remove(playerUuid);
        if (savedModel != null) {
            store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(new Model(savedModel)));
            return true;
        }
        PlayerSkinComponent skin = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (skin == null) {
            return false;
        }
        Model fallbackModel = CosmeticsModule.get().createModel(skin.getPlayerSkin());
        store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(fallbackModel));
        skin.setNetworkOutdated();
        return true;
    }

    @Nullable
    public String savedModelId(@Nonnull UUID playerUuid) {
        Model saved = SAVED_MODELS.get(playerUuid);
        return saved == null ? null : saved.getModelAssetId();
    }

    public boolean hasSavedModel(@Nonnull UUID playerUuid) {
        return SAVED_MODELS.containsKey(playerUuid);
    }

    public void clearSavedModel(@Nonnull UUID playerUuid) {
        SAVED_MODELS.remove(playerUuid);
    }

    private static float clampScale(@Nonnull ModelAsset modelAsset, float scale) {
        return Math.max(modelAsset.getMinScale(), Math.min(modelAsset.getMaxScale(), scale));
    }

    @Nonnull
    private static Model createAvatarFlightModel(@Nonnull ModelAsset modelAsset,
                                                 float scale,
                                                 @Nonnull TwAvatarFlightConfig.AnimationSettings animation) {
        Model baseModel = Model.createScaledModel(modelAsset, scale);
        if (!animation.isPoseAnimationsEnabled()) {
            return baseModel;
        }
        Map<String, ModelAsset.AnimationSet> enrichedAnimations = injectedPoseAnimationSets(baseModel, animation);
        return new Model(
                baseModel.getModelAssetId(),
                baseModel.getScale(),
                baseModel.getRandomAttachmentIds(),
                baseModel.getAttachments(),
                baseModel.getBoundingBox(),
                baseModel.getModel(),
                baseModel.getTexture(),
                baseModel.getGradientSet(),
                baseModel.getGradientId(),
                baseModel.getEyeHeight(),
                baseModel.getCrouchOffset(),
                baseModel.getSittingOffset(),
                baseModel.getSleepingOffset(),
                enrichedAnimations,
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

    @Nonnull
    private static Map<String, ModelAsset.AnimationSet> injectedPoseAnimationSets(
            @Nonnull Model baseModel,
            @Nonnull TwAvatarFlightConfig.AnimationSettings animation) {
        Map<String, ModelAsset.AnimationSet> animations = new LinkedHashMap<>(baseModel.getAnimationSetMap());
        addStandardPoseAnimation(animations, animation.getPitchUpPoseAnimation(),
                "TameworkPitchUp", "NPC/Tamework/AvatarFlight/Animations/Origin/Pitch_Up_20.blockyanim");
        addStandardPoseAnimation(animations, animation.getPitchDownPoseAnimation(),
                "TameworkPitchDown", "NPC/Tamework/AvatarFlight/Animations/Origin/Pitch_Down_20.blockyanim");
        addStandardPoseAnimation(animations, animation.getBankLeftPoseAnimation(),
                "TameworkBankLeft", "NPC/Tamework/AvatarFlight/Animations/Origin/Bank_Left_20.blockyanim");
        addStandardPoseAnimation(animations, animation.getBankRightPoseAnimation(),
                "TameworkBankRight", "NPC/Tamework/AvatarFlight/Animations/Origin/Bank_Right_20.blockyanim");
        addStandardPoseAnimation(animations, animation.getPitchUpBankLeftPoseAnimation(),
                "TameworkPitchUpBankLeft", "NPC/Tamework/AvatarFlight/Animations/Origin/Pitch_Up_Bank_Left_20.blockyanim");
        addStandardPoseAnimation(animations, animation.getPitchUpBankRightPoseAnimation(),
                "TameworkPitchUpBankRight", "NPC/Tamework/AvatarFlight/Animations/Origin/Pitch_Up_Bank_Right_20.blockyanim");
        addStandardPoseAnimation(animations, animation.getPitchDownBankLeftPoseAnimation(),
                "TameworkPitchDownBankLeft", "NPC/Tamework/AvatarFlight/Animations/Origin/Pitch_Down_Bank_Left_20.blockyanim");
        addStandardPoseAnimation(animations, animation.getPitchDownBankRightPoseAnimation(),
                "TameworkPitchDownBankRight", "NPC/Tamework/AvatarFlight/Animations/Origin/Pitch_Down_Bank_Right_20.blockyanim");
        return animations;
    }

    private static void addStandardPoseAnimation(@Nonnull Map<String, ModelAsset.AnimationSet> animations,
                                                 @Nullable String configuredId,
                                                 @Nonnull String standardId,
                                                 @Nonnull String animationPath) {
        if (!standardId.equals(configuredId)) {
            return;
        }
        animations.putIfAbsent(standardId, new ModelAsset.AnimationSet(
                new ModelAsset.Animation[]{
                        new ModelAsset.Animation(
                                standardId,
                                animationPath,
                                POSE_ANIMATION_SPEED,
                                POSE_ANIMATION_BLEND_SECONDS,
                                true,
                                POSE_ANIMATION_WEIGHT,
                                NO_FOOTSTEPS,
                                null
                        )
                },
                ModelAsset.AnimationSet.DEFAULT_NEXT_ANIMATION_DELAY
        ));
    }
}
