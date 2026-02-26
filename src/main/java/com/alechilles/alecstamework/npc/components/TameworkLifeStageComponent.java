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
            new KeyedCodec<>("FullyGrownAtMs", Codec.LONG),
            TameworkLifeStageComponent::setFullyGrownAtMs,
            TameworkLifeStageComponent::getFullyGrownAtMs
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
            new KeyedCodec<>("AdolescentSwitchScale", Codec.DOUBLE),
            TameworkLifeStageComponent::setAdolescentSwitchScale,
            TameworkLifeStageComponent::getAdolescentSwitchScale
        )
        .add()
        .append(
            new KeyedCodec<>("AdultStartScale", Codec.DOUBLE),
            TameworkLifeStageComponent::setAdultStartScale,
            TameworkLifeStageComponent::getAdultStartScale
        )
        .add()
        .append(
            new KeyedCodec<>("AdultSwitchScale", Codec.DOUBLE),
            TameworkLifeStageComponent::setAdultSwitchScale,
            TameworkLifeStageComponent::getAdultSwitchScale
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
        .append(
            new KeyedCodec<>("AdultRoleId", Codec.STRING),
            TameworkLifeStageComponent::setAdultRoleId,
            TameworkLifeStageComponent::getAdultRoleId
        )
        .add()
        .append(
            new KeyedCodec<>("BabyRoleId", Codec.STRING),
            TameworkLifeStageComponent::setBabyRoleId,
            TameworkLifeStageComponent::getBabyRoleId
        )
        .add()
        .append(
            new KeyedCodec<>("AdolescentRoleId", Codec.STRING),
            TameworkLifeStageComponent::setAdolescentRoleId,
            TameworkLifeStageComponent::getAdolescentRoleId
        )
        .add()
        .build();

    private String stage = "Adult";
    private long bornAtMs;
    private long adolescentAtMs;
    private long adultAtMs;
    private long fullyGrownAtMs;
    private double babyScale = 0.55;
    private double adolescentScale = 0.80;
    private double adolescentSwitchScale = 0.80;
    private double adultStartScale = 0.80;
    private double adultSwitchScale = 1.00;
    private double adultScale = 1.00;
    private boolean growthScalingEnabled;
    private String adultRoleId;
    private String babyRoleId;
    private String adolescentRoleId;

    public TameworkLifeStageComponent() {
    }

    public TameworkLifeStageComponent(String stage,
                                      long bornAtMs,
                                      long adolescentAtMs,
                                      long adultAtMs,
                                      long fullyGrownAtMs,
                                      double babyScale,
                                      double adolescentScale,
                                      double adolescentSwitchScale,
                                      double adultStartScale,
                                      double adultSwitchScale,
                                      double adultScale,
                                      boolean growthScalingEnabled) {
        this.stage = stage;
        this.bornAtMs = bornAtMs;
        this.adolescentAtMs = adolescentAtMs;
        this.adultAtMs = adultAtMs;
        this.fullyGrownAtMs = fullyGrownAtMs;
        this.babyScale = babyScale;
        this.adolescentScale = adolescentScale;
        this.adolescentSwitchScale = adolescentSwitchScale;
        this.adultStartScale = adultStartScale;
        this.adultSwitchScale = adultSwitchScale;
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

    public long getFullyGrownAtMs() {
        return fullyGrownAtMs;
    }

    public void setFullyGrownAtMs(long fullyGrownAtMs) {
        this.fullyGrownAtMs = fullyGrownAtMs;
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

    public double getAdolescentSwitchScale() {
        return adolescentSwitchScale;
    }

    public void setAdolescentSwitchScale(double adolescentSwitchScale) {
        this.adolescentSwitchScale = adolescentSwitchScale;
    }

    public double getAdultStartScale() {
        return adultStartScale;
    }

    public void setAdultStartScale(double adultStartScale) {
        this.adultStartScale = adultStartScale;
    }

    public double getAdultSwitchScale() {
        return adultSwitchScale;
    }

    public void setAdultSwitchScale(double adultSwitchScale) {
        this.adultSwitchScale = adultSwitchScale;
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

    public String getAdultRoleId() {
        return adultRoleId;
    }

    public void setAdultRoleId(String adultRoleId) {
        this.adultRoleId = adultRoleId;
    }

    public String getBabyRoleId() {
        return babyRoleId;
    }

    public void setBabyRoleId(String babyRoleId) {
        this.babyRoleId = babyRoleId;
    }

    public String getAdolescentRoleId() {
        return adolescentRoleId;
    }

    public void setAdolescentRoleId(String adolescentRoleId) {
        this.adolescentRoleId = adolescentRoleId;
    }

    @Override
    public TameworkLifeStageComponent clone() {
        TameworkLifeStageComponent clone = new TameworkLifeStageComponent(
                stage,
                bornAtMs,
                adolescentAtMs,
                adultAtMs,
                fullyGrownAtMs,
                babyScale,
                adolescentScale,
                adolescentSwitchScale,
                adultStartScale,
                adultSwitchScale,
                adultScale,
                growthScalingEnabled
        );
        clone.setAdultRoleId(adultRoleId);
        clone.setBabyRoleId(babyRoleId);
        clone.setAdolescentRoleId(adolescentRoleId);
        return clone;
    }
}
