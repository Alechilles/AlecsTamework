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
    private static final String ANIMATION_RUN = "Run";
    private static final String ANIMATION_STEP_RUN = "StepRun";
    private static final String ANIMATION_STEP_WALK = "StepWalk";

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
        PlayerSkinComponent skin = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (savedModel != null) {
            store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(new Model(savedModel)));
            if (skin != null) {
                skin.setNetworkOutdated();
            }
            return true;
        }
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

    @Nullable
    public Model savedModelCopy(@Nonnull UUID playerUuid) {
        Model saved = SAVED_MODELS.get(playerUuid);
        return saved == null ? null : new Model(saved);
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
        Map<String, ModelAsset.AnimationSet> enrichedAnimations = normalizedGroundedMovementAnimationSets(baseModel);
        if (animation.isPoseAnimationsEnabled()) {
            injectPoseAnimationSets(enrichedAnimations, animation);
        }
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
    private static Map<String, ModelAsset.AnimationSet> normalizedGroundedMovementAnimationSets(
            @Nonnull Model baseModel) {
        Map<String, ModelAsset.AnimationSet> animations = new LinkedHashMap<>(baseModel.getAnimationSetMap());
        ensureSprintAnimationSet(animations);
        softenStepRunFallback(animations);
        return animations;
    }

    private static void ensureSprintAnimationSet(@Nonnull Map<String, ModelAsset.AnimationSet> animations) {
        ModelAsset.AnimationSet run = animations.get(ANIMATION_RUN);
        if (run == null) {
            return;
        }
        animations.putIfAbsent("Sprint", run);
    }

    private static void softenStepRunFallback(@Nonnull Map<String, ModelAsset.AnimationSet> animations) {
        ModelAsset.AnimationSet stepRun = animations.get(ANIMATION_STEP_RUN);
        ModelAsset.AnimationSet stepWalk = animations.get(ANIMATION_STEP_WALK);
        ModelAsset.AnimationSet run = animations.get(ANIMATION_RUN);
        if (stepRun == null || stepWalk == null || run == null) {
            return;
        }
        if (usesSameFirstAnimation(stepRun, run)) {
            animations.put("StepRun", stepWalk);
        }
    }

    private static boolean usesSameFirstAnimation(@Nonnull ModelAsset.AnimationSet first,
                                                  @Nonnull ModelAsset.AnimationSet second) {
        String firstAnimation = firstAnimationPath(first);
        return firstAnimation != null && firstAnimation.equals(firstAnimationPath(second));
    }

    @Nullable
    private static String firstAnimationPath(@Nonnull ModelAsset.AnimationSet set) {
        ModelAsset.Animation[] animations = set.getAnimations();
        if (animations == null || animations.length == 0 || animations[0] == null) {
            return null;
        }
        return animations[0].getAnimation();
    }

    private static void injectPoseAnimationSets(
            @Nonnull Map<String, ModelAsset.AnimationSet> animations,
            @Nonnull TwAvatarFlightConfig.AnimationSettings animation) {
        for (AvatarFlightPoseAnimationCatalog.Definition definition
                : AvatarFlightPoseAnimationCatalog.standardDefinitionsFor(animation)) {
            addStandardPoseAnimation(animations, definition.id(), definition.path());
        }
    }

    private static void addStandardPoseAnimation(@Nonnull Map<String, ModelAsset.AnimationSet> animations,
                                                 @Nonnull String standardId,
                                                 @Nonnull String animationPath) {
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
