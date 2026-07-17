package com.alechilles.alecstamework.effects;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class TameworkEntityEffectService {
    private static final Set<String> MISSING_EFFECT_WARNINGS = ConcurrentHashMap.newKeySet();

    private TameworkEntityEffectService() {
    }

    public static boolean applyEffect(@Nullable Ref<EntityStore> targetRef,
                                      @Nullable String effectId,
                                      @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (targetRef == null || !targetRef.isValid() || effectId == null || effectId.isBlank()) {
            return false;
        }

        ComponentType<EntityStore, EffectControllerComponent> effectType = EffectControllerComponent.getComponentType();
        if (effectType == null) {
            return false;
        }

        EffectControllerComponent effectController = componentAccessor.getComponent(targetRef, effectType);
        if (effectController == null) {
            return false;
        }

        IndexedLookupTableAssetMap<String, EntityEffect> effectAssetMap = EntityEffect.getAssetMap();
        if (effectAssetMap == null) {
            return false;
        }

        int effectIndex = effectAssetMap.getIndex(effectId);
        if (effectIndex == Integer.MIN_VALUE) {
            warnMissingEffectAsset(effectId);
            return false;
        }

        EntityEffect effect = effectAssetMap.getAsset(effectIndex);
        if (effect == null) {
            warnMissingEffectAsset(effectId);
            return false;
        }

        boolean added = effectController.addEffect(targetRef, effectIndex, effect, componentAccessor);
        if (added && EntityUtils.getEntity(targetRef, componentAccessor) instanceof NPCEntity npcEntity) {
            npcEntity.invalidateCachedHorizontalSpeedMultiplier();
        }
        return added;
    }

    public static boolean hasActiveEffect(@Nullable EffectControllerComponent effectController, @Nullable String effectId) {
        if (effectController == null || effectId == null || effectId.isBlank()) {
            return false;
        }

        IndexedLookupTableAssetMap<String, EntityEffect> effectAssetMap = EntityEffect.getAssetMap();
        if (effectAssetMap == null) {
            return false;
        }

        int effectIndex = effectAssetMap.getIndex(effectId);
        if (effectIndex == Integer.MIN_VALUE) {
            return false;
        }

        Int2ObjectMap<?> activeEffects = effectController.getActiveEffects();
        return activeEffects != null && activeEffects.containsKey(effectIndex);
    }

    public static boolean removeEffect(@Nullable Ref<EntityStore> targetRef,
                                       @Nullable String effectId,
                                       @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (targetRef == null || !targetRef.isValid() || effectId == null || effectId.isBlank()) {
            return false;
        }
        ComponentType<EntityStore, EffectControllerComponent> effectType = EffectControllerComponent.getComponentType();
        IndexedLookupTableAssetMap<String, EntityEffect> effectAssetMap = EntityEffect.getAssetMap();
        if (effectType == null || effectAssetMap == null) {
            return false;
        }
        EffectControllerComponent effectController = componentAccessor.getComponent(targetRef, effectType);
        int effectIndex = effectAssetMap.getIndex(effectId);
        if (effectController == null || effectIndex == Integer.MIN_VALUE) {
            return false;
        }
        if (!hasActiveEffect(effectController, effectId)) {
            return true;
        }
        effectController.removeEffect(targetRef, effectIndex, componentAccessor);
        return true;
    }

    private static void warnMissingEffectAsset(@Nonnull String effectId) {
        if (!MISSING_EFFECT_WARNINGS.add(effectId)) {
            return;
        }
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.WARNING).log("Missing entity effect asset: " + effectId);
        }
    }
}
