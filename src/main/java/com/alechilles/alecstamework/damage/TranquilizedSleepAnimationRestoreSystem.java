package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.function.IntSupplier;

/** Restores tranquilized sleep after Hytale's fire-and-forget hit animation. */
public final class TranquilizedSleepAnimationRestoreSystem extends DamageEventSystem {
    private static final String TRANQUILIZER_EFFECT_ID = "Tw_Status_Tranquilized";
    private static final String SLEEP_ANIMATION_ID = "Sleep";
    private static final int UNRESOLVED_EFFECT_INDEX = Integer.MIN_VALUE;
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, DamageSystems.HitAnimation.class)
    );

    private final ComponentType<EntityStore, EffectControllerComponent> effectType;
    private final ComponentType<EntityStore, ActiveAnimationComponent> animationType;
    private final Query<EntityStore> query;
    private final IntSupplier tranquilizerEffectIndex;
    private final AnimationEmitter animationEmitter;

    public TranquilizedSleepAnimationRestoreSystem() {
        this(
                NPCEntity.getComponentType(),
                EffectControllerComponent.getComponentType(),
                ActiveAnimationComponent.getComponentType(),
                TranquilizedSleepAnimationRestoreSystem::resolveTranquilizerEffectIndex,
                (ref, animationId, componentAccessor) -> AnimationUtils.playAnimation(
                        ref,
                        AnimationSlot.Status,
                        animationId,
                        true,
                        componentAccessor
                )
        );
    }

    TranquilizedSleepAnimationRestoreSystem(
            ComponentType<EntityStore, NPCEntity> npcType,
            ComponentType<EntityStore, EffectControllerComponent> effectType,
            ComponentType<EntityStore, ActiveAnimationComponent> animationType,
            IntSupplier tranquilizerEffectIndex,
            AnimationEmitter animationEmitter
    ) {
        this.effectType = effectType;
        this.animationType = animationType;
        this.query = Query.and(npcType, effectType, animationType);
        this.tranquilizerEffectIndex = tranquilizerEffectIndex;
        this.animationEmitter = animationEmitter;
    }

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void handle(
            int index,
            ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer,
            Damage damage
    ) {
        if (damage == null || damage.isCancelled() || damage.getAmount() <= 0.0f) {
            return;
        }
        EffectControllerComponent effects = chunk.getComponent(index, effectType);
        ActiveAnimationComponent animations = chunk.getComponent(index, animationType);
        int effectIndex = tranquilizerEffectIndex.getAsInt();
        if (effects == null || animations == null || effectIndex == UNRESOLVED_EFFECT_INDEX
                || !effects.getActiveEffects().containsKey(effectIndex)) {
            return;
        }
        String trackedStatusAnimation =
                animations.getActiveAnimations()[AnimationSlot.Status.ordinal()];
        if (!SLEEP_ANIMATION_ID.equalsIgnoreCase(trackedStatusAnimation)) {
            return;
        }
        Ref<EntityStore> targetRef = chunk.getReferenceTo(index);
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }
        ComponentAccessor<EntityStore> componentAccessor =
                commandBuffer != null ? commandBuffer : store;
        animationEmitter.play(targetRef, trackedStatusAnimation, componentAccessor);
    }

    private static int resolveTranquilizerEffectIndex() {
        if (EntityEffect.getAssetMap() == null) {
            return UNRESOLVED_EFFECT_INDEX;
        }
        return EntityEffect.getAssetMap().getIndex(TRANQUILIZER_EFFECT_ID);
    }

    @FunctionalInterface
    interface AnimationEmitter {
        void play(
                Ref<EntityStore> ref,
                String animationId,
                ComponentAccessor<EntityStore> componentAccessor
        );
    }
}
