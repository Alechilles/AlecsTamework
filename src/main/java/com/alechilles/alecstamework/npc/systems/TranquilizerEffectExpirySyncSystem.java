package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.livingentity.LivingEntityEffectSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Collections;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Ensures the tranquilizer status effect emits a network remove update before natural duration expiry.
 *
 * <p>Vanilla effect ticking can remove duration-expired effects from the active map without queuing a dedicated
 * remove-change packet. For tranquilizer, that leaves client-side particles visible until a full entity refresh.
 * This system proactively issues {@link RemovalBehavior#DURATION} just before expiry so clients receive a clear event.
 */
public final class TranquilizerEffectExpirySyncSystem extends EntityTickingSystem<EntityStore> {
    private static final String TRANQUILIZER_EFFECT_ID = "Tw_Status_Tranquilized";
    private static final float EXPIRY_EPSILON_SECONDS = 0.0001f;
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Collections.singleton(
            new SystemDependency<>(Order.BEFORE, LivingEntityEffectSystem.class)
    );

    private int tranquilizerEffectIndex = Integer.MIN_VALUE;

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getGatherDamageGroup();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        ComponentType<EntityStore, EffectControllerComponent> effectType = EffectControllerComponent.getComponentType();
        return effectType == null ? Query.any() : Query.and(effectType);
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        if (!(dt > 0.0f)) {
            return;
        }
        int effectIndex = resolveTranquilizerEffectIndex();
        if (effectIndex == Integer.MIN_VALUE) {
            return;
        }
        ComponentType<EntityStore, EffectControllerComponent> effectType = EffectControllerComponent.getComponentType();
        if (effectType == null) {
            return;
        }
        EffectControllerComponent effectController = archetypeChunk.getComponent(index, effectType);
        if (effectController == null) {
            return;
        }
        ActiveEntityEffect tranquilized = effectController.getActiveEffects().get(effectIndex);
        if (tranquilized == null || tranquilized.isInfinite()) {
            return;
        }
        float remainingSeconds = tranquilized.getRemainingDuration();
        if (!(remainingSeconds > 0.0f)) {
            return;
        }
        if (remainingSeconds > dt + EXPIRY_EPSILON_SECONDS) {
            return;
        }
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (ref == null || !ref.isValid()) {
            return;
        }
        effectController.removeEffect(ref, effectIndex, RemovalBehavior.DURATION, commandBuffer);
    }

    private int resolveTranquilizerEffectIndex() {
        if (tranquilizerEffectIndex != Integer.MIN_VALUE) {
            return tranquilizerEffectIndex;
        }
        IndexedLookupTableAssetMap<String, EntityEffect> effectMap = EntityEffect.getAssetMap();
        if (effectMap == null) {
            return Integer.MIN_VALUE;
        }
        int resolved = effectMap.getIndex(TRANQUILIZER_EFFECT_ID);
        if (resolved != Integer.MIN_VALUE) {
            tranquilizerEffectIndex = resolved;
        }
        return resolved;
    }
}
