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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies and restores player model state for avatar-flight test sessions.
 */
public final class AvatarFlightModelService {
    private static final ConcurrentHashMap<UUID, Model> SAVED_MODELS = new ConcurrentHashMap<>();

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
                new ModelComponent(Model.createScaledModel(modelAsset, scale)));
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
}
