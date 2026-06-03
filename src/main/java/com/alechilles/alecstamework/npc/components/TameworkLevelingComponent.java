package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Stores persistent XP and level state for a companion.
 */
public final class TameworkLevelingComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkLevelingComponent> CODEC = BuilderCodec.builder(
            TameworkLevelingComponent.class,
            TameworkLevelingComponent::new
    )
            .append(
                    new KeyedCodec<>("ConfigId", Codec.STRING),
                    TameworkLevelingComponent::setConfigId,
                    TameworkLevelingComponent::getConfigId
            )
            .add()
            .append(
                    new KeyedCodec<>("Level", Codec.INTEGER),
                    TameworkLevelingComponent::setLevel,
                    TameworkLevelingComponent::getLevel
            )
            .add()
            .append(
                    new KeyedCodec<>("CurrentXp", Codec.DOUBLE),
                    TameworkLevelingComponent::setCurrentXp,
                    TameworkLevelingComponent::getCurrentXp
            )
            .add()
            .append(
                    new KeyedCodec<>("TotalXp", Codec.DOUBLE),
                    TameworkLevelingComponent::setTotalXp,
                    TameworkLevelingComponent::getTotalXp
            )
            .add()
            .append(
                    new KeyedCodec<>("LastFeedXpAwardedAtMs", Codec.LONG),
                    TameworkLevelingComponent::setLastFeedXpAwardedAtMs,
                    TameworkLevelingComponent::getLastFeedXpAwardedAtMs
            )
            .add()
            .build();

    private String configId;
    private int level = 1;
    private double currentXp;
    private double totalXp;
    private long lastFeedXpAwardedAtMs;

    public TameworkLevelingComponent() {
    }

    public TameworkLevelingComponent(String configId, int level, double currentXp, double totalXp) {
        this(configId, level, currentXp, totalXp, 0L);
    }

    public TameworkLevelingComponent(String configId, int level, double currentXp, double totalXp, long lastFeedXpAwardedAtMs) {
        this.configId = configId;
        setLevel(level);
        setCurrentXp(currentXp);
        setTotalXp(totalXp);
        setLastFeedXpAwardedAtMs(lastFeedXpAwardedAtMs);
    }

    public static ComponentType<EntityStore, TameworkLevelingComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getLevelingComponentType() : null;
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = Math.max(1, level);
    }

    public double getCurrentXp() {
        return currentXp;
    }

    public void setCurrentXp(double currentXp) {
        this.currentXp = sanitizeXp(currentXp);
    }

    public double getTotalXp() {
        return totalXp;
    }

    public void setTotalXp(double totalXp) {
        this.totalXp = sanitizeXp(totalXp);
    }

    public long getLastFeedXpAwardedAtMs() {
        return lastFeedXpAwardedAtMs;
    }

    public void setLastFeedXpAwardedAtMs(long lastFeedXpAwardedAtMs) {
        this.lastFeedXpAwardedAtMs = Math.max(0L, lastFeedXpAwardedAtMs);
    }

    @Override
    public TameworkLevelingComponent clone() {
        return new TameworkLevelingComponent(configId, level, currentXp, totalXp, lastFeedXpAwardedAtMs);
    }

    private static double sanitizeXp(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 0.0;
        }
        return value;
    }
}
