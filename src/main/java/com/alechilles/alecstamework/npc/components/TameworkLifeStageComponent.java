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
        .append(
            new KeyedCodec<>("Gender", Codec.STRING),
            TameworkLifeStageComponent::setGender,
            TameworkLifeStageComponent::getGender
        )
        .add()
        .append(
            new KeyedCodec<>("AgingInitialized", Codec.BOOLEAN),
            TameworkLifeStageComponent::setAgingInitialized,
            TameworkLifeStageComponent::isAgingInitialized
        )
        .add()
        .append(
            new KeyedCodec<>("AgeProgressMs", Codec.DOUBLE),
            TameworkLifeStageComponent::setAgeProgressMs,
            TameworkLifeStageComponent::getAgeProgressMs
        )
        .add()
        .append(
            new KeyedCodec<>("ProgressionOwnerId", Codec.STRING),
            TameworkLifeStageComponent::setProgressionOwnerId,
            TameworkLifeStageComponent::getProgressionOwnerId
        )
        .add()
        .append(
            new KeyedCodec<>("ProgressionClockMs", Codec.LONG),
            TameworkLifeStageComponent::setProgressionClockMs,
            TameworkLifeStageComponent::getProgressionClockMs
        )
        .add()
        .append(
            new KeyedCodec<>("ProgressionInitialized", Codec.BOOLEAN),
            TameworkLifeStageComponent::setProgressionInitialized,
            TameworkLifeStageComponent::isProgressionInitialized
        )
        .add()
        .append(
            new KeyedCodec<>("LastProgressionWorldMs", Codec.LONG),
            TameworkLifeStageComponent::setLastProgressionWorldMs,
            TameworkLifeStageComponent::getLastProgressionWorldMs
        )
        .add()
        .append(
            new KeyedCodec<>("LifecycleNowMs", Codec.LONG),
            TameworkLifeStageComponent::setLifecycleNowMs,
            TameworkLifeStageComponent::getLifecycleNowMs
        )
        .add()
        .append(
            new KeyedCodec<>("JuvenileClockInitialized", Codec.BOOLEAN),
            TameworkLifeStageComponent::setJuvenileClockInitialized,
            TameworkLifeStageComponent::isJuvenileClockInitialized
        )
        .add()
        .append(
            new KeyedCodec<>("StoredProgressionPaused", Codec.BOOLEAN),
            TameworkLifeStageComponent::setStoredProgressionPaused,
            TameworkLifeStageComponent::isStoredProgressionPaused
        )
        .add()
        .append(new KeyedCodec<>("ActiveProgressMs", Codec.LONG),
            TameworkLifeStageComponent::setActiveProgressMs, TameworkLifeStageComponent::getActiveProgressMs)
        .add()
        .build();

    private long activeProgressMs;
    public long getActiveProgressMs() { return activeProgressMs; }
    public void setActiveProgressMs(long value) { activeProgressMs = Math.max(0, value); }

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
    private String gender;
    private boolean agingInitialized;
    private double ageProgressMs;
    private String progressionOwnerId;
    private long progressionClockMs;
    private boolean progressionInitialized;
    private long lastProgressionWorldMs;
    private long lifecycleNowMs;
    private boolean juvenileClockInitialized;
    private boolean storedProgressionPaused;

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

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public boolean isAgingInitialized() {
        return agingInitialized;
    }

    public void setAgingInitialized(boolean agingInitialized) {
        this.agingInitialized = agingInitialized;
    }

    public double getAgeProgressMs() {
        return Double.isFinite(ageProgressMs) && ageProgressMs >= 0.0 ? ageProgressMs : 0.0;
    }

    public void setAgeProgressMs(double ageProgressMs) {
        this.ageProgressMs = Double.isFinite(ageProgressMs) && ageProgressMs >= 0.0 ? ageProgressMs : 0.0;
    }

    public String getProgressionOwnerId() {
        return progressionOwnerId;
    }

    public void setProgressionOwnerId(String progressionOwnerId) {
        this.progressionOwnerId = progressionOwnerId;
    }

    public long getProgressionClockMs() {
        return progressionClockMs;
    }

    public void setProgressionClockMs(long progressionClockMs) {
        this.progressionClockMs = progressionClockMs;
    }

    public boolean isProgressionInitialized() {
        return progressionInitialized;
    }

    public void setProgressionInitialized(boolean progressionInitialized) {
        this.progressionInitialized = progressionInitialized;
    }

    public long getLastProgressionWorldMs() {
        return lastProgressionWorldMs;
    }

    public void setLastProgressionWorldMs(long lastProgressionWorldMs) {
        this.lastProgressionWorldMs = lastProgressionWorldMs;
    }

    public long getLifecycleNowMs() {
        return lifecycleNowMs;
    }

    public void setLifecycleNowMs(long lifecycleNowMs) {
        this.lifecycleNowMs = lifecycleNowMs;
    }

    public boolean isJuvenileClockInitialized() {
        return juvenileClockInitialized;
    }

    public void setJuvenileClockInitialized(boolean juvenileClockInitialized) {
        this.juvenileClockInitialized = juvenileClockInitialized;
    }

    public boolean isStoredProgressionPaused() {
        return storedProgressionPaused;
    }

    public void setStoredProgressionPaused(boolean storedProgressionPaused) {
        this.storedProgressionPaused = storedProgressionPaused;
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
        clone.setGender(gender);
        clone.setActiveProgressMs(activeProgressMs);
        clone.setAgingInitialized(agingInitialized);
        clone.setAgeProgressMs(ageProgressMs);
        clone.setProgressionOwnerId(progressionOwnerId);
        clone.setProgressionClockMs(progressionClockMs);
        clone.setProgressionInitialized(progressionInitialized);
        clone.setLastProgressionWorldMs(lastProgressionWorldMs);
        clone.setLifecycleNowMs(lifecycleNowMs);
        clone.setJuvenileClockInitialized(juvenileClockInitialized);
        clone.setStoredProgressionPaused(storedProgressionPaused);
        return clone;
    }
}
