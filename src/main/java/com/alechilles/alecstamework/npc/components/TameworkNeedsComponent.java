package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Stores persistent hunger/thirst state and applied needs-derived happiness penalty for a companion.
 */
public final class TameworkNeedsComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkNeedsComponent> CODEC = BuilderCodec.builder(
            TameworkNeedsComponent.class,
            TameworkNeedsComponent::new
    )
        .append(
            new KeyedCodec<>("ConfigId", Codec.STRING),
            TameworkNeedsComponent::setConfigId,
            TameworkNeedsComponent::getConfigId
        )
        .add()
        .append(
            new KeyedCodec<>("Hunger", Codec.DOUBLE),
            TameworkNeedsComponent::setHunger,
            TameworkNeedsComponent::getHunger
        )
        .add()
        .append(
            new KeyedCodec<>("Thirst", Codec.DOUBLE),
            TameworkNeedsComponent::setThirst,
            TameworkNeedsComponent::getThirst
        )
        .add()
        .append(
            new KeyedCodec<>("AppliedHappinessPenalty", Codec.DOUBLE),
            TameworkNeedsComponent::setAppliedHappinessPenalty,
            TameworkNeedsComponent::getAppliedHappinessPenalty
        )
        .add()
        .append(
            new KeyedCodec<>("LastUpdateMs", Codec.LONG),
            TameworkNeedsComponent::setLastUpdateMs,
            TameworkNeedsComponent::getLastUpdateMs
        )
        .add()
        .append(
            new KeyedCodec<>("LastPassiveSweepMs", Codec.LONG),
            TameworkNeedsComponent::setLastPassiveSweepMs,
            TameworkNeedsComponent::getLastPassiveSweepMs
        )
        .add()
        .build();

    private String configId;
    private double hunger;
    private double thirst;
    private double appliedHappinessPenalty;
    private long lastUpdateMs;
    private long lastPassiveSweepMs;

    public TameworkNeedsComponent() {
    }

    public TameworkNeedsComponent(String configId,
                                  double hunger,
                                  double thirst,
                                  double appliedHappinessPenalty,
                                  long lastUpdateMs,
                                  long lastPassiveSweepMs) {
        this.configId = configId;
        this.hunger = hunger;
        this.thirst = thirst;
        this.appliedHappinessPenalty = appliedHappinessPenalty;
        this.lastUpdateMs = lastUpdateMs;
        this.lastPassiveSweepMs = lastPassiveSweepMs;
    }

    public static ComponentType<EntityStore, TameworkNeedsComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getNeedsComponentType() : null;
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
    }

    public double getHunger() {
        return hunger;
    }

    public void setHunger(double hunger) {
        this.hunger = hunger;
    }

    public double getThirst() {
        return thirst;
    }

    public void setThirst(double thirst) {
        this.thirst = thirst;
    }

    public double getAppliedHappinessPenalty() {
        return appliedHappinessPenalty;
    }

    public void setAppliedHappinessPenalty(double appliedHappinessPenalty) {
        this.appliedHappinessPenalty = appliedHappinessPenalty;
    }

    public long getLastUpdateMs() {
        return lastUpdateMs;
    }

    public void setLastUpdateMs(long lastUpdateMs) {
        this.lastUpdateMs = lastUpdateMs;
    }

    public long getLastPassiveSweepMs() {
        return lastPassiveSweepMs;
    }

    public void setLastPassiveSweepMs(long lastPassiveSweepMs) {
        this.lastPassiveSweepMs = lastPassiveSweepMs;
    }

    @Override
    public TameworkNeedsComponent clone() {
        return new TameworkNeedsComponent(
                configId,
                hunger,
                thirst,
                appliedHappinessPenalty,
                lastUpdateMs,
                lastPassiveSweepMs
        );
    }
}
