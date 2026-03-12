package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkEffectActive;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import javax.annotation.Nonnull;

/**
 * Sensor that matches when a specific EntityEffect is active on the NPC.
 */
public final class SensorTameworkEffectActive extends TameworkSensorBase {
    private static final double EPSILON = 0.000001;

    private final String effectId;
    private final double minRemainingSeconds;
    private int cachedEffectIndex = Integer.MIN_VALUE;

    public SensorTameworkEffectActive(@Nonnull BuilderSensorTameworkEffectActive builder,
                                      @Nonnull BuilderSupport support) {
        super(builder);
        String configured = builder.getEffectId(support);
        this.effectId = configured == null ? "" : configured.trim();
        this.minRemainingSeconds = Math.max(0.0, builder.getMinRemainingSeconds(support));
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        if (!super.matches(ref, role, dt, store)) {
            return false;
        }
        if (effectId.isBlank()) {
            return false;
        }
        int effectIndex = resolveEffectIndex();
        if (effectIndex == Integer.MIN_VALUE) {
            return false;
        }

        ComponentType<EntityStore, EffectControllerComponent> effectType = EffectControllerComponent.getComponentType();
        if (effectType == null) {
            return false;
        }
        EffectControllerComponent effectController = store.getComponent(ref, effectType);
        if (effectController == null) {
            return false;
        }
        Int2ObjectMap<ActiveEntityEffect> activeEffects = effectController.getActiveEffects();
        if (activeEffects == null || activeEffects.isEmpty()) {
            return false;
        }

        ActiveEntityEffect activeEffect = activeEffects.get(effectIndex);
        if (activeEffect == null) {
            return false;
        }
        if (activeEffect.isInfinite()) {
            return true;
        }
        if (minRemainingSeconds <= 0.0) {
            return true;
        }

        double remainingSeconds = activeEffect.getRemainingDuration();
        return Double.isFinite(remainingSeconds)
                && remainingSeconds + EPSILON >= minRemainingSeconds;
    }

    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }

    private int resolveEffectIndex() {
        if (cachedEffectIndex != Integer.MIN_VALUE) {
            return cachedEffectIndex;
        }
        IndexedLookupTableAssetMap<String, EntityEffect> effectMap = EntityEffect.getAssetMap();
        if (effectMap == null) {
            return Integer.MIN_VALUE;
        }
        cachedEffectIndex = effectMap.getIndex(effectId);
        return cachedEffectIndex;
    }
}
