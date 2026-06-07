package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Stores the durable harvest cooldown for optimized companion harvest interactions.
 */
public final class TameworkHarvestCooldownComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkHarvestCooldownComponent> CODEC = BuilderCodec.builder(
            TameworkHarvestCooldownComponent.class,
            TameworkHarvestCooldownComponent::new
    )
        .append(
            new KeyedCodec<>("CooldownUntilMs", Codec.LONG),
            TameworkHarvestCooldownComponent::setCooldownUntilMs,
            TameworkHarvestCooldownComponent::getCooldownUntilMs
        )
        .add()
        .append(
            new KeyedCodec<>("CooldownStartedAtMs", Codec.LONG),
            TameworkHarvestCooldownComponent::setCooldownStartedAtMs,
            TameworkHarvestCooldownComponent::getCooldownStartedAtMs
        )
        .add()
        .append(
            new KeyedCodec<>("CooldownDurationMs", Codec.LONG),
            TameworkHarvestCooldownComponent::setCooldownDurationMs,
            TameworkHarvestCooldownComponent::getCooldownDurationMs
        )
        .add()
        .build();

    private long cooldownUntilMs;
    private long cooldownStartedAtMs;
    private long cooldownDurationMs;

    public static ComponentType<EntityStore, TameworkHarvestCooldownComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getHarvestCooldownComponentType() : null;
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
        this.cooldownStartedAtMs = cooldownStartedAtMs;
    }

    public long getCooldownDurationMs() {
        return cooldownDurationMs;
    }

    public void setCooldownDurationMs(long cooldownDurationMs) {
        this.cooldownDurationMs = Math.max(0L, cooldownDurationMs);
    }

    public boolean isCooldownActive(long nowMs) {
        return cooldownUntilMs != 0L && nowMs < cooldownUntilMs;
    }

    @Override
    public TameworkHarvestCooldownComponent clone() {
        TameworkHarvestCooldownComponent clone = new TameworkHarvestCooldownComponent();
        clone.setCooldownUntilMs(cooldownUntilMs);
        clone.setCooldownStartedAtMs(cooldownStartedAtMs);
        clone.setCooldownDurationMs(cooldownDurationMs);
        return clone;
    }
}
