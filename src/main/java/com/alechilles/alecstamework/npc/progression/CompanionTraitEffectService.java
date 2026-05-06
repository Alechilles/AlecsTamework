package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.internal.TraitEffectRuntime;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * Applies trait-driven EntityEffect state (for example, movement speed).
 */
public final class CompanionTraitEffectService {
    private static final String MOVE_SPEED_MULTIPLIER_EFFECT_KEY = "MoveSpeedMultiplier";
    private static final Set<String> MISSING_EFFECT_WARNINGS = ConcurrentHashMap.newKeySet();

    private CompanionTraitEffectService() {
    }

    public static void applyTraitEffects(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        applyMoveSpeedEffect(npcRef, store);
        applyRegisteredTraitEffects(npcRef, store);
    }

    private static void applyRegisteredTraitEffects(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        Tamework instance = Tamework.getInstance();
        if (instance == null) {
            return;
        }
        TraitEffectRuntime runtime = instance.getTraitEffectRuntime();
        if (runtime != null) {
            runtime.applyRegisteredEffects(npcRef, store);
        }
    }

    private static void applyMoveSpeedEffect(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, EffectControllerComponent> effectType = EffectControllerComponent.getComponentType();
        if (effectType == null) {
            return;
        }
        EffectControllerComponent effectController = store.getComponent(npcRef, effectType);
        if (effectController == null) {
            return;
        }

        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                MOVE_SPEED_MULTIPLIER_EFFECT_KEY,
                1.0
        );
        String desiredEffectId = TraitMoveSpeedEffectResolver.resolveMoveSpeedEffectId(multiplier);
        boolean removedAny = removeExistingMoveSpeedEffects(effectController, npcRef, store, desiredEffectId);
        if (desiredEffectId == null) {
            if (removedAny) {
                invalidateNpcSpeedCache(npcRef, store);
            }
            return;
        }

        IndexedLookupTableAssetMap<String, EntityEffect> effectAssetMap = EntityEffect.getAssetMap();
        if (effectAssetMap == null) {
            return;
        }
        int effectIndex = effectAssetMap.getIndex(desiredEffectId);
        if (effectIndex == Integer.MIN_VALUE) {
            warnMissingEffectAsset(desiredEffectId);
            if (removedAny) {
                invalidateNpcSpeedCache(npcRef, store);
            }
            return;
        }
        Int2ObjectMap<ActiveEntityEffect> activeEffects = effectController.getActiveEffects();
        if (activeEffects != null && activeEffects.containsKey(effectIndex)) {
            if (removedAny) {
                invalidateNpcSpeedCache(npcRef, store);
            }
            return;
        }

        EntityEffect effect = effectAssetMap.getAsset(effectIndex);
        if (effect == null) {
            warnMissingEffectAsset(desiredEffectId);
            if (removedAny) {
                invalidateNpcSpeedCache(npcRef, store);
            }
            return;
        }

        boolean added = effectController.addEffect(npcRef, effectIndex, effect, store);
        if (added || removedAny) {
            invalidateNpcSpeedCache(npcRef, store);
        }
    }

    private static boolean removeExistingMoveSpeedEffects(EffectControllerComponent effectController,
                                                          Ref<EntityStore> npcRef,
                                                          Store<EntityStore> store,
                                                          @Nullable String keepEffectId) {
        Int2ObjectMap<ActiveEntityEffect> activeEffects = effectController.getActiveEffects();
        if (activeEffects == null || activeEffects.isEmpty()) {
            return false;
        }
        IndexedLookupTableAssetMap<String, EntityEffect> effectAssetMap = EntityEffect.getAssetMap();
        if (effectAssetMap == null) {
            return false;
        }
        int[] activeIndexes = effectController.getActiveEffectIndexes();
        if (activeIndexes == null || activeIndexes.length == 0) {
            return false;
        }
        boolean removedAny = false;
        for (int activeIndex : activeIndexes) {
            EntityEffect activeEffect = effectAssetMap.getAsset(activeIndex);
            if (activeEffect == null) {
                continue;
            }
            String activeId = activeEffect.getId();
            if (!TraitMoveSpeedEffectResolver.isTraitMoveSpeedEffectId(activeId)) {
                continue;
            }
            if (keepEffectId != null && keepEffectId.equals(activeId)) {
                continue;
            }
            effectController.removeEffect(npcRef, activeIndex, store);
            removedAny = true;
        }
        return removedAny;
    }

    private static void invalidateNpcSpeedCache(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null) {
            npc.invalidateCachedHorizontalSpeedMultiplier();
        }
    }

    private static void warnMissingEffectAsset(String effectId) {
        if (!MISSING_EFFECT_WARNINGS.add(effectId)) {
            return;
        }
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.WARNING).log(
                    "Missing move-speed trait effect asset: " + effectId + ". Swiftness trait speed bonus will be skipped."
            );
        }
    }
}
