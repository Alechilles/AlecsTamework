package com.alechilles.alecstamework.damage;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TranquilizedSleepAnimationRestoreSystemTest {
    private static final int TRANQUILIZER_EFFECT_INDEX = 42;

    @Test
    void damageToTranquilizedSleepingNpcReplaysTrackedSleepAnimation() {
        AtomicReference<String> replayedAnimation = new AtomicReference<>();

        runDamage(
                "Sleep",
                (ref, animationId, componentAccessor) -> replayedAnimation.set(animationId)
        );

        assertEquals("Sleep", replayedAnimation.get());
    }

    @Test
    void damageToAwakeNpcDoesNotSuppressNormalHitAnimation() {
        AtomicReference<String> replayedAnimation = new AtomicReference<>();

        runDamage(
                "Idle",
                (ref, animationId, componentAccessor) -> replayedAnimation.set(animationId)
        );

        assertNull(replayedAnimation.get());
    }

    private static void runDamage(
            String trackedStatusAnimation,
            TranquilizedSleepAnimationRestoreSystem.AnimationEmitter emitter
    ) {
        ComponentType<EntityStore, NPCEntity> npcType = new ComponentType<>();
        ComponentType<EntityStore, EffectControllerComponent> effectType = new ComponentType<>();
        ComponentType<EntityStore, ActiveAnimationComponent> animationType = new ComponentType<>();
        EntityStore entityStore = new EntityStore(null);
        try (TestEntityComponentStore store = new TestEntityComponentStore(entityStore)) {
            Ref<EntityStore> reference = store.createReference();
            EffectControllerComponent effects = new EffectControllerComponent();
            effects.getActiveEffects().put(
                    TRANQUILIZER_EFFECT_INDEX,
                    new ActiveEntityEffect(
                            "Tw_Status_Tranquilized",
                            TRANQUILIZER_EFFECT_INDEX,
                            true,
                            false
                    )
            );
            ActiveAnimationComponent animations = new ActiveAnimationComponent();
            animations.setPlayingAnimation(AnimationSlot.Status, trackedStatusAnimation);
            store.put(reference, npcType, new NPCEntity());
            store.put(reference, effectType, effects);
            store.put(reference, animationType, animations);

            TranquilizedSleepAnimationRestoreSystem system =
                    new TranquilizedSleepAnimationRestoreSystem(
                            npcType,
                            effectType,
                            animationType,
                            () -> TRANQUILIZER_EFFECT_INDEX,
                            emitter
                    );
            Damage damage = new Damage(
                    new Damage.EnvironmentSource("test"),
                    0,
                    5.0f
            );
            store.forEachChunk(
                    Query.any(),
                    (BiConsumer<com.hypixel.hytale.component.ArchetypeChunk<EntityStore>,
                            CommandBuffer<EntityStore>>) (chunk, commandBuffer) ->
                            system.handle(0, chunk, store, commandBuffer, damage)
            );
        }
    }
}
