package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.UUIDBinaryCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/**
 * Stores runtime breeding state for a tamed companion.
 */
public final class TameworkBreedingComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkBreedingComponent> CODEC = BuilderCodec.builder(
            TameworkBreedingComponent.class,
            TameworkBreedingComponent::new
    )
        .append(
            new KeyedCodec<>("ConfigId", Codec.STRING),
            TameworkBreedingComponent::setConfigId,
            TameworkBreedingComponent::getConfigId
        )
        .add()
        .append(
            new KeyedCodec<>("Happiness", Codec.DOUBLE),
            TameworkBreedingComponent::setHappiness,
            TameworkBreedingComponent::getHappiness
        )
        .add()
        .append(
            new KeyedCodec<>("LastHappinessUpdateMs", Codec.LONG),
            TameworkBreedingComponent::setLastHappinessUpdateMs,
            TameworkBreedingComponent::getLastHappinessUpdateMs
        )
        .add()
        .append(
            new KeyedCodec<>("Ready", Codec.BOOLEAN),
            TameworkBreedingComponent::setReady,
            TameworkBreedingComponent::isReady
        )
        .add()
        .append(
            new KeyedCodec<>("CooldownUntilMs", Codec.LONG),
            TameworkBreedingComponent::setCooldownUntilMs,
            TameworkBreedingComponent::getCooldownUntilMs
        )
        .add()
        .append(
            new KeyedCodec<>("LastPartnerUuid", new UUIDBinaryCodec()),
            TameworkBreedingComponent::setLastPartnerUuid,
            TameworkBreedingComponent::getLastPartnerUuid
        )
        .add()
        .build();

    private String configId;
    private double happiness;
    private long lastHappinessUpdateMs;
    private boolean ready;
    private long cooldownUntilMs;
    private UUID lastPartnerUuid;

    public TameworkBreedingComponent() {
    }

    public TameworkBreedingComponent(String configId,
                                     double happiness,
                                     long lastHappinessUpdateMs,
                                     boolean ready,
                                     long cooldownUntilMs,
                                     UUID lastPartnerUuid) {
        this.configId = configId;
        this.happiness = happiness;
        this.lastHappinessUpdateMs = lastHappinessUpdateMs;
        this.ready = ready;
        this.cooldownUntilMs = cooldownUntilMs;
        this.lastPartnerUuid = lastPartnerUuid;
    }

    public static ComponentType<EntityStore, TameworkBreedingComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getBreedingComponentType() : null;
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
    }

    public double getHappiness() {
        return happiness;
    }

    public void setHappiness(double happiness) {
        this.happiness = happiness;
    }

    public long getLastHappinessUpdateMs() {
        return lastHappinessUpdateMs;
    }

    public void setLastHappinessUpdateMs(long lastHappinessUpdateMs) {
        this.lastHappinessUpdateMs = lastHappinessUpdateMs;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public long getCooldownUntilMs() {
        return cooldownUntilMs;
    }

    public void setCooldownUntilMs(long cooldownUntilMs) {
        this.cooldownUntilMs = cooldownUntilMs;
    }

    public UUID getLastPartnerUuid() {
        return lastPartnerUuid;
    }

    public void setLastPartnerUuid(UUID lastPartnerUuid) {
        this.lastPartnerUuid = lastPartnerUuid;
    }

    public boolean isCooldownActive(long nowMs) {
        return cooldownUntilMs != 0L && nowMs < cooldownUntilMs;
    }

    @Override
    public TameworkBreedingComponent clone() {
        return new TameworkBreedingComponent(
                configId,
                happiness,
                lastHappinessUpdateMs,
                ready,
                cooldownUntilMs,
                lastPartnerUuid
        );
    }
}
