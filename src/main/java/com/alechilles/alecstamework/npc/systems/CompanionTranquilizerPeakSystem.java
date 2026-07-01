package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkTranquilizerPeakComponent;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import javax.annotation.Nonnull;

/**
 * Caches the highest tranquilizer duration reached while the current tranquilizer debuff remains active.
 */
public final class CompanionTranquilizerPeakSystem extends TickingSystem<EntityStore> {
    private static final String TRANQUILIZER_EFFECT_ID = "Tw_Status_Tranquilized";
    private static final int UNRESOLVED_EFFECT_INDEX = Integer.MIN_VALUE;
    private static final long SWEEP_INTERVAL_MS = 100L;

    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, EffectControllerComponent> effectControllerType;
    private final ComponentType<EntityStore, TameworkTranquilizerPeakComponent> peakType;
    private final StoreScopedState<TickState> statesByStore = new StoreScopedState<>(TickState::new);
    private volatile int tranquilizerEffectIndex = UNRESOLVED_EFFECT_INDEX;

    public CompanionTranquilizerPeakSystem(ComponentType<EntityStore, NPCEntity> npcType,
                                           ComponentType<EntityStore, EffectControllerComponent> effectControllerType,
                                           ComponentType<EntityStore, TameworkTranquilizerPeakComponent> peakType) {
        this.npcType = npcType;
        this.effectControllerType = effectControllerType;
        this.peakType = peakType;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        if (npcType == null || effectControllerType == null || peakType == null) {
            return;
        }
        TickState tickState = statesByStore.get(store);
        long nowMs = System.currentTimeMillis();
        if (nowMs < tickState.nextSweepAtMs) {
            return;
        }
        int effectIndex = resolveTranquilizerEffectIndex();
        if (effectIndex == UNRESOLVED_EFFECT_INDEX) {
            return;
        }
        tickState.nextSweepAtMs = nowMs + SWEEP_INTERVAL_MS;
        store.forEachChunk(
                Query.and(npcType, effectControllerType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) ->
                        updateChunk(chunk, commandBuffer, store, effectIndex)
        );
    }

    private void updateChunk(@Nonnull ArchetypeChunk<EntityStore> chunk,
                             @Nonnull CommandBuffer<EntityStore> commandBuffer,
                             @Nonnull Store<EntityStore> store,
                             int effectIndex) {
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            Ref<EntityStore> reference = chunk.getReferenceTo(i);
            if (reference == null || !reference.isValid()) {
                continue;
            }
            EffectControllerComponent effectController = chunk.getComponent(i, effectControllerType);
            double currentRemaining = resolveCurrentRemainingSeconds(effectController, effectIndex);
            TameworkTranquilizerPeakComponent existing = store.getComponent(reference, peakType);
            if (currentRemaining > 0.0) {
                double priorPeak = existing != null ? existing.getPeakRemainingSeconds() : 0.0;
                double nextPeak = Math.max(priorPeak, currentRemaining);
                if (existing == null || Math.abs(nextPeak - priorPeak) > 0.01) {
                    commandBuffer.putComponent(reference, peakType, new TameworkTranquilizerPeakComponent(nextPeak));
                }
                continue;
            }
            if (existing != null) {
                commandBuffer.run(bufferStore -> bufferStore.tryRemoveComponent(reference, peakType));
            }
        }
    }

    private static double resolveCurrentRemainingSeconds(EffectControllerComponent effectController, int effectIndex) {
        if (effectController == null) {
            return 0.0;
        }
        Int2ObjectMap<ActiveEntityEffect> activeEffects = effectController.getActiveEffects();
        if (activeEffects == null || activeEffects.isEmpty()) {
            return 0.0;
        }
        ActiveEntityEffect activeEffect = activeEffects.get(effectIndex);
        if (activeEffect == null || activeEffect.isInfinite()) {
            return 0.0;
        }
        double remainingSeconds = activeEffect.getRemainingDuration();
        if (!Double.isFinite(remainingSeconds) || remainingSeconds <= 0.0) {
            return 0.0;
        }
        return remainingSeconds;
    }

    private int resolveTranquilizerEffectIndex() {
        if (tranquilizerEffectIndex != UNRESOLVED_EFFECT_INDEX) {
            return tranquilizerEffectIndex;
        }
        IndexedLookupTableAssetMap<String, EntityEffect> effectMap = EntityEffect.getAssetMap();
        if (effectMap == null) {
            return UNRESOLVED_EFFECT_INDEX;
        }
        int resolved = effectMap.getIndex(TRANQUILIZER_EFFECT_ID);
        if (resolved != UNRESOLVED_EFFECT_INDEX) {
            tranquilizerEffectIndex = resolved;
        }
        return resolved;
    }

    private static final class TickState {
        private long nextSweepAtMs;
    }
}
