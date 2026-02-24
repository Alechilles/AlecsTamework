package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Tracks companion life-stage and optional growth-scaling progression.
 */
public final class TameworkLifeStageComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkLifeStageComponent> CODEC = BuilderCodec.builder(
            TameworkLifeStageComponent.class,
            TameworkLifeStageComponent::new
    )
        .append(
            new KeyedCodec<>("Stage", Codec.STRING),
            TameworkLifeStageComponent::setStage,
            TameworkLifeStageComponent::getStage
        )
        .add()
        .append(
            new KeyedCodec<>("BornAtMs", Codec.LONG),
            TameworkLifeStageComponent::setBornAtMs,
            TameworkLifeStageComponent::getBornAtMs
        )
        .add()
        .append(
            new KeyedCodec<>("AdolescentAtMs", Codec.LONG),
            TameworkLifeStageComponent::setAdolescentAtMs,
            TameworkLifeStageComponent::getAdolescentAtMs
        )
        .add()
        .append(
            new KeyedCodec<>("AdultAtMs", Codec.LONG),
            TameworkLifeStageComponent::setAdultAtMs,
            TameworkLifeStageComponent::getAdultAtMs
        )
        .add()
        .append(
            new KeyedCodec<>("BabyScale", Codec.DOUBLE),
            TameworkLifeStageComponent::setBabyScale,
            TameworkLifeStageComponent::getBabyScale
        )
        .add()
        .append(
            new KeyedCodec<>("AdolescentScale", Codec.DOUBLE),
            TameworkLifeStageComponent::setAdolescentScale,
            TameworkLifeStageComponent::getAdolescentScale
        )
        .add()
        .append(
            new KeyedCodec<>("AdultScale", Codec.DOUBLE),
            TameworkLifeStageComponent::setAdultScale,
            TameworkLifeStageComponent::getAdultScale
        )
        .add()
        .append(
            new KeyedCodec<>("GrowthScalingEnabled", Codec.BOOLEAN),
            TameworkLifeStageComponent::setGrowthScalingEnabled,
            TameworkLifeStageComponent::isGrowthScalingEnabled
        )
        .add()
        .build();

    private String stage = "Adult";
    private long bornAtMs;
    private long adolescentAtMs;
    private long adultAtMs;
    private double babyScale = 0.55;
    private double adolescentScale = 0.80;
    private double adultScale = 1.00;
    private boolean growthScalingEnabled;

    public TameworkLifeStageComponent() {
    }

    public TameworkLifeStageComponent(String stage,
                                      long bornAtMs,
                                      long adolescentAtMs,
                                      long adultAtMs,
                                      double babyScale,
                                      double adolescentScale,
                                      double adultScale,
                                      boolean growthScalingEnabled) {
        this.stage = stage;
        this.bornAtMs = bornAtMs;
        this.adolescentAtMs = adolescentAtMs;
        this.adultAtMs = adultAtMs;
        this.babyScale = babyScale;
        this.adolescentScale = adolescentScale;
        this.adultScale = adultScale;
        this.growthScalingEnabled = growthScalingEnabled;
    }

    public static ComponentType<EntityStore, TameworkLifeStageComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getLifeStageComponentType() : null;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public long getBornAtMs() {
        return bornAtMs;
    }

    public void setBornAtMs(long bornAtMs) {
        this.bornAtMs = bornAtMs;
    }

    public long getAdolescentAtMs() {
        return adolescentAtMs;
    }

    public void setAdolescentAtMs(long adolescentAtMs) {
        this.adolescentAtMs = adolescentAtMs;
    }

    public long getAdultAtMs() {
        return adultAtMs;
    }

    public void setAdultAtMs(long adultAtMs) {
        this.adultAtMs = adultAtMs;
    }

    public double getBabyScale() {
        return babyScale;
    }

    public void setBabyScale(double babyScale) {
        this.babyScale = babyScale;
    }

    public double getAdolescentScale() {
        return adolescentScale;
    }

    public void setAdolescentScale(double adolescentScale) {
        this.adolescentScale = adolescentScale;
    }

    public double getAdultScale() {
        return adultScale;
    }

    public void setAdultScale(double adultScale) {
        this.adultScale = adultScale;
    }

    public boolean isGrowthScalingEnabled() {
        return growthScalingEnabled;
    }

    public void setGrowthScalingEnabled(boolean growthScalingEnabled) {
        this.growthScalingEnabled = growthScalingEnabled;
    }

    @Override
    public TameworkLifeStageComponent clone() {
        return new TameworkLifeStageComponent(
                stage,
                bornAtMs,
                adolescentAtMs,
                adultAtMs,
                babyScale,
                adolescentScale,
                adultScale,
                growthScalingEnabled
        );
    }
}
