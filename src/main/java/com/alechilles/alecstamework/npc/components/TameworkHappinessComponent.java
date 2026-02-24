package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Stores shared companion happiness state for systems beyond breeding.
 */
public final class TameworkHappinessComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkHappinessComponent> CODEC = BuilderCodec.builder(
            TameworkHappinessComponent.class,
            TameworkHappinessComponent::new
    )
        .append(
            new KeyedCodec<>("ConfigId", Codec.STRING),
            TameworkHappinessComponent::setConfigId,
            TameworkHappinessComponent::getConfigId
        )
        .add()
        .append(
            new KeyedCodec<>("Value", Codec.DOUBLE),
            TameworkHappinessComponent::setValue,
            TameworkHappinessComponent::getValue
        )
        .add()
        .append(
            new KeyedCodec<>("LastUpdateMs", Codec.LONG),
            TameworkHappinessComponent::setLastUpdateMs,
            TameworkHappinessComponent::getLastUpdateMs
        )
        .add()
        .build();

    private String configId;
    private double value;
    private long lastUpdateMs;

    public TameworkHappinessComponent() {
    }

    public TameworkHappinessComponent(String configId, double value, long lastUpdateMs) {
        this.configId = configId;
        this.value = value;
        this.lastUpdateMs = lastUpdateMs;
    }

    public static ComponentType<EntityStore, TameworkHappinessComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getHappinessComponentType() : null;
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public long getLastUpdateMs() {
        return lastUpdateMs;
    }

    public void setLastUpdateMs(long lastUpdateMs) {
        this.lastUpdateMs = lastUpdateMs;
    }

    @Override
    public TameworkHappinessComponent clone() {
        return new TameworkHappinessComponent(configId, value, lastUpdateMs);
    }
}
