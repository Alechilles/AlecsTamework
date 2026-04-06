package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Stores persistent hunger/thirst state for a companion.
 *
 * <p>{@code AppliedHappinessPenalty} is retained as a legacy field and reset to zero by runtime updates.
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
            new KeyedCodec<>("PendingNeedsDamage", Codec.DOUBLE),
            TameworkNeedsComponent::setPendingNeedsDamage,
            TameworkNeedsComponent::getPendingNeedsDamage
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
        .append(
            new KeyedCodec<>("RegenSuppressionBaselineHealth", Codec.DOUBLE),
            TameworkNeedsComponent::setRegenSuppressionBaselineHealth,
            TameworkNeedsComponent::getRegenSuppressionBaselineHealth
        )
        .add()
        .append(
            new KeyedCodec<>("RegenSuppressionAllowedHeal", Codec.DOUBLE),
            TameworkNeedsComponent::setRegenSuppressionAllowedHeal,
            TameworkNeedsComponent::getRegenSuppressionAllowedHeal
        )
        .add()
        .build();

    private String configId;
    private double hunger;
    private double thirst;
    private double appliedHappinessPenalty;
    private double pendingNeedsDamage;
    private long lastUpdateMs;
    private long lastPassiveSweepMs;
    private double regenSuppressionBaselineHealth;
    private double regenSuppressionAllowedHeal;

    public TameworkNeedsComponent() {
    }

    public TameworkNeedsComponent(String configId,
                                  double hunger,
                                  double thirst,
                                  double appliedHappinessPenalty,
                                  long lastUpdateMs,
                                  long lastPassiveSweepMs) {
        this(configId, hunger, thirst, appliedHappinessPenalty, 0.0, lastUpdateMs, lastPassiveSweepMs);
    }

    public TameworkNeedsComponent(String configId,
                                  double hunger,
                                  double thirst,
                                  double appliedHappinessPenalty,
                                  double pendingNeedsDamage,
                                  long lastUpdateMs,
                                  long lastPassiveSweepMs) {
        this.configId = configId;
        this.hunger = hunger;
        this.thirst = thirst;
        this.appliedHappinessPenalty = appliedHappinessPenalty;
        this.pendingNeedsDamage = pendingNeedsDamage;
        this.lastUpdateMs = lastUpdateMs;
        this.lastPassiveSweepMs = lastPassiveSweepMs;
        this.regenSuppressionBaselineHealth = -1.0;
        this.regenSuppressionAllowedHeal = 0.0;
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

    public double getPendingNeedsDamage() {
        return pendingNeedsDamage;
    }

    public void setPendingNeedsDamage(double pendingNeedsDamage) {
        this.pendingNeedsDamage = pendingNeedsDamage;
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

    public double getRegenSuppressionBaselineHealth() {
        return regenSuppressionBaselineHealth;
    }

    public void setRegenSuppressionBaselineHealth(double regenSuppressionBaselineHealth) {
        this.regenSuppressionBaselineHealth = regenSuppressionBaselineHealth;
    }

    public double getRegenSuppressionAllowedHeal() {
        return regenSuppressionAllowedHeal;
    }

    public void setRegenSuppressionAllowedHeal(double regenSuppressionAllowedHeal) {
        this.regenSuppressionAllowedHeal = regenSuppressionAllowedHeal;
    }

    @Override
    public TameworkNeedsComponent clone() {
        TameworkNeedsComponent copy = new TameworkNeedsComponent(
                configId,
                hunger,
                thirst,
                appliedHappinessPenalty,
                pendingNeedsDamage,
                lastUpdateMs,
                lastPassiveSweepMs
        );
        copy.setRegenSuppressionBaselineHealth(regenSuppressionBaselineHealth);
        copy.setRegenSuppressionAllowedHeal(regenSuppressionAllowedHeal);
        return copy;
    }
}
