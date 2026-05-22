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
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            TameworkBreedingComponent::setEnabled,
            TameworkBreedingComponent::isEnabled
        )
        .add()
        .append(
            new KeyedCodec<>("CooldownUntilMs", Codec.LONG),
            TameworkBreedingComponent::setCooldownUntilMs,
            TameworkBreedingComponent::getCooldownUntilMs
        )
        .add()
        .append(
            new KeyedCodec<>("CooldownStartedAtMs", Codec.LONG),
            TameworkBreedingComponent::setCooldownStartedAtMs,
            TameworkBreedingComponent::getCooldownStartedAtMs
        )
        .add()
        .append(
            new KeyedCodec<>("CooldownDurationMs", Codec.LONG),
            TameworkBreedingComponent::setCooldownDurationMs,
            TameworkBreedingComponent::getCooldownDurationMs
        )
        .add()
        .append(
            new KeyedCodec<>("LastPartnerUuid", new UUIDBinaryCodec()),
            TameworkBreedingComponent::setLastPartnerUuid,
            TameworkBreedingComponent::getLastPartnerUuid
        )
        .add()
        .append(
            new KeyedCodec<>("ManualBreedingPlayerUuid", new UUIDBinaryCodec()),
            TameworkBreedingComponent::setManualBreedingPlayerUuid,
            TameworkBreedingComponent::getManualBreedingPlayerUuid
        )
        .add()
        .append(
            new KeyedCodec<>("ManualBreedingUntilMs", Codec.LONG),
            TameworkBreedingComponent::setManualBreedingUntilMs,
            TameworkBreedingComponent::getManualBreedingUntilMs
        )
        .add()
        .build();

    private String configId;
    private double happiness;
    private long lastHappinessUpdateMs;
    private boolean ready;
    private boolean enabled;
    private long cooldownUntilMs;
    private long cooldownStartedAtMs;
    private long cooldownDurationMs;
    private UUID lastPartnerUuid;
    private UUID manualBreedingPlayerUuid;
    private long manualBreedingUntilMs;

    public TameworkBreedingComponent() {
    }

    public TameworkBreedingComponent(String configId,
                                     double happiness,
                                     long lastHappinessUpdateMs,
                                     boolean ready,
                                     long cooldownUntilMs,
                                     UUID lastPartnerUuid) {
        this(configId, happiness, lastHappinessUpdateMs, ready, false, cooldownUntilMs, lastPartnerUuid, 0L, 0L);
    }

    public TameworkBreedingComponent(String configId,
                                     double happiness,
                                     long lastHappinessUpdateMs,
                                     boolean ready,
                                     boolean enabled,
                                     long cooldownUntilMs,
                                     UUID lastPartnerUuid) {
        this(configId, happiness, lastHappinessUpdateMs, ready, enabled, cooldownUntilMs, lastPartnerUuid, 0L, 0L);
    }

    public TameworkBreedingComponent(String configId,
                                     double happiness,
                                     long lastHappinessUpdateMs,
                                     boolean ready,
                                     boolean enabled,
                                     long cooldownUntilMs,
                                     UUID lastPartnerUuid,
                                     long cooldownStartedAtMs,
                                     long cooldownDurationMs) {
        this(
                configId,
                happiness,
                lastHappinessUpdateMs,
                ready,
                enabled,
                cooldownUntilMs,
                lastPartnerUuid,
                cooldownStartedAtMs,
                cooldownDurationMs,
                null,
                0L
        );
    }

    public TameworkBreedingComponent(String configId,
                                     double happiness,
                                     long lastHappinessUpdateMs,
                                     boolean ready,
                                     boolean enabled,
                                     long cooldownUntilMs,
                                     UUID lastPartnerUuid,
                                     long cooldownStartedAtMs,
                                     long cooldownDurationMs,
                                     UUID manualBreedingPlayerUuid,
                                     long manualBreedingUntilMs) {
        this.configId = configId;
        this.happiness = happiness;
        this.lastHappinessUpdateMs = lastHappinessUpdateMs;
        this.ready = ready;
        this.enabled = enabled;
        this.cooldownUntilMs = cooldownUntilMs;
        this.cooldownStartedAtMs = Math.max(0L, cooldownStartedAtMs);
        this.cooldownDurationMs = Math.max(0L, cooldownDurationMs);
        this.lastPartnerUuid = lastPartnerUuid;
        this.manualBreedingPlayerUuid = manualBreedingPlayerUuid;
        this.manualBreedingUntilMs = manualBreedingUntilMs;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCooldownUntilMs() {
        return cooldownUntilMs;
    }

    public void setCooldownUntilMs(long cooldownUntilMs) {
        this.cooldownUntilMs = cooldownUntilMs;
    }

    public long getCooldownStartedAtMs() {
        return cooldownStartedAtMs;
    }

    public void setCooldownStartedAtMs(long cooldownStartedAtMs) {
        this.cooldownStartedAtMs = Math.max(0L, cooldownStartedAtMs);
    }

    public long getCooldownDurationMs() {
        return cooldownDurationMs;
    }

    public void setCooldownDurationMs(long cooldownDurationMs) {
        this.cooldownDurationMs = Math.max(0L, cooldownDurationMs);
    }

    public UUID getLastPartnerUuid() {
        return lastPartnerUuid;
    }

    public void setLastPartnerUuid(UUID lastPartnerUuid) {
        this.lastPartnerUuid = lastPartnerUuid;
    }

    public UUID getManualBreedingPlayerUuid() {
        return manualBreedingPlayerUuid;
    }

    public void setManualBreedingPlayerUuid(UUID manualBreedingPlayerUuid) {
        this.manualBreedingPlayerUuid = manualBreedingPlayerUuid;
    }

    public long getManualBreedingUntilMs() {
        return manualBreedingUntilMs;
    }

    public void setManualBreedingUntilMs(long manualBreedingUntilMs) {
        this.manualBreedingUntilMs = manualBreedingUntilMs;
    }

    public void markManualBreedingReady(UUID playerUuid, long untilMs) {
        this.manualBreedingPlayerUuid = playerUuid;
        this.manualBreedingUntilMs = untilMs;
    }

    public void clearManualBreedingReady() {
        this.manualBreedingPlayerUuid = null;
        this.manualBreedingUntilMs = 0L;
    }

    public boolean isManualBreedingReadyFor(UUID playerUuid, long nowMs) {
        return playerUuid != null
                && manualBreedingPlayerUuid != null
                && manualBreedingUntilMs != 0L
                && nowMs < manualBreedingUntilMs
                && manualBreedingPlayerUuid.equals(playerUuid);
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
                enabled,
                cooldownUntilMs,
                lastPartnerUuid,
                cooldownStartedAtMs,
                cooldownDurationMs,
                manualBreedingPlayerUuid,
                manualBreedingUntilMs
        );
    }
}
