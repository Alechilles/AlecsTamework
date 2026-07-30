package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwCompanionMovementConfig;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * Applies the one owned movement-speed effect that represents a companion's configured attachments and progression.
 */
public final class CompanionMovementSpeedEffectService {
    private static final String MOVE_SPEED_MULTIPLIER_EFFECT_KEY = "MoveSpeedMultiplier";
    private static final CompanionMovementSpeedResolver SPEED_RESOLVER = new CompanionMovementSpeedResolver();
    private static final CompanionMovementSpeedEffectIdResolver EFFECT_ID_RESOLVER =
            new CompanionMovementSpeedEffectIdResolver();
    private static final Set<String> MISSING_EFFECT_WARNINGS = ConcurrentHashMap.newKeySet();

    private CompanionMovementSpeedEffectService() {
    }

    /** Refreshes only the Tamework-owned movement-speed effects on a valid companion entity. */
    public static void apply(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        TwCompanionMovementConfig.ResolvedMovement movement = TwCompanionMovementConfig.resolveForRole(roleId);
        double progressionMultiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef, store, MOVE_SPEED_MULTIPLIER_EFFECT_KEY, 1.0);
        double quantizedMultiplier = SPEED_RESOLVER.resolve(
                movement,
                CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store),
                progressionMultiplier
        ).quantizedMultiplier();
        applyResolvedMultiplier(npcRef, store, roleId, quantizedMultiplier);
    }

    /**
     * Applies a movement effect from a source role and multiplier already resolved by a native mount lifecycle path.
     */
    public static void applyResolvedMultiplier(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store,
                                               @Nullable String sourceRoleId,
                                               double quantizedMultiplier) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, EffectControllerComponent> effectType = EffectControllerComponent.getComponentType();
        if (effectType == null) {
            return;
        }
        EffectControllerComponent controller = store.getComponent(npcRef, effectType);
        if (controller == null) {
            return;
        }

        String desiredEffectId = EFFECT_ID_RESOLVER.resolveManagedEffectId(quantizedMultiplier);
        boolean removed = removeOwnedEffects(controller, npcRef, store, desiredEffectId);
        boolean added = addDesiredEffect(controller, npcRef, store, desiredEffectId);
        if (removed || added) {
            invalidateNpcSpeedCache(npcRef, store);
        }
    }

    static List<String> effectIdsToRemove(@Nullable Collection<String> activeEffectIds,
                                          @Nullable String desiredEffectId) {
        if (activeEffectIds == null || activeEffectIds.isEmpty()) {
            return List.of();
        }
        List<String> removed = new ArrayList<>();
        for (String effectId : activeEffectIds) {
            boolean managed = EFFECT_ID_RESOLVER.isManagedEffectId(effectId);
            if ((managed && !effectId.equals(desiredEffectId)) || EFFECT_ID_RESOLVER.isLegacyEffectId(effectId)) {
                removed.add(effectId);
            }
        }
        return List.copyOf(removed);
    }

    @Nullable
    static String effectIdToAdd(@Nullable String desiredEffectId, boolean assetAvailable) {
        return assetAvailable ? desiredEffectId : null;
    }

    private static boolean removeOwnedEffects(EffectControllerComponent controller,
                                              Ref<EntityStore> npcRef,
                                              Store<EntityStore> store,
                                              @Nullable String desiredEffectId) {
        Int2ObjectMap<ActiveEntityEffect> activeEffects = controller.getActiveEffects();
        int[] activeIndexes = controller.getActiveEffectIndexes();
        IndexedLookupTableAssetMap<String, EntityEffect> assetMap = EntityEffect.getAssetMap();
        if (activeEffects == null || activeEffects.isEmpty() || activeIndexes == null || activeIndexes.length == 0
                || assetMap == null) {
            return false;
        }
        List<String> activeIds = new ArrayList<>();
        for (int activeIndex : activeIndexes) {
            EntityEffect effect = assetMap.getAsset(activeIndex);
            if (effect != null) {
                activeIds.add(effect.getId());
            }
        }
        Set<String> idsToRemove = new HashSet<>(effectIdsToRemove(activeIds, desiredEffectId));
        if (idsToRemove.isEmpty()) {
            return false;
        }
        boolean removed = false;
        for (int activeIndex : activeIndexes) {
            EntityEffect effect = assetMap.getAsset(activeIndex);
            if (effect != null && idsToRemove.contains(effect.getId())) {
                controller.removeEffect(npcRef, activeIndex, store);
                removed = true;
            }
        }
        return removed;
    }

    private static boolean addDesiredEffect(EffectControllerComponent controller,
                                            Ref<EntityStore> npcRef,
                                            Store<EntityStore> store,
                                            @Nullable String desiredEffectId) {
        if (desiredEffectId == null) {
            return false;
        }
        IndexedLookupTableAssetMap<String, EntityEffect> assetMap = EntityEffect.getAssetMap();
        if (assetMap == null) {
            return false;
        }
        int effectIndex = assetMap.getIndex(desiredEffectId);
        EntityEffect effect = effectIndex == Integer.MIN_VALUE ? null : assetMap.getAsset(effectIndex);
        String effectIdToAdd = effectIdToAdd(desiredEffectId, effect != null);
        if (effectIdToAdd == null) {
            warnMissingEffectAsset(npcRef, desiredEffectId);
            return false;
        }
        Int2ObjectMap<ActiveEntityEffect> activeEffects = controller.getActiveEffects();
        if (activeEffects != null && activeEffects.containsKey(effectIndex)) {
            return false;
        }
        return controller.addEffect(npcRef, effectIndex, effect, store);
    }

    private static void invalidateNpcSpeedCache(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null) {
            npc.invalidateCachedHorizontalSpeedMultiplier();
        }
    }

    private static void warnMissingEffectAsset(Ref<EntityStore> npcRef, String effectId) {
        if (!MISSING_EFFECT_WARNINGS.add(effectId)) {
            return;
        }
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.WARNING).log(
                    "Missing companion movement-speed effect asset for entity " + npcRef + ": " + effectId
            );
        }
    }
}
