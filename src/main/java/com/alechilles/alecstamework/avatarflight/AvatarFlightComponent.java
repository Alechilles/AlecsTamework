package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Marks a transformed player as controlled by the avatar-flight prototype.
 */
public final class AvatarFlightComponent implements Component<EntityStore> {
    public static final BuilderCodec<AvatarFlightComponent> CODEC = BuilderCodec.builder(
            AvatarFlightComponent.class,
            AvatarFlightComponent::new
    )
            .<String>append(new KeyedCodec<>("ConfigId", Codec.STRING),
                    AvatarFlightComponent::setConfigId,
                    AvatarFlightComponent::getConfigId)
            .add()
            .<String>append(new KeyedCodec<>("Mode", Codec.STRING),
                    AvatarFlightComponent::setModeName,
                    AvatarFlightComponent::getModeName)
            .add()
            .<Double>append(new KeyedCodec<>("VelocityX", Codec.DOUBLE),
                    AvatarFlightComponent::setVelocityX,
                    AvatarFlightComponent::getVelocityX)
            .add()
            .<Double>append(new KeyedCodec<>("VelocityY", Codec.DOUBLE),
                    AvatarFlightComponent::setVelocityY,
                    AvatarFlightComponent::getVelocityY)
            .add()
            .<Double>append(new KeyedCodec<>("VelocityZ", Codec.DOUBLE),
                    AvatarFlightComponent::setVelocityZ,
                    AvatarFlightComponent::getVelocityZ)
            .add()
            .<Double>append(new KeyedCodec<>("HudPitchRadians", Codec.DOUBLE),
                    AvatarFlightComponent::setHudPitchRadians,
                    AvatarFlightComponent::getHudPitchRadians)
            .add()
            .<Double>append(new KeyedCodec<>("HudTargetSpeedRatio", Codec.DOUBLE),
                    AvatarFlightComponent::setHudTargetSpeedRatio,
                    AvatarFlightComponent::getHudTargetSpeedRatio)
            .add()
            .<Double>append(new KeyedCodec<>("DiveLoad", Codec.DOUBLE),
                    AvatarFlightComponent::setDiveLoad,
                    AvatarFlightComponent::getDiveLoad)
            .add()
            .<Double>append(new KeyedCodec<>("ClimbLoad", Codec.DOUBLE),
                    AvatarFlightComponent::setClimbLoad,
                    AvatarFlightComponent::getClimbLoad)
            .add()
            .<Long>append(new KeyedCodec<>("NextJumpAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextJumpAtMs,
                    AvatarFlightComponent::getNextJumpAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("NextBoostAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextBoostAtMs,
                    AvatarFlightComponent::getNextBoostAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("NextLaunchAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextLaunchAtMs,
                    AvatarFlightComponent::getNextLaunchAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("EnabledAtMs", Codec.LONG),
                    AvatarFlightComponent::setEnabledAtMs,
                    AvatarFlightComponent::getEnabledAtMs)
            .add()
            .<Double>append(new KeyedCodec<>("VigourCharges", Codec.DOUBLE),
                    AvatarFlightComponent::setVigourCharges,
                    AvatarFlightComponent::getVigourCharges)
            .add()
            .<Long>append(new KeyedCodec<>("LastVigourUpdateAtMs", Codec.LONG),
                    AvatarFlightComponent::setLastVigourUpdateAtMs,
                    AvatarFlightComponent::getLastVigourUpdateAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("VigourRechargeBlockedUntilMs", Codec.LONG),
                    AvatarFlightComponent::setVigourRechargeBlockedUntilMs,
                    AvatarFlightComponent::getVigourRechargeBlockedUntilMs)
            .add()
            .<String>append(new KeyedCodec<>("VigourRechargeMode", Codec.STRING),
                    AvatarFlightComponent::setVigourRechargeMode,
                    AvatarFlightComponent::getVigourRechargeMode)
            .add()
            .<Boolean>append(new KeyedCodec<>("ClientFlyingSynced", Codec.BOOLEAN),
                    AvatarFlightComponent::setClientFlyingSynced,
                    AvatarFlightComponent::isClientFlyingSynced)
            .add()
            .<String>append(new KeyedCodec<>("MovementAnimationId", Codec.STRING),
                    AvatarFlightComponent::setMovementAnimationId,
                    AvatarFlightComponent::getMovementAnimationId)
            .add()
            .<Long>append(new KeyedCodec<>("NextMovementAnimationAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextMovementAnimationAtMs,
                    AvatarFlightComponent::getNextMovementAnimationAtMs)
            .add()
            .<String>append(new KeyedCodec<>("FlightFlapAudioMode", Codec.STRING),
                    AvatarFlightComponent::setFlightFlapAudioMode,
                    AvatarFlightComponent::getFlightFlapAudioMode)
            .add()
            .<Long>append(new KeyedCodec<>("NextFlightFlapAudioAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextFlightFlapAudioAtMs,
                    AvatarFlightComponent::getNextFlightFlapAudioAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("NextSuppressedAnimationAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextSuppressedAnimationAtMs,
                    AvatarFlightComponent::getNextSuppressedAnimationAtMs)
            .add()
            .<String>append(new KeyedCodec<>("AbilityAnimationId", Codec.STRING),
                    AvatarFlightComponent::setAbilityAnimationId,
                    AvatarFlightComponent::getAbilityAnimationId)
            .add()
            .<String>append(new KeyedCodec<>("AbilityAnimationSlot", Codec.STRING),
                    AvatarFlightComponent::setAbilityAnimationSlot,
                    AvatarFlightComponent::getAbilityAnimationSlot)
            .add()
            .<String>append(new KeyedCodec<>("AbilityAnimationKind", Codec.STRING),
                    AvatarFlightComponent::setAbilityAnimationKind,
                    AvatarFlightComponent::getAbilityAnimationKind)
            .add()
            .<Long>append(new KeyedCodec<>("AbilityAnimationUntilMs", Codec.LONG),
                    AvatarFlightComponent::setAbilityAnimationUntilMs,
                    AvatarFlightComponent::getAbilityAnimationUntilMs)
            .add()
            .<String>append(new KeyedCodec<>("PitchPoseAnimationId", Codec.STRING),
                    AvatarFlightComponent::setPitchPoseAnimationId,
                    AvatarFlightComponent::getPitchPoseAnimationId)
            .add()
            .<Long>append(new KeyedCodec<>("NextPitchPoseAnimationAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextPitchPoseAnimationAtMs,
                    AvatarFlightComponent::getNextPitchPoseAnimationAtMs)
            .add()
            .<String>append(new KeyedCodec<>("RollPoseAnimationId", Codec.STRING),
                    AvatarFlightComponent::setRollPoseAnimationId,
                    AvatarFlightComponent::getRollPoseAnimationId)
            .add()
            .<Long>append(new KeyedCodec<>("NextRollPoseAnimationAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextRollPoseAnimationAtMs,
                    AvatarFlightComponent::getNextRollPoseAnimationAtMs)
            .add()
            .<Boolean>append(new KeyedCodec<>("LaunchVfxOriginValid", Codec.BOOLEAN),
                    AvatarFlightComponent::setLaunchVfxOriginValid,
                    AvatarFlightComponent::isLaunchVfxOriginValid)
            .add()
            .<Double>append(new KeyedCodec<>("LaunchVfxOriginX", Codec.DOUBLE),
                    AvatarFlightComponent::setLaunchVfxOriginX,
                    AvatarFlightComponent::getLaunchVfxOriginX)
            .add()
            .<Double>append(new KeyedCodec<>("LaunchVfxOriginY", Codec.DOUBLE),
                    AvatarFlightComponent::setLaunchVfxOriginY,
                    AvatarFlightComponent::getLaunchVfxOriginY)
            .add()
            .<Double>append(new KeyedCodec<>("LaunchVfxOriginZ", Codec.DOUBLE),
                    AvatarFlightComponent::setLaunchVfxOriginZ,
                    AvatarFlightComponent::getLaunchVfxOriginZ)
            .add()
            .<Double>append(new KeyedCodec<>("LaunchVfxYawRadians", Codec.DOUBLE),
                    AvatarFlightComponent::setLaunchVfxYawRadians,
                    AvatarFlightComponent::getLaunchVfxYawRadians)
            .add()
            .<Long>append(new KeyedCodec<>("NextLaunchChargeVfxAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextLaunchChargeVfxAtMs,
                    AvatarFlightComponent::getNextLaunchChargeVfxAtMs)
            .add()
            .<Long>append(new KeyedCodec<>("NextLaunchChargeAudioAtMs", Codec.LONG),
                    AvatarFlightComponent::setNextLaunchChargeAudioAtMs,
                    AvatarFlightComponent::getNextLaunchChargeAudioAtMs)
            .add()
            .<Boolean>append(new KeyedCodec<>("LaunchFullChargeAudioPlayed", Codec.BOOLEAN),
                    AvatarFlightComponent::setLaunchFullChargeAudioPlayed,
                    AvatarFlightComponent::isLaunchFullChargeAudioPlayed)
            .add()
            .<Integer>append(new KeyedCodec<>("FastGlideTrailChainId", Codec.INTEGER),
                    AvatarFlightComponent::setFastGlideTrailChainId,
                    AvatarFlightComponent::getFastGlideTrailChainId)
            .add()
            .<String>append(new KeyedCodec<>("ActiveTrailRootInteraction", Codec.STRING),
                    AvatarFlightComponent::setActiveTrailRootInteraction,
                    AvatarFlightComponent::getActiveTrailRootInteraction)
            .add()
            .<Long>append(new KeyedCodec<>("BurstTrailUntilMs", Codec.LONG),
                    AvatarFlightComponent::setBurstTrailUntilMs,
                    AvatarFlightComponent::getBurstTrailUntilMs)
            .add()
            .build();

    private String configId = "";
    private AvatarFlightMode mode = AvatarFlightMode.GROUNDED;
    private double velocityX;
    private double velocityY;
    private double velocityZ;
    private double hudPitchRadians;
    private double hudTargetSpeedRatio;
    private double diveLoad;
    private double climbLoad;
    private long nextJumpAtMs;
    private long nextBoostAtMs;
    private long nextLaunchAtMs;
    private long enabledAtMs;
    private double vigourCharges;
    private long lastVigourUpdateAtMs;
    private long vigourRechargeBlockedUntilMs;
    private String vigourRechargeMode = AvatarFlightVigourService.RechargeMode.NONE.name();
    private boolean clientFlyingSynced;
    private String movementAnimationId = "";
    private long nextMovementAnimationAtMs;
    private String flightFlapAudioMode = "";
    private long nextFlightFlapAudioAtMs;
    private long nextSuppressedAnimationAtMs;
    private String abilityAnimationId = "";
    private String abilityAnimationSlot = "Action";
    private String abilityAnimationKind = "";
    private long abilityAnimationUntilMs;
    private String pitchPoseAnimationId = "";
    private long nextPitchPoseAnimationAtMs;
    private String rollPoseAnimationId = "";
    private long nextRollPoseAnimationAtMs;
    private boolean launchVfxOriginValid;
    private double launchVfxOriginX;
    private double launchVfxOriginY;
    private double launchVfxOriginZ;
    private double launchVfxYawRadians;
    private long nextLaunchChargeVfxAtMs;
    private long nextLaunchChargeAudioAtMs;
    private boolean launchFullChargeAudioPlayed;
    private int fastGlideTrailChainId;
    private String activeTrailRootInteraction = "";
    private long burstTrailUntilMs;

    public AvatarFlightComponent() {
    }

    public AvatarFlightComponent(@Nullable String configId, long enabledAtMs) {
        setConfigId(configId);
        this.enabledAtMs = enabledAtMs;
    }

    @Nullable
    public static ComponentType<EntityStore, AvatarFlightComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance == null ? null : instance.getAvatarFlightComponentType();
    }

    @Nonnull
    public String getConfigId() {
        return configId == null ? "" : configId;
    }

    public void setConfigId(@Nullable String configId) {
        this.configId = configId == null || configId.isBlank() ? "" : configId.trim();
    }

    @Nonnull
    public AvatarFlightMode getMode() {
        return mode == null ? AvatarFlightMode.GROUNDED : mode;
    }

    public void setMode(@Nullable AvatarFlightMode mode) {
        this.mode = mode == null ? AvatarFlightMode.GROUNDED : mode;
    }

    @Nonnull
    public String getModeName() {
        return getMode().name();
    }

    public void setModeName(@Nullable String value) {
        if (value == null || value.isBlank()) {
            mode = AvatarFlightMode.GROUNDED;
            return;
        }
        try {
            mode = AvatarFlightMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            mode = AvatarFlightMode.GROUNDED;
        }
    }

    public double getVelocityX() {
        return velocityX;
    }

    public void setVelocityX(@Nullable Double velocityX) {
        this.velocityX = finiteOrZero(velocityX);
    }

    public double getVelocityY() {
        return velocityY;
    }

    public void setVelocityY(@Nullable Double velocityY) {
        this.velocityY = finiteOrZero(velocityY);
    }

    public double getVelocityZ() {
        return velocityZ;
    }

    public void setVelocityZ(@Nullable Double velocityZ) {
        this.velocityZ = finiteOrZero(velocityZ);
    }

    public double getHudPitchRadians() {
        return finiteOrZero(hudPitchRadians);
    }

    public void setHudPitchRadians(@Nullable Double hudPitchRadians) {
        this.hudPitchRadians = finiteOrZero(hudPitchRadians);
    }

    public double getHudTargetSpeedRatio() {
        return clamp01(finiteOrZero(hudTargetSpeedRatio));
    }

    public void setHudTargetSpeedRatio(@Nullable Double hudTargetSpeedRatio) {
        this.hudTargetSpeedRatio = clamp01(finiteOrZero(hudTargetSpeedRatio));
    }

    public double getDiveLoad() {
        return clamp01(finiteOrZero(diveLoad));
    }

    public void setDiveLoad(@Nullable Double diveLoad) {
        this.diveLoad = clamp01(finiteOrZero(diveLoad));
    }

    public double getClimbLoad() {
        return clamp01(finiteOrZero(climbLoad));
    }

    public void setClimbLoad(@Nullable Double climbLoad) {
        this.climbLoad = clamp01(finiteOrZero(climbLoad));
    }

    public long getNextJumpAtMs() {
        return nextJumpAtMs;
    }

    public void setNextJumpAtMs(@Nullable Long nextJumpAtMs) {
        this.nextJumpAtMs = nextJumpAtMs == null ? 0L : nextJumpAtMs;
    }

    public long getNextBoostAtMs() {
        return nextBoostAtMs;
    }

    public void setNextBoostAtMs(@Nullable Long nextBoostAtMs) {
        this.nextBoostAtMs = nextBoostAtMs == null ? 0L : nextBoostAtMs;
    }

    public long getNextLaunchAtMs() {
        return nextLaunchAtMs;
    }

    public void setNextLaunchAtMs(@Nullable Long nextLaunchAtMs) {
        this.nextLaunchAtMs = nextLaunchAtMs == null ? 0L : nextLaunchAtMs;
    }

    public long getEnabledAtMs() {
        return enabledAtMs;
    }

    public void setEnabledAtMs(@Nullable Long enabledAtMs) {
        this.enabledAtMs = enabledAtMs == null ? 0L : enabledAtMs;
    }

    public double getVigourCharges() {
        return finiteOrZero(vigourCharges);
    }

    public void setVigourCharges(@Nullable Double vigourCharges) {
        this.vigourCharges = finiteOrZero(vigourCharges);
    }

    public long getLastVigourUpdateAtMs() {
        return lastVigourUpdateAtMs;
    }

    public void setLastVigourUpdateAtMs(@Nullable Long lastVigourUpdateAtMs) {
        this.lastVigourUpdateAtMs = lastVigourUpdateAtMs == null ? 0L : lastVigourUpdateAtMs;
    }

    public long getVigourRechargeBlockedUntilMs() {
        return vigourRechargeBlockedUntilMs;
    }

    public void setVigourRechargeBlockedUntilMs(@Nullable Long vigourRechargeBlockedUntilMs) {
        this.vigourRechargeBlockedUntilMs = vigourRechargeBlockedUntilMs == null ? 0L : vigourRechargeBlockedUntilMs;
    }

    @Nonnull
    public String getVigourRechargeMode() {
        return vigourRechargeMode == null || vigourRechargeMode.isBlank()
                ? AvatarFlightVigourService.RechargeMode.NONE.name()
                : vigourRechargeMode;
    }

    public void setVigourRechargeMode(@Nullable String vigourRechargeMode) {
        if (vigourRechargeMode == null || vigourRechargeMode.isBlank()) {
            this.vigourRechargeMode = AvatarFlightVigourService.RechargeMode.NONE.name();
            return;
        }
        String normalized = vigourRechargeMode.trim().toUpperCase(java.util.Locale.ROOT);
        for (AvatarFlightVigourService.RechargeMode mode : AvatarFlightVigourService.RechargeMode.values()) {
            if (mode.name().equals(normalized)) {
                this.vigourRechargeMode = normalized;
                return;
            }
        }
        this.vigourRechargeMode = AvatarFlightVigourService.RechargeMode.NONE.name();
    }

    public boolean isClientFlyingSynced() {
        return clientFlyingSynced;
    }

    public void setClientFlyingSynced(@Nullable Boolean clientFlyingSynced) {
        this.clientFlyingSynced = clientFlyingSynced != null && clientFlyingSynced;
    }

    @Nonnull
    public String getMovementAnimationId() {
        return movementAnimationId == null ? "" : movementAnimationId;
    }

    public void setMovementAnimationId(@Nullable String movementAnimationId) {
        this.movementAnimationId = movementAnimationId == null ? "" : movementAnimationId.trim();
    }

    public long getNextMovementAnimationAtMs() {
        return nextMovementAnimationAtMs;
    }

    public void setNextMovementAnimationAtMs(@Nullable Long nextMovementAnimationAtMs) {
        this.nextMovementAnimationAtMs = nextMovementAnimationAtMs == null ? 0L : nextMovementAnimationAtMs;
    }

    @Nonnull
    public String getFlightFlapAudioMode() {
        return flightFlapAudioMode == null ? "" : flightFlapAudioMode;
    }

    public void setFlightFlapAudioMode(@Nullable String flightFlapAudioMode) {
        this.flightFlapAudioMode = flightFlapAudioMode == null ? "" : flightFlapAudioMode.trim();
    }

    public long getNextFlightFlapAudioAtMs() {
        return nextFlightFlapAudioAtMs;
    }

    public void setNextFlightFlapAudioAtMs(@Nullable Long nextFlightFlapAudioAtMs) {
        this.nextFlightFlapAudioAtMs = nextFlightFlapAudioAtMs == null ? 0L : nextFlightFlapAudioAtMs;
    }

    public long getNextSuppressedAnimationAtMs() {
        return nextSuppressedAnimationAtMs;
    }

    public void setNextSuppressedAnimationAtMs(@Nullable Long nextSuppressedAnimationAtMs) {
        this.nextSuppressedAnimationAtMs = nextSuppressedAnimationAtMs == null ? 0L : nextSuppressedAnimationAtMs;
    }

    @Nonnull
    public String getAbilityAnimationId() {
        return abilityAnimationId == null ? "" : abilityAnimationId;
    }

    public void setAbilityAnimationId(@Nullable String abilityAnimationId) {
        this.abilityAnimationId = abilityAnimationId == null ? "" : abilityAnimationId.trim();
    }

    @Nonnull
    public String getAbilityAnimationSlot() {
        return abilityAnimationSlot != null && abilityAnimationSlot.equalsIgnoreCase("Movement")
                ? "Movement" : "Action";
    }

    public void setAbilityAnimationSlot(@Nullable String abilityAnimationSlot) {
        this.abilityAnimationSlot = abilityAnimationSlot != null
                && abilityAnimationSlot.trim().equalsIgnoreCase("Movement") ? "Movement" : "Action";
    }

    @Nonnull
    public String getAbilityAnimationKind() {
        return abilityAnimationKind == null ? "" : abilityAnimationKind;
    }

    public void setAbilityAnimationKind(@Nullable String abilityAnimationKind) {
        this.abilityAnimationKind = abilityAnimationKind == null ? "" : abilityAnimationKind.trim();
    }

    public long getAbilityAnimationUntilMs() {
        return abilityAnimationUntilMs;
    }

    public void setAbilityAnimationUntilMs(@Nullable Long abilityAnimationUntilMs) {
        this.abilityAnimationUntilMs = abilityAnimationUntilMs == null ? 0L : abilityAnimationUntilMs;
    }

    public void clearAbilityAnimationState() {
        abilityAnimationId = "";
        abilityAnimationSlot = "Action";
        abilityAnimationKind = "";
        abilityAnimationUntilMs = 0L;
    }

    @Nonnull
    public String getPitchPoseAnimationId() {
        return pitchPoseAnimationId == null ? "" : pitchPoseAnimationId;
    }

    public void setPitchPoseAnimationId(@Nullable String pitchPoseAnimationId) {
        this.pitchPoseAnimationId = pitchPoseAnimationId == null ? "" : pitchPoseAnimationId.trim();
    }

    public long getNextPitchPoseAnimationAtMs() {
        return nextPitchPoseAnimationAtMs;
    }

    public void setNextPitchPoseAnimationAtMs(@Nullable Long nextPitchPoseAnimationAtMs) {
        this.nextPitchPoseAnimationAtMs = nextPitchPoseAnimationAtMs == null ? 0L : nextPitchPoseAnimationAtMs;
    }

    @Nonnull
    public String getRollPoseAnimationId() {
        return rollPoseAnimationId == null ? "" : rollPoseAnimationId;
    }

    public void setRollPoseAnimationId(@Nullable String rollPoseAnimationId) {
        this.rollPoseAnimationId = rollPoseAnimationId == null ? "" : rollPoseAnimationId.trim();
    }

    public long getNextRollPoseAnimationAtMs() {
        return nextRollPoseAnimationAtMs;
    }

    public void setNextRollPoseAnimationAtMs(@Nullable Long nextRollPoseAnimationAtMs) {
        this.nextRollPoseAnimationAtMs = nextRollPoseAnimationAtMs == null ? 0L : nextRollPoseAnimationAtMs;
    }

    public boolean isLaunchVfxOriginValid() { return launchVfxOriginValid; }
    public void setLaunchVfxOriginValid(@Nullable Boolean value) { launchVfxOriginValid = value != null && value; }
    public double getLaunchVfxOriginX() { return finiteOrZero(launchVfxOriginX); }
    public void setLaunchVfxOriginX(@Nullable Double value) { launchVfxOriginX = finiteOrZero(value); }
    public double getLaunchVfxOriginY() { return finiteOrZero(launchVfxOriginY); }
    public void setLaunchVfxOriginY(@Nullable Double value) { launchVfxOriginY = finiteOrZero(value); }
    public double getLaunchVfxOriginZ() { return finiteOrZero(launchVfxOriginZ); }
    public void setLaunchVfxOriginZ(@Nullable Double value) { launchVfxOriginZ = finiteOrZero(value); }
    public double getLaunchVfxYawRadians() { return finiteOrZero(launchVfxYawRadians); }
    public void setLaunchVfxYawRadians(@Nullable Double value) { launchVfxYawRadians = finiteOrZero(value); }
    public long getNextLaunchChargeVfxAtMs() { return nextLaunchChargeVfxAtMs; }
    public void setNextLaunchChargeVfxAtMs(@Nullable Long value) { nextLaunchChargeVfxAtMs = value == null ? 0L : value; }
    public long getNextLaunchChargeAudioAtMs() { return nextLaunchChargeAudioAtMs; }
    public void setNextLaunchChargeAudioAtMs(@Nullable Long value) { nextLaunchChargeAudioAtMs = value == null ? 0L : value; }
    public boolean isLaunchFullChargeAudioPlayed() { return launchFullChargeAudioPlayed; }
    public void setLaunchFullChargeAudioPlayed(@Nullable Boolean value) { launchFullChargeAudioPlayed = value != null && value; }
    public int getFastGlideTrailChainId() { return fastGlideTrailChainId; }
    public void setFastGlideTrailChainId(@Nullable Integer value) { fastGlideTrailChainId = value == null ? 0 : value; }
    @Nonnull
    public String getActiveTrailRootInteraction() {
        return activeTrailRootInteraction == null ? "" : activeTrailRootInteraction;
    }
    public void setActiveTrailRootInteraction(@Nullable String value) {
        activeTrailRootInteraction = value == null ? "" : value.trim();
    }
    public long getBurstTrailUntilMs() { return burstTrailUntilMs; }
    public void setBurstTrailUntilMs(@Nullable Long value) { burstTrailUntilMs = value == null ? 0L : value; }

    public void captureLaunchVfxOrigin(double x, double y, double z, double yawRadians) {
        launchVfxOriginValid = true;
        launchVfxOriginX = finiteOrZero(x);
        launchVfxOriginY = finiteOrZero(y);
        launchVfxOriginZ = finiteOrZero(z);
        launchVfxYawRadians = finiteOrZero(yawRadians);
    }

    public void clearLaunchVfxState() {
        launchVfxOriginValid = false;
        launchVfxOriginX = 0.0;
        launchVfxOriginY = 0.0;
        launchVfxOriginZ = 0.0;
        launchVfxYawRadians = 0.0;
        nextLaunchChargeVfxAtMs = 0L;
    }

    public void clearLaunchAudioState() {
        nextLaunchChargeAudioAtMs = 0L;
        launchFullChargeAudioPlayed = false;
    }

    public void setVelocity(double x, double y, double z) {
        velocityX = finiteOrZero(x);
        velocityY = finiteOrZero(y);
        velocityZ = finiteOrZero(z);
    }

    @Override
    public AvatarFlightComponent clone() {
        AvatarFlightComponent clone = new AvatarFlightComponent(configId, enabledAtMs);
        clone.mode = getMode();
        clone.velocityX = velocityX;
        clone.velocityY = velocityY;
        clone.velocityZ = velocityZ;
        clone.hudPitchRadians = getHudPitchRadians();
        clone.hudTargetSpeedRatio = getHudTargetSpeedRatio();
        clone.diveLoad = getDiveLoad();
        clone.climbLoad = getClimbLoad();
        clone.nextJumpAtMs = nextJumpAtMs;
        clone.nextBoostAtMs = nextBoostAtMs;
        clone.nextLaunchAtMs = nextLaunchAtMs;
        clone.vigourCharges = getVigourCharges();
        clone.lastVigourUpdateAtMs = lastVigourUpdateAtMs;
        clone.vigourRechargeBlockedUntilMs = vigourRechargeBlockedUntilMs;
        clone.vigourRechargeMode = getVigourRechargeMode();
        clone.clientFlyingSynced = clientFlyingSynced;
        clone.movementAnimationId = getMovementAnimationId();
        clone.nextMovementAnimationAtMs = nextMovementAnimationAtMs;
        clone.flightFlapAudioMode = getFlightFlapAudioMode();
        clone.nextFlightFlapAudioAtMs = nextFlightFlapAudioAtMs;
        clone.nextSuppressedAnimationAtMs = nextSuppressedAnimationAtMs;
        clone.abilityAnimationId = getAbilityAnimationId();
        clone.abilityAnimationSlot = getAbilityAnimationSlot();
        clone.abilityAnimationKind = getAbilityAnimationKind();
        clone.abilityAnimationUntilMs = abilityAnimationUntilMs;
        clone.pitchPoseAnimationId = getPitchPoseAnimationId();
        clone.nextPitchPoseAnimationAtMs = nextPitchPoseAnimationAtMs;
        clone.rollPoseAnimationId = getRollPoseAnimationId();
        clone.nextRollPoseAnimationAtMs = nextRollPoseAnimationAtMs;
        clone.launchVfxOriginValid = launchVfxOriginValid;
        clone.launchVfxOriginX = getLaunchVfxOriginX();
        clone.launchVfxOriginY = getLaunchVfxOriginY();
        clone.launchVfxOriginZ = getLaunchVfxOriginZ();
        clone.launchVfxYawRadians = getLaunchVfxYawRadians();
        clone.nextLaunchChargeVfxAtMs = nextLaunchChargeVfxAtMs;
        clone.nextLaunchChargeAudioAtMs = nextLaunchChargeAudioAtMs;
        clone.launchFullChargeAudioPlayed = launchFullChargeAudioPlayed;
        clone.fastGlideTrailChainId = fastGlideTrailChainId;
        clone.activeTrailRootInteraction = getActiveTrailRootInteraction();
        clone.burstTrailUntilMs = burstTrailUntilMs;
        return clone;
    }

    private static double finiteOrZero(@Nullable Double value) {
        return value != null && Double.isFinite(value) ? value : 0.0;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
